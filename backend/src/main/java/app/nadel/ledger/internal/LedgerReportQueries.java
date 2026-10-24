package app.nadel.ledger.internal;

import app.nadel.ledger.CorrectionCause;
import app.nadel.ledger.CorrectionKind;
import app.nadel.ledger.CorrectionRow;
import app.nadel.ledger.CorrectionSource;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.MonthGrouping;
import app.nadel.ledger.MonthStatus;
import app.nadel.ledger.MonthTypeTotals;
import app.nadel.ledger.PlotDebts;
import app.nadel.money.Money;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
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
        Optional<UUID> typeId = chargeTypes.all().stream().filter(type -> chargeTypeCode.equals(type.code()))
                .map(ChargeTypeFacts::id).findFirst();
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
