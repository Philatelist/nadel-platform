package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The corrections of the billing months ({@link LedgerReadModel#corrections}, feature 013 §3.8) on a fixed ledger of a
 * cooperative of its own (booked at, Kyiv time in brackets):
 * <pre>
 * January 2025 (closed)  J1  P1 membership 30.00, 01.01-31.01
 * February 2025 (closed) F2  P2 membership 30.00, 01.02-28.02; reversed by hand in February -30.00 (20.02 10:00)
 * March 2025 (open)      M1  P1 membership 25.00, 01.03-31.03
 *                        recalculation R (cause TARIFF_VERSION, "Виправлено ставку"): adjustment of J1 -5.00
 *                        (10.03 23:30 UTC = 11.03 01:30 Kyiv) and reversal of M1 -25.00 (one second later)
 *                        losses run L (reversed 15.03 12:00): L2 P1 electricity 7.60 reversed by hand on 14.03 before
 *                        the run, L1 P2 electricity 12.40 reversed by the run reversal (15.03 12:00)
 * April 2025 (open)      common consumer run C (reversed 02.04 09:00): C1 P1 electricity 9.00 reversed by the run
 * </pre>
 * The regular charges are never listed; P1 and P2 are numbers "1" and "2".
 */
class LedgerCorrectionsTests extends FreshCooperativeSupport {

    private static final YearMonth JANUARY = YearMonth.of(2025, 1);
    private static final YearMonth FEBRUARY = YearMonth.of(2025, 2);
    private static final YearMonth MARCH = YearMonth.of(2025, 3);
    private static final YearMonth APRIL = YearMonth.of(2025, 4);

    @Autowired
    LedgerReadModel readModel;

    @Autowired
    TransactionTemplate tx;

    private UUID p1;
    private UUID p2;
    private UUID membership;
    private UUID electricity;
    private UUID booker;
    private UUID recalculation;
    private UUID j1;
    private UUID f2;
    private UUID m1;
    private UUID l1;
    private UUID l2;
    private UUID c1;

    private UUID otherCharge;
    private UUID otherReversal;

    @BeforeEach
    void bookTheLedger() {
        p1 = plot("1", "600");
        p2 = plot("2", "600");
        membership = typeId("MEMBERSHIP");
        electricity = typeId("ELECTRICITY");
        booker = owner().queryForObject("select id from member where tenant_id = ? and first_name = 'CHAIRMAN'",
                UUID.class, tenant);

        j1 = charge(p1, membership, "TARIFF", null, "30.00", "2025-01-01", "2025-01-31", "2025-01");
        f2 = charge(p2, membership, "TARIFF", null, "30.00", "2025-02-01", "2025-02-28", "2025-02");
        reversal(f2, "MANUAL_REVERSAL", null, "Помилкове нарахування", "2025-02-20 10:00:00+02");
        m1 = charge(p1, membership, "TARIFF", null, "25.00", "2025-03-01", "2025-03-31", "2025-03");

        recalculation = UUID.randomUUID();
        owner().update("insert into recalculation (id, tenant_id, cause, reason, total, line_count, plot_count, "
                + "digest, created_by) values (?, ?, 'TARIFF_VERSION', 'Виправлено ставку', -30.00, 2, 1, 'test', ?)",
                recalculation, tenant, booker);
        owner().update("insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key, adjusts_charge_id, recalculation_id, "
                + "reason, created_by, created_at) select tenant_id, plot_id, charge_type_id, ?, 'ADJUSTMENT', "
                + "'RECALCULATION', -5.00, service_from, service_to, accrued_on, ?, id, ?, 'Виправлено ставку', ?, "
                + "timestamptz '2025-03-10 23:30:00+00' from charge where id = ?", monthRow("2025-03"),
                "ADJUSTMENT:" + recalculation + ":" + j1, recalculation, booker, j1);
        reversal(m1, "RECALCULATION", recalculation, "Виправлено ставку", "2025-03-10 23:30:01+00");

        UUID lossesRun = run("losses_run", "2025-03", "2025-03-15 12:00:00+02");
        l2 = charge(p1, electricity, "LOSSES", lossesRun, "7.60", "2025-03-01", "2025-03-14", "2025-03");
        l1 = charge(p2, electricity, "LOSSES", lossesRun, "12.40", "2025-03-01", "2025-03-14", "2025-03");
        reversal(l2, "MANUAL_REVERSAL", null, "Не той лічильник", "2025-03-14 09:00:00+02");
        reversal(l1, "MANUAL_REVERSAL", null, "Помилковий розрахунок", "2025-03-15 12:00:00+02");

        UUID commonRun = run("common_consumer_run", "2025-04", "2025-04-02 09:00:00+03");
        c1 = charge(p1, electricity, "COMMON_CONSUMER", commonRun, "9.00", "2025-03-01", "2025-03-31", "2025-04");
        reversal(c1, "MANUAL_REVERSAL", null, "Помилковий розрахунок", "2025-04-02 09:00:00+03");

        closeMonth("2025-01");
        closeMonth("2025-02");
    }

    @AfterEach
    void removeOtherCooperativeRows() {
        if (otherCharge != null) {
            UUID charge = otherCharge;
            UUID reversal = otherReversal;
            asLedgerOwner(sql -> {
                sql.update("delete from charge where id = ?", reversal);
                sql.update("delete from charge where id = ?", charge);
            });
            otherCharge = null;
        }
    }

    @Test
    void everyReversalAndAdjustmentIsListedWithItsOriginalChargeSourceCauseAndAuthor() {
        List<CorrectionRow> rows = corrections(tenant, FEBRUARY, APRIL, Set.of(), Set.of(), 100);

        assertThat(rows).extracting(this::line).containsExactly(
                "2025-02 2025-02-20 P2 membership REVERSAL -30.00 of F2 2025-02-01..2025-02-28 30.00 MANUAL_REVERSAL"
                        + " - Помилкове нарахування chairman",
                "2025-03 2025-03-11 P1 membership ADJUSTMENT -5.00 of J1 2025-01-01..2025-01-31 30.00 RECALCULATION"
                        + " TARIFF_VERSION Виправлено ставку chairman",
                "2025-03 2025-03-11 P1 membership REVERSAL -25.00 of M1 2025-03-01..2025-03-31 25.00 RECALCULATION"
                        + " TARIFF_VERSION Виправлено ставку chairman",
                "2025-03 2025-03-14 P1 electricity REVERSAL -7.60 of L2 2025-03-01..2025-03-14 7.60 MANUAL_REVERSAL"
                        + " - Не той лічильник chairman",
                "2025-03 2025-03-15 P2 electricity REVERSAL -12.40 of L1 2025-03-01..2025-03-14 12.40 RUN_REVERSAL"
                        + " - Помилковий розрахунок chairman",
                "2025-04 2025-04-02 P1 electricity REVERSAL -9.00 of C1 2025-03-01..2025-03-31 9.00 RUN_REVERSAL"
                        + " - Помилковий розрахунок chairman");
        assertThat(rows.get(1).recalculationId()).isEqualTo(recalculation);
        assertThat(rows.get(1).bookedAt()).isEqualTo(Instant.parse("2025-03-10T23:30:00Z"));
        assertThat(rows.getFirst().recalculationId()).isNull();
    }

    @Test
    void theKindFilterListsOnlyReversalsOrOnlyAdjustments() {
        List<CorrectionRow> reversals = corrections(tenant, FEBRUARY, APRIL, Set.of(), Set.of(CorrectionKind.REVERSAL),
                100);
        List<CorrectionRow> adjustments = corrections(tenant, FEBRUARY, APRIL, Set.of(),
                Set.of(CorrectionKind.ADJUSTMENT), 100);
        List<CorrectionRow> both = corrections(tenant, FEBRUARY, APRIL, Set.of(),
                Set.of(CorrectionKind.REVERSAL, CorrectionKind.ADJUSTMENT), 100);

        assertThat(reversals).hasSize(5).allMatch(row -> row.kind() == CorrectionKind.REVERSAL);
        assertThat(adjustments).extracting(CorrectionRow::originalChargeId).containsExactly(j1);
        assertThat(both).hasSize(6);
    }

    @Test
    void theMonthRangeIsTheBillingMonthTheCorrectionIsBookedIn() {
        // the adjustment of J1 (service period January) is booked in March, the reversal of C1 (March) in April
        assertThat(corrections(tenant, MARCH, MARCH, Set.of(), Set.of(), 100))
                .extracting(CorrectionRow::originalChargeId).containsExactly(j1, m1, l2, l1);
        assertThat(corrections(tenant, APRIL, APRIL, Set.of(), Set.of(), 100))
                .extracting(CorrectionRow::originalChargeId).containsExactly(c1);
        assertThat(corrections(tenant, JANUARY, JANUARY, Set.of(), Set.of(), 100)).isEmpty();
        assertThat(corrections(tenant, FEBRUARY, FEBRUARY, Set.of(), Set.of(), 100))
                .extracting(CorrectionRow::originalChargeId).containsExactly(f2);
    }

    @Test
    void theChargeTypeFilterListsOnlyThoseTypes() {
        assertThat(corrections(tenant, FEBRUARY, APRIL, Set.of(electricity), Set.of(), 100))
                .extracting(CorrectionRow::originalChargeId).containsExactly(l2, l1, c1);
        assertThat(corrections(tenant, FEBRUARY, APRIL, Set.of(membership, electricity), Set.of(), 100)).hasSize(6);
    }

    @Test
    void theLimitCutsTheListInItsOrder() {
        assertThat(corrections(tenant, FEBRUARY, APRIL, Set.of(), Set.of(), 2))
                .extracting(CorrectionRow::originalChargeId).containsExactly(f2, j1);
    }

    @Test
    void theCorrectionsOfAnotherCooperativeAreNeverListed() {
        Side other = side(OTHER_TENANT);
        otherCharge = UUID.randomUUID();
        otherReversal = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, 'REGULAR', "
                + "'TARIFF', 99.00, date '2031-01-01', date '2031-01-31', date '2031-01-01', ?)", otherCharge,
                OTHER_TENANT, other.plot(), other.chargeType(), other.month(), "TEST:" + otherCharge);
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, reverses_charge_id, reason) values (?, ?, ?, "
                + "?, ?, 'REVERSAL', 'MANUAL_REVERSAL', -99.00, date '2031-01-01', date '2031-01-31', "
                + "date '2031-01-01', ?, 'Інший кооператив')", otherReversal, OTHER_TENANT, other.plot(),
                other.chargeType(), other.month(), otherCharge);
        YearMonth month = YearMonth.parse(MONTH.substring(0, 7));

        assertThat(corrections(tenant, FEBRUARY, month, Set.of(), Set.of(), 100))
                .extracting(CorrectionRow::chargeId).doesNotContain(otherReversal).hasSize(6);
        assertThat(corrections(OTHER_TENANT, FEBRUARY, month, Set.of(), Set.of(), 100))
                .extracting(CorrectionRow::chargeId).containsExactly(otherReversal);
    }

    @Test
    void invalidArgumentsAreRefused() {
        assertThatThrownBy(() -> corrections(tenant, APRIL, MARCH, Set.of(), Set.of(), 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> corrections(tenant, MARCH, APRIL, Set.of(), Set.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itRunsOnlyInTheCallersTransaction() {
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> readModel.corrections(MARCH, APRIL, Set.of(),
                Set.of(), 10))).isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---- fixture

    private List<CorrectionRow> corrections(UUID cooperative, YearMonth from, YearMonth to, Set<UUID> types,
            Set<CorrectionKind> kinds, int limit) {
        return TenantContext.runAs(cooperative, () -> tx.execute(status -> readModel.corrections(from, to, types,
                kinds, limit)));
    }

    private String line(CorrectionRow row) {
        return String.join(" ", row.billingMonth().toString(), row.bookedOn().toString(),
                row.plotId().equals(p1) ? "P1" : "P2",
                row.chargeTypeId().equals(membership) ? "membership" : "electricity", row.kind().name(),
                row.amount().toString(), "of " + name(row.originalChargeId()),
                row.originalServiceFrom() + ".." + row.originalServiceTo(), row.originalAmount().toString(),
                row.source().name(), row.cause() == null ? "-" : row.cause().name(), row.reason(),
                booker.equals(row.bookedBy()) ? "chairman" : String.valueOf(row.bookedBy()));
    }

    private String name(UUID charge) {
        if (charge.equals(j1)) {
            return "J1";
        }
        if (charge.equals(f2)) {
            return "F2";
        }
        if (charge.equals(m1)) {
            return "M1";
        }
        if (charge.equals(l1)) {
            return "L1";
        }
        if (charge.equals(l2)) {
            return "L2";
        }
        return charge.equals(c1) ? "C1" : charge.toString();
    }

    private UUID monthRow(String month) {
        owner().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month + "-01");
        return owner().queryForObject("select id from billing_month where tenant_id = ? and month = ?::date",
                UUID.class, tenant, month + "-01");
    }

    /** A regular charge written as the database owner, of a run when {@code run} is set. */
    private UUID charge(UUID plot, UUID type, String source, UUID run, String amount, String from, String to,
            String bookedIn) {
        UUID id = UUID.randomUUID();
        owner().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key, run_id, created_by) values (?, ?, ?, ?, ?, "
                + "'REGULAR', ?, ?, ?::date, ?::date, ?::date, ?, ?, ?)", id, tenant, plot, type, monthRow(bookedIn),
                source, new BigDecimal(amount), from, to, from, "TEST:" + id, run, booker);
        return id;
    }

    /** A reversal in the original's month, with its run, at the given time. */
    private void reversal(UUID original, String source, UUID recalculationId, String reason, String at) {
        owner().update("insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, reverses_charge_id, run_id, recalculation_id, reason, "
                + "created_by, created_at) select tenant_id, plot_id, charge_type_id, billing_month_id, 'REVERSAL', ?, "
                + "-amount, service_from, service_to, accrued_on, id, run_id, ?, ?, ?, ?::timestamptz from charge "
                + "where id = ?", source, recalculationId, reason, booker, at, original);
    }

    /** A run (losses or common consumer) booked in the month and reversed at the given time. */
    private UUID run(String table, String month, String reversedAt) {
        UUID id = UUID.randomUUID();
        owner().update("insert into " + table + " (id, tenant_id, from_date, to_date, status, billing_month_id, "
                + "total, created_by, reversed_at, reversed_by, reverse_reason) values (?, ?, date '2025-03-01', "
                + "date '2025-03-14', 'REVERSED', ?, 20.00, ?, ?::timestamptz, ?, 'Помилковий розрахунок')", id,
                tenant, monthRow(month), booker, reversedAt, booker);
        return id;
    }
}
