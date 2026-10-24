package app.nadel.ledger.internal;

import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.MonthGrouping;
import app.nadel.ledger.MonthStatus;
import app.nadel.ledger.MonthTypeTotals;
import app.nadel.ledger.PlotDebts;
import app.nadel.money.Money;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private final EntityManager entityManager;

    LedgerReportQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
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
