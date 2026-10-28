package app.nadel.ledger.internal;

import app.nadel.ledger.internal.OpenAmounts.AllocationEntry;
import app.nadel.ledger.internal.OpenAmounts.ChargeEntry;
import app.nadel.ledger.internal.OpenAmounts.PaymentEntry;
import app.nadel.money.Money;
import app.nadel.tariffs.ChargeTypeCatalog;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the ledger of plots as far as allocation and debt are concerned (feature 015, technical considerations 2.1):
 * per plot its charges, allocations (with releases) and payments (with reversals) in one {@code UNION ALL} statement
 * per chunk of plots, and their {@link PlotLedgerState} with the cooperative's order of charge types
 * ({@link ChargeTypeCatalog#all()}). Runs in the caller's transaction; RLS hides other cooperatives' rows. A path that
 * writes allocations loads under the plot ledger locks ({@link PlotLedgerLock}), so what it reads stays true until it
 * commits.
 */
@Component
class PlotLedgerQueries {

    /** Plots per statement. */
    private static final int CHUNK = 500;

    // t: C charge, A allocation, P payment. ref1: reverses (charge, payment) or releases (allocation); ref2: adjusts
    // (charge) or the payment (allocation); ref3: the charge (allocation).
    private static final String LEDGER = """
            select 'C' as t, c.id, c.plot_id, c.kind, c.charge_type_id, c.amount, c.accrued_on as day,
                   c.service_from, c.created_at, c.reverses_charge_id as ref1, c.adjusts_charge_id as ref2,
                   cast(null as uuid) as ref3
            from charge c where c.plot_id in (:plotIds)
            union all
            select 'A', a.id, a.plot_id, null, null, a.amount, null, null, a.created_at, a.releases_allocation_id,
                   a.payment_id, a.charge_id
            from allocation a where a.plot_id in (:plotIds)
            union all
            select 'P', p.id, p.plot_id, p.kind, null, p.amount, p.received_on, null, p.created_at,
                   p.reverses_payment_id, null, null
            from payment p where p.plot_id in (:plotIds)
            order by 1, 9, 2
            """;

    /**
     * The ledger of one plot: its rows as stored and their allocation view.
     *
     * @param charges     every charge of the plot (all kinds), in booking order
     * @param allocations every allocation and release, in the order they were written
     * @param payments    every payment and payment reversal, in the order they were entered
     * @param state       the open amounts, what is left of the allocations and payments
     */
    record PlotLedger(UUID plotId, List<ChargeEntry> charges, List<AllocationEntry> allocations,
            List<PaymentEntry> payments, PlotLedgerState state) {

        PlotLedger {
            Objects.requireNonNull(plotId, "plotId");
            charges = List.copyOf(charges);
            allocations = List.copyOf(allocations);
            payments = List.copyOf(payments);
            Objects.requireNonNull(state, "state");
        }
    }

    private final EntityManager entityManager;
    private final ChargeTypeCatalog chargeTypes;

    PlotLedgerQueries(EntityManager entityManager, ChargeTypeCatalog chargeTypes) {
        this.entityManager = entityManager;
        this.chargeTypes = chargeTypes;
    }

    /**
     * The ledger of every plot asked, in the order asked (a plot without rows, or not of the cooperative, has empty
     * lists).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Map<UUID, PlotLedger> load(Collection<UUID> plotIds) {
        List<UUID> ids = plotIds.stream().distinct().toList();
        Map<UUID, Rows> byPlot = new LinkedHashMap<>();
        ids.forEach(id -> byPlot.put(id, new Rows()));
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<?> rows = entityManager.createNativeQuery(LEDGER)
                    .setParameter("plotIds", ids.subList(from, Math.min(ids.size(), from + CHUNK)))
                    .getResultList();
            for (Object row : rows) {
                Object[] v = (Object[]) row;
                Rows plot = byPlot.get((UUID) v[2]);
                switch ((String) v[0]) {
                    case "C" -> plot.charges.add(new ChargeEntry((UUID) v[1], (UUID) v[2],
                            ChargeKind.valueOf((String) v[3]), (UUID) v[4], money(v[5]),
                            Objects.requireNonNull(date(v[6])), date(v[7]), (Instant) v[8], (UUID) v[9],
                            (UUID) v[10]));
                    case "A" -> plot.allocations.add(new AllocationEntry((UUID) v[1], (UUID) v[10], (UUID) v[11],
                            (UUID) v[2], money(v[5]), (UUID) v[9], (Instant) v[8]));
                    case "P" -> plot.payments.add(new PaymentEntry((UUID) v[1], (UUID) v[2],
                            PaymentKind.valueOf((String) v[3]), money(v[5]), Objects.requireNonNull(date(v[6])),
                            (Instant) v[8], (UUID) v[9]));
                    default -> throw new IllegalStateException("Unknown ledger row type");
                }
            }
        }
        Map<UUID, Integer> positions = OpenAmounts.positions(chargeTypes.all());
        Map<UUID, PlotLedger> ledgers = new LinkedHashMap<>();
        byPlot.forEach((plotId, rows) -> ledgers.put(plotId, new PlotLedger(plotId, rows.charges, rows.allocations,
                rows.payments, OpenAmounts.of(rows.charges, rows.allocations, rows.payments, positions))));
        return ledgers;
    }

    private static final class Rows {
        private final List<ChargeEntry> charges = new ArrayList<>();
        private final List<AllocationEntry> allocations = new ArrayList<>();
        private final List<PaymentEntry> payments = new ArrayList<>();
    }

    private static Money money(Object value) {
        return Money.of((BigDecimal) value);
    }

    private static @Nullable LocalDate date(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate) value;
    }
}
