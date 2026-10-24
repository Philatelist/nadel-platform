package app.nadel.ledger.internal;

import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.MonthGrouping;
import app.nadel.ledger.MonthStatus;
import app.nadel.ledger.MonthTypeTotals;
import app.nadel.money.Money;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
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
