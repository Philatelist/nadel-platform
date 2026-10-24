package app.nadel.ledger.internal;

import app.nadel.ledger.ChargedPart;
import app.nadel.ledger.ChargedRow;
import app.nadel.ledger.ChargedRowKind;
import app.nadel.ledger.ChargedSource;
import app.nadel.ledger.CommonConsumerRunLineOutcome;
import app.nadel.ledger.CommonConsumerRunLineRow;
import app.nadel.ledger.CorrectionCause;
import app.nadel.ledger.CorrectionKind;
import app.nadel.ledger.CorrectionRow;
import app.nadel.ledger.CorrectionSource;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.LossesRunNodeOutcome;
import app.nadel.ledger.LossesRunNodeRow;
import app.nadel.ledger.MonthGrouping;
import app.nadel.ledger.MonthStatus;
import app.nadel.ledger.MonthTypeTotals;
import app.nadel.ledger.PlotDebts;
import app.nadel.ledger.RunStatus;
import app.nadel.meters.MeterKind;
import app.nadel.meters.Quantity;
import app.nadel.money.Money;
import app.nadel.settings.CommonConsumerSplit;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger's read facade for the reports ({@link LedgerReadModel}, feature 013). Native queries over {@code charge}
 * (and {@code billing_month}), aggregated in the database, in the caller's transaction and under RLS like every ledger
 * query.
 * <p>
 * {@link #monthTotals}: the billing month grouping uses the predicate of the billing months screen
 * ({@code ChargeRepository.totalsPerMonthAndType}: every row booked in the month, of every kind, {@code SUM(amount)}),
 * so the effective total of a month and type is the screen's figure cent for cent; the split by kind uses
 * {@code FILTER} clauses over the same rows. The service month grouping takes the month of {@code service_from}.
 * <p>
 * {@link #debtsAsOf}: {@code DebtCalculator}'s rule in SQL over the charges dated on or before the day, one row per plot
 * and charge type (the signed sum and the oldest unpaid debt date of the type); the plot's total is the sum of its types
 * and its oldest date the earliest of theirs, which is {@code DebtCalculator}'s answer because a reversal and an
 * adjustment always share the plot and charge type of the charge they correct. Plots go 500 per {@code IN} list.
 * <p>
 * {@link #corrections}: the {@code REVERSAL} and {@code ADJUSTMENT} rows of the billing months, each joined to the charge
 * it corrects ({@code reverses_charge_id} or {@code adjusts_charge_id}) and to its {@code recalculation}; the source is
 * worked out in SQL as {@link CorrectionSource} documents it (a {@code MANUAL_REVERSAL} row is a run reversal when its
 * losses or common consumer run is {@code REVERSED} with {@code reversed_at} equal to the row's {@code created_at}: the
 * run reversal writes both from one {@code Instant}). The day it was booked is {@code created_at} on the cooperative's
 * clock (Europe/Kyiv).
 * <p>
 * {@link #chargedInYear}: the signed sum per plot of the charges of the type (by code, through
 * {@code tariffs.ChargeTypeCatalog}) whose {@code service_from} lies in the year, opening balances left out; plots go
 * 500 per {@code IN} list ({@code charge_plot_type_idx}).
 * <p>
 * {@link #electricityCharged}: every row (regular, reversal, adjustment) of the regular consumption and losses charges
 * of the electricity type and of the common consumer charges whose service period overlaps the range, joined to the
 * charge it corrects for the source and the original service period; then the parts of the rows and of the states the
 * corrections start from, 500 charges per {@code IN} list, added exactly in Java ({@code Quantity}): one row per charge,
 * never per part, since a charge's parts are rounded once together. {@link #waterCharged} is the same query with the
 * water predicate (consumption and fixed fee of the water type, and the common consumer shares whose calculation names
 * the split {@code CONNECTED_PLOTS}: a well pump's), 500 plots per {@code IN} list, the chunks' rows merged in the
 * query's order and cut to the limit.
 * <p>
 * {@link #openIntervalItems}: the {@code OPEN} items of kind {@code INTERVAL} of the queue per meter, 500 meters per
 * {@code IN} list.
 * <p>
 * {@link #lossesRunsInRange} and {@link #commonConsumerRunsInRange}: the nodes or consumers of the runs overlapping the
 * range as stored, booked and reversed, with the distinct plots that have a regular charge of the run for the node (a
 * part with its {@code node_meter_id}) or the consumer (its calculation's {@code consumerMeterId}).
 */
@Component
class LedgerReportQueries implements LedgerReadModel {

    /** Bound to an {@code IN} list that a {@code true} "all" flag switches off (an empty list is not valid SQL). */
    private static final List<UUID> NONE = List.of(new UUID(0L, 0L));

    private static final String MONTH_TOTALS = """
            select to_char(cast(%1$s as timestamp), 'YYYY-MM') as month,
                   %2$s as status,
                   c.charge_type_id,
                   count(distinct c.plot_id) as plots,
                   coalesce(sum(c.amount) filter (where c.kind = 'REGULAR'), 0) as regular,
                   coalesce(sum(c.amount) filter (where c.kind = 'OPENING'), 0) as opening,
                   coalesce(sum(c.amount) filter (where c.kind = 'REVERSAL'), 0) as reversals,
                   coalesce(sum(c.amount) filter (where c.kind = 'ADJUSTMENT'), 0) as adjustments,
                   sum(c.amount) as effective
            from charge c
            %3$s
            where %4$s between :from and :to
              and (:allTypes = true or c.charge_type_id in (:typeIds))
              and (:allPlots = true or c.plot_id in (:plotIds))
            group by 1, 2, 3
            order by 1, 3
            """;

    /** By the billing month a row is booked in: the screen's grouping, with the month's status. */
    private static final String BY_BILLING_MONTH = MONTH_TOTALS.formatted("bm.month", "bm.status",
            "join billing_month bm on bm.id = c.billing_month_id", "bm.month");

    /** By the month of the first service day; no status. */
    private static final String BY_SERVICE_MONTH = MONTH_TOTALS.formatted("c.service_from",
            "cast(null as text)", "", "c.service_from");

    /** Values per {@code IN} list. */
    private static final int CHUNK = 500;

    /**
     * Per plot and charge type: the signed sum of the counted charges, and the oldest debt date among the counted charges
     * that are neither a reversal nor an adjustment, are not reversed by a counted reversal, and whose amount plus their
     * counted adjustments is above zero ({@code DebtCalculator.of} over the same lines).
     */
    private static final String DEBTS_AS_OF = """
            with lines as (
                select c.id, c.plot_id, c.charge_type_id, c.amount, c.accrued_on, c.reverses_charge_id,
                       c.adjusts_charge_id
                from charge c
                where c.plot_id in (:plotIds)
                  and c.accrued_on <= :asOf
                  and (:allTypes = true or c.charge_type_id in (:typeIds))
            ),
            adjusted as (
                select a.adjusts_charge_id as id, sum(a.amount) as amount
                from lines a
                where a.adjusts_charge_id is not null
                group by a.adjusts_charge_id
            ),
            unpaid as (
                select l.plot_id, l.charge_type_id, min(l.accrued_on) as oldest
                from lines l
                left join adjusted a on a.id = l.id
                where l.reverses_charge_id is null
                  and l.adjusts_charge_id is null
                  and not exists (select 1 from lines r where r.reverses_charge_id = l.id)
                  and l.amount + coalesce(a.amount, 0) > 0
                group by l.plot_id, l.charge_type_id
            )
            select t.plot_id, t.charge_type_id, sum(t.amount) as debt, u.oldest
            from lines t
            left join unpaid u on u.plot_id = t.plot_id and u.charge_type_id = t.charge_type_id
            group by t.plot_id, t.charge_type_id, u.oldest
            order by t.plot_id, t.charge_type_id
            """;

    /**
     * The reversals and adjustments booked in the billing months, with the charge each corrects and its recalculation.
     * A {@code MANUAL_REVERSAL} row of a run's charge comes from the reversal of the whole run when that run was reversed
     * at the instant the row was created (both are written with one timestamp); otherwise it was reversed by hand.
     */
    private static final String CORRECTIONS = """
            select c.id, c.kind, c.created_at, to_char(cast(bm.month as timestamp), 'YYYY-MM') as month, c.plot_id,
                   c.charge_type_id, o.id as original_id, o.service_from, o.service_to, o.amount as original_amount,
                   c.amount,
                   case
                       when c.source = 'RECALCULATION' then 'RECALCULATION'
                       when c.run_id is not null
                            and (exists (select 1 from losses_run lr where lr.id = c.run_id
                                             and lr.status = 'REVERSED' and lr.reversed_at = c.created_at)
                                 or exists (select 1 from common_consumer_run cr where cr.id = c.run_id
                                             and cr.status = 'REVERSED' and cr.reversed_at = c.created_at))
                           then 'RUN_REVERSAL'
                       else 'MANUAL_REVERSAL'
                   end as source,
                   c.recalculation_id, r.cause, c.reason, c.created_by
            from charge c
            join billing_month bm on bm.id = c.billing_month_id
            join charge o on o.id = coalesce(c.reverses_charge_id, c.adjusts_charge_id)
            left join recalculation r on r.id = c.recalculation_id
            where c.kind in (:kinds)
              and bm.month between :from and :to
              and (:allTypes = true or c.charge_type_id in (:typeIds))
            order by bm.month, c.created_at, c.id
            limit :limit
            """;

    /** Per plot, the signed sum of the charges of one type whose first service day lies in the range; no openings. */
    private static final String CHARGED_IN_RANGE = """
            select c.plot_id, sum(c.amount) as charged
            from charge c
            where c.plot_id in (:plotIds)
              and c.charge_type_id = :typeId
              and c.kind <> 'OPENING'
              and c.service_from between :from and :to
            group by c.plot_id
            """;

    /**
     * Every row of a charge whose service period overlaps the range: the {@code REGULAR} charge {@code o} that the
     * predicate ({@code %s}, over {@code o}) selects, and the row {@code c} itself (the charge, a reversal or an
     * adjustment of it). {@code before_id} is, for a correction, the state of the charge it starts from: the latest
     * adjustment of the charge booked before it, else the charge itself (an adjustment's parts are the recalculated
     * charge's, so its quantity is its parts minus that state's). The consumer of a common consumer share is the one its
     * calculation names; the booking times come along for the merge of several plot chunks.
     */
    private static final String CHARGED = """
            select c.id, c.kind, o.source, c.plot_id, c.charge_type_id,
                   to_char(cast(bm.month as timestamp), 'YYYY-MM') as month, o.service_from, o.service_to,
                   c.accrued_on, case when c.kind = 'REGULAR' then null else o.id end as original_id, o.run_id,
                   o.meter_id, c.amount,
                   case when c.kind = 'REGULAR' then null
                        else coalesce((select a.id from charge a
                                       where a.adjusts_charge_id = o.id
                                         and (a.created_at, a.id) < (c.created_at, c.id)
                                       order by a.created_at desc, a.id desc
                                       limit 1), o.id)
                   end as before_id,
                   case when o.source = 'COMMON_CONSUMER'
                        then cast(o.calculation ->> 'consumerMeterId' as uuid) end as consumer_id,
                   o.created_at as original_at, c.created_at as row_at, o.id as charge_id
            from charge c
            join charge o on o.id = coalesce(c.reverses_charge_id, c.adjusts_charge_id, c.id)
            join billing_month bm on bm.id = c.billing_month_id
            where c.kind in ('REGULAR', 'REVERSAL', 'ADJUSTMENT')
              and o.kind = 'REGULAR'
              and (%s)
              and o.service_from <= :to
              and o.service_to >= :from
            order by o.service_from, o.service_to, o.plot_id, o.source, o.created_at, o.id, c.created_at, c.id
            limit :limit
            """;

    /**
     * The electricity rows: consumption and losses of the electricity type, and every common consumer share (whatever
     * its consumer).
     */
    private static final String ELECTRICITY_CHARGED = CHARGED.formatted(
            "(o.source in ('CONSUMPTION', 'LOSSES') and o.charge_type_id = :type) or o.source = 'COMMON_CONSUMER'");

    /**
     * The water rows of the plots: consumption and the fixed fee of the water type, and the common consumer shares split
     * among the plots connected to the consumer as their water source (a well pump).
     */
    private static final String WATER_CHARGED = CHARGED.formatted("""
            o.plot_id in (:plotIds)
                   and ((o.source in ('CONSUMPTION', 'TARIFF') and o.charge_type_id = :type)
                        or (o.source = 'COMMON_CONSUMER' and o.calculation ->> 'split' = 'CONNECTED_PLOTS'))""");

    /** The open reading interval items of the queue per meter. */
    private static final String OPEN_INTERVAL_ITEMS = """
            select i.meter_id, count(*) as items
            from not_charged_item i
            where i.kind = 'INTERVAL'
              and i.status = 'OPEN'
              and i.meter_id in (:meterIds)
            group by i.meter_id
            """;

    /**
     * The order of the charged rows, as the query sorts them: service period, plot, source, the charge's booking time
     * and id, the row's booking time and id ({@code uuid} compares as unsigned bytes in PostgreSQL).
     */
    private static final Comparator<Object[]> CHARGED_ORDER = Comparator
            .comparing((Object[] v) -> Objects.requireNonNull(date(v[6])))
            .thenComparing(v -> Objects.requireNonNull(date(v[7])))
            .thenComparing(v -> (UUID) v[3], LedgerReportQueries::compareUuids)
            .thenComparing(v -> (String) v[2])
            .thenComparing(v -> (Instant) v[15])
            .thenComparing(v -> (UUID) v[17], LedgerReportQueries::compareUuids)
            .thenComparing(v -> (Instant) v[16])
            .thenComparing(v -> (UUID) v[0], LedgerReportQueries::compareUuids);

    /** The parts of the charges, in a stable order (a part has no position of its own). */
    private static final String PARTS = """
            select p.charge_id, p.tariff_version_id, p.days, p.units_num, p.units_den, p.rate, p.node_meter_id
            from charge_part p
            where p.charge_id in (:ids)
            order by p.charge_id, p.node_meter_id nulls first, p.rate, p.days, p.id
            """;

    /**
     * The nodes of the losses runs overlapping the range, with the distinct plots that have a regular losses charge of
     * the run with a part for the node.
     */
    private static final String LOSSES_RUNS = """
            select r.id, r.from_date, r.to_date, r.status, r.reversed_at,
                   to_char(cast(bm.month as timestamp), 'YYYY-MM') as month, r.total, n.node_meter_id, n.node_kind,
                   n.node_name, n.position, n.outcome, n.consumption, n.children, n.losses, n.pct, n.calculated,
                   n.amount, jsonb_array_length(n.plots) as shared,
                   (select count(distinct c.plot_id) from charge c
                    where c.run_id = r.id and c.kind = 'REGULAR' and c.source = 'LOSSES'
                      and exists (select 1 from charge_part p
                                  where p.charge_id = c.id and p.node_meter_id = n.node_meter_id)) as charged
            from losses_run r
            join billing_month bm on bm.id = r.billing_month_id
            join losses_run_node n on n.run_id = r.id
            where r.from_date <= :to
              and r.to_date >= :from
            order by r.from_date, r.to_date, r.created_at, r.id, n.position
            """;

    /**
     * The consumers of the common consumer runs overlapping the range, with the distinct plots that have a regular
     * charge of the run for the consumer (its calculation names the consumer).
     */
    private static final String COMMON_CONSUMER_RUNS = """
            select r.id, r.from_date, r.to_date, r.status, r.reversed_at,
                   to_char(cast(bm.month as timestamp), 'YYYY-MM') as month, r.total, l.consumer_meter_id,
                   l.consumer_name, l.position, l.split, l.outcome, l.consumption, l.calculated, l.plot_days, l.amount,
                   jsonb_array_length(l.plots) as shared,
                   (select count(distinct c.plot_id) from charge c
                    where c.run_id = r.id and c.kind = 'REGULAR' and c.source = 'COMMON_CONSUMER'
                      and c.calculation ->> 'consumerMeterId' = cast(l.consumer_meter_id as text)) as charged
            from common_consumer_run r
            join billing_month bm on bm.id = r.billing_month_id
            join common_consumer_run_line l on l.run_id = r.id
            where r.from_date <= :to
              and r.to_date >= :from
            order by r.from_date, r.to_date, r.created_at, r.id, l.position
            """;

    /** The code of the seeded electricity charge type. */
    private static final String ELECTRICITY = "ELECTRICITY";

    /** The code of the seeded water charge type. */
    private static final String WATER = "WATER";

    private final EntityManager entityManager;
    private final ChargeTypeCatalog chargeTypes;
    private final Clock clock;

    LedgerReportQueries(EntityManager entityManager, ChargeTypeCatalog chargeTypes, Clock clock) {
        this.entityManager = entityManager;
        this.chargeTypes = chargeTypes;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<MonthTypeTotals> monthTotals(YearMonth fromMonth, YearMonth toMonth, Set<UUID> chargeTypeIds,
            @Nullable Set<UUID> plotIds, MonthGrouping grouping) {
        Objects.requireNonNull(fromMonth, "fromMonth");
        Objects.requireNonNull(toMonth, "toMonth");
        Objects.requireNonNull(chargeTypeIds, "chargeTypeIds");
        Objects.requireNonNull(grouping, "grouping");
        if (toMonth.isBefore(fromMonth)) {
            throw new IllegalArgumentException("toMonth is before fromMonth");
        }
        if (plotIds != null && plotIds.isEmpty()) {
            return List.of();
        }
        boolean byBillingMonth = grouping == MonthGrouping.BILLING_MONTH;
        // a billing month is stored as its first day; a service month covers every day of the month
        LocalDate to = byBillingMonth ? toMonth.atDay(1) : toMonth.atEndOfMonth();
        List<?> rows = entityManager.createNativeQuery(byBillingMonth ? BY_BILLING_MONTH : BY_SERVICE_MONTH)
                .setParameter("from", fromMonth.atDay(1))
                .setParameter("to", to)
                .setParameter("allTypes", chargeTypeIds.isEmpty())
                .setParameter("typeIds", orNone(chargeTypeIds))
                .setParameter("allPlots", plotIds == null)
                .setParameter("plotIds", plotIds == null ? NONE : List.copyOf(plotIds))
                .getResultList();
        return rows.stream().map(row -> totals((Object[]) row)).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, PlotDebts> debtsAsOf(LocalDate asOf, Set<UUID> chargeTypeIds, Collection<UUID> plotIds) {
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(chargeTypeIds, "chargeTypeIds");
        List<UUID> ids = plotIds.stream().distinct().toList();
        Map<UUID, Accumulator> byPlot = new LinkedHashMap<>();
        ids.forEach(id -> byPlot.put(id, new Accumulator()));
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<?> rows = entityManager.createNativeQuery(DEBTS_AS_OF)
                    .setParameter("plotIds", ids.subList(from, Math.min(ids.size(), from + CHUNK)))
                    .setParameter("asOf", asOf)
                    .setParameter("allTypes", chargeTypeIds.isEmpty())
                    .setParameter("typeIds", orNone(chargeTypeIds))
                    .getResultList();
            for (Object row : rows) {
                Object[] values = (Object[]) row;
                byPlot.get((UUID) values[0]).add((UUID) values[1], money(values[2]), date(values[3]));
            }
        }
        Map<UUID, PlotDebts> debts = new LinkedHashMap<>();
        byPlot.forEach((plotId, sum) -> debts.put(plotId, sum.debts(plotId, asOf)));
        return debts;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<CorrectionRow> corrections(YearMonth fromMonth, YearMonth toMonth, Set<UUID> chargeTypeIds,
            Set<CorrectionKind> kinds, int limit) {
        Objects.requireNonNull(fromMonth, "fromMonth");
        Objects.requireNonNull(toMonth, "toMonth");
        Objects.requireNonNull(chargeTypeIds, "chargeTypeIds");
        Objects.requireNonNull(kinds, "kinds");
        if (toMonth.isBefore(fromMonth)) {
            throw new IllegalArgumentException("toMonth is before fromMonth");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit is below 1");
        }
        Set<CorrectionKind> asked = kinds.isEmpty() ? Set.of(CorrectionKind.values()) : kinds;
        List<?> rows = entityManager.createNativeQuery(CORRECTIONS)
                .setParameter("kinds", asked.stream().map(Enum::name).toList())
                .setParameter("from", fromMonth.atDay(1))
                .setParameter("to", toMonth.atDay(1))
                .setParameter("allTypes", chargeTypeIds.isEmpty())
                .setParameter("typeIds", orNone(chargeTypeIds))
                .setParameter("limit", limit)
                .getResultList();
        return rows.stream().map(row -> correction((Object[]) row)).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Money> chargedInYear(String chargeTypeCode, int year, Collection<UUID> plotIds) {
        Objects.requireNonNull(chargeTypeCode, "chargeTypeCode");
        List<UUID> ids = plotIds.stream().distinct().toList();
        Map<UUID, Money> charged = new LinkedHashMap<>();
        ids.forEach(id -> charged.put(id, Money.ZERO));
        Optional<UUID> typeId = typeId(chargeTypeCode);
        if (typeId.isEmpty()) {
            return charged;
        }
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<?> rows = entityManager.createNativeQuery(CHARGED_IN_RANGE)
                    .setParameter("plotIds", ids.subList(from, Math.min(ids.size(), from + CHUNK)))
                    .setParameter("typeId", typeId.get())
                    .setParameter("from", LocalDate.of(year, 1, 1))
                    .setParameter("to", LocalDate.of(year, 12, 31))
                    .getResultList();
            for (Object row : rows) {
                Object[] values = (Object[]) row;
                charged.put((UUID) values[0], money(values[1]));
            }
        }
        return charged;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ChargedRow> electricityCharged(LocalDate from, LocalDate to, int limit) {
        requireRange(from, to);
        if (limit < 1) {
            throw new IllegalArgumentException("limit is below 1");
        }
        List<Object[]> rows = new ArrayList<>();
        for (Object row : entityManager.createNativeQuery(ELECTRICITY_CHARGED)
                .setParameter("type", typeId(ELECTRICITY).orElse(NONE.getFirst()))
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("limit", limit)
                .getResultList()) {
            rows.add((Object[]) row);
        }
        return chargedRows(rows);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ChargedRow> waterCharged(LocalDate from, LocalDate to, Collection<UUID> plotIds, int limit) {
        requireRange(from, to);
        Objects.requireNonNull(plotIds, "plotIds");
        if (limit < 1) {
            throw new IllegalArgumentException("limit is below 1");
        }
        List<UUID> ids = plotIds.stream().distinct().toList();
        UUID water = typeId(WATER).orElse(NONE.getFirst());
        List<Object[]> rows = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += CHUNK) {
            for (Object row : entityManager.createNativeQuery(WATER_CHARGED)
                    .setParameter("plotIds", ids.subList(start, Math.min(ids.size(), start + CHUNK)))
                    .setParameter("type", water)
                    .setParameter("from", from)
                    .setParameter("to", to)
                    .setParameter("limit", limit)
                    .getResultList()) {
                rows.add((Object[]) row);
            }
        }
        // each chunk holds its first rows in this order, so the first rows of all are among them
        rows.sort(CHARGED_ORDER);
        return chargedRows(rows.size() > limit ? rows.subList(0, limit) : rows);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Integer> openIntervalItems(Collection<UUID> meterIds) {
        Objects.requireNonNull(meterIds, "meterIds");
        List<UUID> ids = meterIds.stream().distinct().toList();
        Map<UUID, Integer> items = new LinkedHashMap<>();
        ids.forEach(id -> items.put(id, 0));
        for (int start = 0; start < ids.size(); start += CHUNK) {
            List<?> rows = entityManager.createNativeQuery(OPEN_INTERVAL_ITEMS)
                    .setParameter("meterIds", ids.subList(start, Math.min(ids.size(), start + CHUNK)))
                    .getResultList();
            for (Object row : rows) {
                Object[] values = (Object[]) row;
                items.put((UUID) values[0], Math.toIntExact(((Number) values[1]).longValue()));
            }
        }
        return items;
    }

    /** The rows of {@link #electricityCharged} or {@link #waterCharged} with their parts (one more query). */
    private List<ChargedRow> chargedRows(List<Object[]> rows) {
        Set<UUID> priced = new LinkedHashSet<>();
        for (Object[] values : rows) {
            if (!ChargedRowKind.REVERSAL.name().equals(values[1])) {
                priced.add((UUID) values[0]);
            }
            if (values[13] != null) {
                priced.add((UUID) values[13]);
            }
        }
        Map<UUID, List<ChargedPart>> parts = parts(List.copyOf(priced));
        return rows.stream().map(row -> chargedRow(row, parts)).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LossesRunNodeRow> lossesRunsInRange(LocalDate from, LocalDate to) {
        requireRange(from, to);
        List<?> rows = entityManager.createNativeQuery(LOSSES_RUNS)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        return rows.stream().map(row -> {
            Object[] v = (Object[]) row;
            return new LossesRunNodeRow((UUID) v[0], Objects.requireNonNull(date(v[1])),
                    Objects.requireNonNull(date(v[2])), RunStatus.valueOf((String) v[3]), (Instant) v[4],
                    YearMonth.parse((String) v[5]), money(v[6]), (UUID) v[7], MeterKind.valueOf((String) v[8]),
                    (String) v[9], ((Number) v[10]).intValue(), LossesRunNodeOutcome.valueOf((String) v[11]),
                    quantity(v[12]), quantity(v[13]), quantity(v[14]), (BigDecimal) v[15], (Boolean) v[16],
                    money(v[17]), ((Number) v[18]).intValue(), ((Number) v[19]).intValue());
        }).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<CommonConsumerRunLineRow> commonConsumerRunsInRange(LocalDate from, LocalDate to) {
        requireRange(from, to);
        List<?> rows = entityManager.createNativeQuery(COMMON_CONSUMER_RUNS)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        return rows.stream().map(row -> {
            Object[] v = (Object[]) row;
            return new CommonConsumerRunLineRow((UUID) v[0], Objects.requireNonNull(date(v[1])),
                    Objects.requireNonNull(date(v[2])), RunStatus.valueOf((String) v[3]), (Instant) v[4],
                    YearMonth.parse((String) v[5]), money(v[6]), (UUID) v[7], (String) v[8],
                    ((Number) v[9]).intValue(), CommonConsumerSplit.valueOf((String) v[10]),
                    CommonConsumerRunLineOutcome.valueOf((String) v[11]), quantity(v[12]), (Boolean) v[13],
                    ((Number) v[14]).longValue(), money(v[15]), ((Number) v[16]).intValue(),
                    ((Number) v[17]).intValue());
        }).toList();
    }

    /** The parts of the charges by charge, 500 charges per {@code IN} list; a charge without parts is absent. */
    private Map<UUID, List<ChargedPart>> parts(List<UUID> chargeIds) {
        Map<UUID, List<ChargedPart>> parts = new HashMap<>();
        for (int from = 0; from < chargeIds.size(); from += CHUNK) {
            List<?> rows = entityManager.createNativeQuery(PARTS)
                    .setParameter("ids", chargeIds.subList(from, Math.min(chargeIds.size(), from + CHUNK)))
                    .getResultList();
            for (Object row : rows) {
                Object[] v = (Object[]) row;
                Quantity units = fraction((BigDecimal) v[3], (BigDecimal) v[4]);
                parts.computeIfAbsent((UUID) v[0], id -> new ArrayList<>()).add(new ChargedPart((UUID) v[1],
                        ((Number) v[2]).intValue(), units, (BigDecimal) v[5], (UUID) v[6]));
            }
        }
        return parts;
    }

    /**
     * A row of {@link #electricityCharged} or {@link #waterCharged}: a regular charge and an adjustment with their own
     * parts, a reversal with the parts of the state it cancels negated; the quantity is the row's parts minus those of
     * the state a correction starts from (for a reversal its negated parts already are that difference), none for a
     * tariff charge (its parts count plots or sotkas, not a consumption).
     */
    private static ChargedRow chargedRow(Object[] v, Map<UUID, List<ChargedPart>> parts) {
        ChargedRowKind kind = ChargedRowKind.valueOf((String) v[1]);
        UUID before = (UUID) v[13];
        List<ChargedPart> own;
        Quantity quantity;
        if (kind == ChargedRowKind.REVERSAL) {
            own = parts.getOrDefault(before, List.of()).stream()
                    .map(part -> new ChargedPart(part.tariffVersionId(), part.days(),
                            Quantity.ZERO.minus(part.quantity()), part.rate(), part.nodeMeterId()))
                    .toList();
            quantity = sum(own);
        } else {
            own = parts.getOrDefault((UUID) v[0], List.of());
            quantity = before == null ? sum(own) : sum(own).minus(sum(parts.getOrDefault(before, List.of())));
        }
        ChargedSource source = ChargedSource.valueOf((String) v[2]);
        return new ChargedRow((UUID) v[0], kind, source, (UUID) v[3], (UUID) v[4], YearMonth.parse((String) v[5]),
                Objects.requireNonNull(date(v[6])), Objects.requireNonNull(date(v[7])),
                Objects.requireNonNull(date(v[8])), (UUID) v[9], (UUID) v[10], (UUID) v[11], (UUID) v[14],
                source == ChargedSource.TARIFF ? null : quantity, own, money(v[12]));
    }

    private static Quantity sum(List<ChargedPart> parts) {
        Quantity sum = Quantity.ZERO;
        for (ChargedPart part : parts) {
            sum = sum.plus(part.quantity());
        }
        return sum;
    }

    /** A quantity stored with three decimals for display, exactly as stored; null stays null. */
    private static @Nullable Quantity quantity(@Nullable Object value) {
        return value == null ? null : fraction((BigDecimal) value, BigDecimal.ONE);
    }

    /** {@code numerator / denominator} exactly, both decimals as stored ({@code charge_part.units_num/units_den}). */
    private static Quantity fraction(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal n = numerator.scale() < 0 ? numerator.setScale(0) : numerator;
        BigDecimal d = denominator.scale() < 0 ? denominator.setScale(0) : denominator;
        return Quantity.ofFraction(n.unscaledValue().multiply(BigInteger.TEN.pow(d.scale())),
                d.unscaledValue().multiply(BigInteger.TEN.pow(n.scale())));
    }

    private Optional<UUID> typeId(String code) {
        return chargeTypes.all().stream().filter(type -> code.equals(type.code())).map(ChargeTypeFacts::id)
                .findFirst();
    }

    private static void requireRange(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to is before from");
        }
    }

    private CorrectionRow correction(Object[] row) {
        Instant bookedAt = (Instant) row[2];
        UUID recalculationId = (UUID) row[12];
        return new CorrectionRow((UUID) row[0], CorrectionKind.valueOf((String) row[1]), bookedAt,
                LocalDate.ofInstant(bookedAt, clock.getZone()), YearMonth.parse((String) row[3]), (UUID) row[4],
                (UUID) row[5], (UUID) row[6], Objects.requireNonNull(date(row[7])), Objects.requireNonNull(date(row[8])),
                money(row[9]), money(row[10]), CorrectionSource.valueOf((String) row[11]), recalculationId,
                row[13] == null ? null : CorrectionCause.valueOf((String) row[13]), (String) row[14], (UUID) row[15]);
    }

    /** The types of one plot as they come from the query, folded into its debts. */
    private static final class Accumulator {

        private final Map<UUID, Money> byType = new LinkedHashMap<>();
        private Money total = Money.ZERO;
        private @Nullable LocalDate oldest;

        void add(UUID chargeTypeId, Money debt, @Nullable LocalDate oldestOfType) {
            byType.put(chargeTypeId, debt);
            total = total.plus(debt);
            if (oldestOfType != null && (oldest == null || oldestOfType.isBefore(oldest))) {
                oldest = oldestOfType;
            }
        }

        PlotDebts debts(UUID plotId, LocalDate asOf) {
            Integer days = oldest == null ? null : Math.toIntExact(ChronoUnit.DAYS.between(oldest, asOf));
            return new PlotDebts(plotId, byType, total, oldest, days);
        }
    }

    private static @Nullable LocalDate date(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate) value;
    }

    /** The order of {@code uuid} in PostgreSQL: the 16 bytes compared unsigned, most significant first. */
    private static int compareUuids(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    private static Collection<UUID> orNone(Set<UUID> ids) {
        return ids.isEmpty() ? NONE : List.copyOf(ids);
    }

    private static MonthTypeTotals totals(Object[] row) {
        MonthStatus status = row[1] == null ? null : MonthStatus.valueOf((String) row[1]);
        return new MonthTypeTotals(YearMonth.parse((String) row[0]), status, (UUID) row[2],
                Math.toIntExact(((Number) row[3]).longValue()), money(row[4]), money(row[5]), money(row[6]),
                money(row[7]), money(row[8]));
    }

    /** The exact database sum ({@code numeric(14,2)} rows): {@code Money.of} only fixes the scale of a zero. */
    private static Money money(Object value) {
        return Money.of((BigDecimal) value);
    }
}
