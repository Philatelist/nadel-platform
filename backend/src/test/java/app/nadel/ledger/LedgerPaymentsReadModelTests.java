package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.money.Money;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * What the read model of the reports says about payments (feature 015 §6, technical considerations 2.3 and 2.7): paid,
 * debt and prepayment as of a day by the value date of each allocation ({@code GREATEST(received_on, accrued_on)}), the
 * paid totals per charge type on the accrual basis of the charged ones, the paid amounts of the charges by month while
 * their effective totals stay those of the billing months screen, the payments list and the payer notes port. The
 * worked ledger of a cooperative of its own (debt date or day received in brackets):
 * <pre>
 * P1  membership J 300.00 (01.01.2025, January), F 300.00 (01.02.2025, February); electricity E 250.00 (20.01.2025,
 *     January): charged 850.00
 *     A cash 500.00 (25.01.2025, receipt 1, January)  -> J 300.00, E 200.00
 *     C cash 100.00 (05.02.2025, receipt 2, February) -> F 100.00, released again (100.00 prepayment)
 *     B bank 200.00 (10.03.2025, "ПД-17", March)      -> E 50.00, F 150.00
 * P2  membership J2 30.00 (01.01.2025, January)
 *     D cash 50.00 (10.01.2025, receipt 3, January)   -> J2 30.00 (20.00 prepayment)
 *     R cash 40.00 (12.01.2025, receipt 4, January), reversed (its reversal keeps 12.01.2025)
 * P3  membership K 100.00 (01.02.2025, February)
 *     G bank 150.00 (05.01.2025, January)             -> K 100.00 (value date 01.02.2025; 50.00 prepayment)
 * </pre>
 * As of 31.01.2025: P1 membership 300/300/0, electricity 250/200/50, total 550/500/50, oldest unpaid E (20.01, 11
 * days), prepayment 0; P3 has no counted charge (K is dated later) and its G is all prepayment, 150.00. As of
 * 28.02.2025: P1 membership 600/300/300 (C's allocation and its release both count from 05.02 and cancel out), oldest E
 * (39 days), prepayment 100.00 (B not yet received); P3 100/100/0, prepayment 50.00. As of 31.03.2025: P1 850.00
 * charged, 700.00 paid, 150.00 debt (F, 01.02, 58 days), prepayment 100.00.
 */
class LedgerPaymentsReadModelTests extends FreshCooperativeSupport {

    private static final LocalDate JAN_31 = LocalDate.of(2025, 1, 31);
    private static final LocalDate FEB_28 = LocalDate.of(2025, 2, 28);
    private static final LocalDate MAR_31 = LocalDate.of(2025, 3, 31);
    private static final YearMonth JAN = YearMonth.of(2025, 1);
    private static final YearMonth FEB = YearMonth.of(2025, 2);
    private static final YearMonth MAR = YearMonth.of(2025, 3);

    @Autowired
    LedgerReadModel readModel;

    @Autowired
    PaymentNotes paymentNotes;

    @Autowired
    PersonalDataCipher cipher;

    @Autowired
    TransactionTemplate tx;

    private UUID p1;
    private UUID p2;
    private UUID p3;
    private UUID membership;
    private UUID electricity;
    private UUID paymentA;
    private UUID paymentB;
    private UUID paymentC;
    private UUID paymentD;
    private UUID paymentR;
    private UUID paymentG;

    @BeforeEach
    void bookTheLedger() {
        p1 = plot("1", "600");
        p2 = plot("2", "600");
        p3 = plot("3", "600");
        membership = typeId("MEMBERSHIP");
        electricity = typeId("ELECTRICITY");

        UUID j = charge(p1, membership, "300.00", "2025-01-01", "2025-01");
        UUID f = charge(p1, membership, "300.00", "2025-02-01", "2025-02");
        UUID e = charge(p1, electricity, "250.00", "2025-01-20", "2025-01");
        UUID j2 = charge(p2, membership, "30.00", "2025-01-01", "2025-01");
        UUID k = charge(p3, membership, "100.00", "2025-02-01", "2025-02");

        paymentA = payment(p1, "CASH", "500.00", "2025-01-25", "2025-01", 1L, null, "Брат власника");
        allocate(paymentA, j, p1, "300.00");
        allocate(paymentA, e, p1, "200.00");
        paymentC = payment(p1, "CASH", "100.00", "2025-02-05", "2025-02", 2L, null, null);
        UUID placed = allocate(paymentC, f, p1, "100.00");
        release(placed, paymentC, f, p1, "-100.00");
        paymentB = payment(p1, "BANK", "200.00", "2025-03-10", "2025-03", null, "ПД-17", null);
        allocate(paymentB, e, p1, "50.00");
        allocate(paymentB, f, p1, "150.00");

        paymentD = payment(p2, "CASH", "50.00", "2025-01-10", "2025-01", 3L, null, null);
        allocate(paymentD, j2, p2, "30.00");
        paymentR = payment(p2, "CASH", "40.00", "2025-01-12", "2025-01", 4L, null, null);
        reversePayment(paymentR);

        paymentG = payment(p3, "BANK", "150.00", "2025-01-05", "2025-01", null, null, null);
        allocate(paymentG, k, p3, "100.00");

        closeMonth("2025-01");
        closeMonth("2025-02");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    // ---- debts as of a day

    @Test
    void aPaymentCountsFromTheDayItWasReceivedAndAPaymentReceivedLaterIsNotCounted() {
        Map<UUID, PlotDebts> endOfJanuary = debts(JAN_31, Set.of(), List.of(p1, p2, p3));
        assertThat(line(endOfJanuary.get(p1))).isEqualTo("membership=300.00/300.00/0.00 "
                + "electricity=250.00/200.00/50.00 total=550.00/500.00/50.00 prepayment=0.00 oldest=2025-01-20 "
                + "days=11");
        assertThat(line(endOfJanuary.get(p2))).isEqualTo(
                "membership=30.00/30.00/0.00 total=30.00/30.00/0.00 prepayment=20.00 oldest=- days=-");

        Map<UUID, PlotDebts> endOfMarch = debts(MAR_31, Set.of(), List.of(p1));
        // B (10.03) pays the rest of E and half of F: the FS 2.11 figures of a plot charged 850 and paid 700
        assertThat(line(endOfMarch.get(p1))).isEqualTo("membership=600.00/450.00/150.00 "
                + "electricity=250.00/250.00/0.00 total=850.00/700.00/150.00 prepayment=100.00 oldest=2025-02-01 "
                + "days=58");

        // before D was received P2 owes its January charge
        Map<UUID, PlotDebts> beforeD = debts(LocalDate.of(2025, 1, 5), Set.of(), List.of(p2));
        assertThat(line(beforeD.get(p2))).isEqualTo(
                "membership=30.00/0.00/30.00 total=30.00/0.00/30.00 prepayment=0.00 oldest=2025-01-01 days=4");
    }

    @Test
    void aReleaseSharesTheDayOfItsAllocationSoItCancelsItAtEveryDay() {
        Map<UUID, PlotDebts> endOfFebruary = debts(FEB_28, Set.of(), List.of(p1));

        // C's 100.00 on F and its release both count from 05.02: F is unpaid, C is prepayment
        assertThat(line(endOfFebruary.get(p1))).isEqualTo("membership=600.00/300.00/300.00 "
                + "electricity=250.00/200.00/50.00 total=850.00/500.00/350.00 prepayment=100.00 oldest=2025-01-20 "
                + "days=39");
    }

    @Test
    void anAllocationCountsFromTheDebtDateOfItsChargeWhenThePaymentCameEarlier() {
        // G (05.01) was placed on K (01.02): as of January it is all prepayment, from February it pays K
        assertThat(line(debts(JAN_31, Set.of(), List.of(p3)).get(p3))).isEqualTo(
                "total=0.00/0.00/0.00 prepayment=150.00 oldest=- days=-");
        assertThat(line(debts(FEB_28, Set.of(), List.of(p3)).get(p3))).isEqualTo(
                "membership=100.00/100.00/0.00 total=100.00/100.00/0.00 prepayment=50.00 oldest=- days=-");
        assertThat(debts(LocalDate.of(2025, 1, 4), Set.of(), List.of(p3)).get(p3)).isEqualTo(PlotDebts.none(p3));
    }

    @Test
    void aReversedPaymentIsNeverPrepaymentAndItsReversalCountsOnTheDayOfTheOriginal() {
        // R (12.01) and its reversal cancel out from 12.01; D's 20.00 is the prepayment on both sides of that day
        assertThat(debts(LocalDate.of(2025, 1, 11), Set.of(), List.of(p2)).get(p2).prepayment())
                .isEqualTo(Money.parse("20.00"));
        assertThat(debts(LocalDate.of(2025, 1, 12), Set.of(), List.of(p2)).get(p2).prepayment())
                .isEqualTo(Money.parse("20.00"));
    }

    @Test
    void theChargeTypeFilterLeavesThePrepaymentOfThePlotAsItIs() {
        PlotDebts onlyElectricity = debts(MAR_31, Set.of(electricity), List.of(p1)).get(p1);

        assertThat(line(onlyElectricity)).isEqualTo(
                "electricity=250.00/250.00/0.00 total=250.00/250.00/0.00 prepayment=100.00 oldest=- days=-");
    }

    @Test
    void asOfTodayTheDebtsAndThePrepaymentAreThoseOfThePlotAccount() throws Exception {
        Map<UUID, PlotDebts> debts = debts(LocalDate.now(), Set.of(), List.of(p1, p2, p3));

        for (UUID plot : List.of(p1, p2, p3)) {
            JsonNode account = body(send(chairman, get(ACCOUNT.formatted(plot)), null).andExpect(status().isOk()));
            Map<UUID, String> accountDebts = new HashMap<>();
            account.get("debts").forEach(debt -> accountDebts.put(UUID.fromString(debt.get("chargeTypeId").asString()),
                    debt.get("debt").asString()));
            Map<UUID, String> ours = new HashMap<>();
            debts.get(plot).byChargeType().forEach((type, amount) -> ours.put(type, amount.toString()));
            assertThat(ours).as("debts of %s", plot).isEqualTo(accountDebts);
            assertThat(debts.get(plot).total().toString()).isEqualTo(account.get("total").asString());
            assertThat(debts.get(plot).prepayment().toString()).isEqualTo(account.get("prepayment").asString());
        }
    }

    // ---- paid per type, prepayment of the cooperative

    @Test
    void paidByTypeCountsTheAllocationsAsOfTheEndToTheChargesOfTheSameAccrualBasis() {
        Map<UUID, Money> january = read(() -> readModel.paidByType(LocalDate.of(2025, 1, 1), JAN_31));
        // J 300 and J2 30 by A and D; E 200 by A (B came in March)
        assertThat(january).containsOnly(Map.entry(membership, Money.parse("330.00")),
                Map.entry(electricity, Money.parse("200.00")));
        assertThat(read(() -> readModel.chargedByType(LocalDate.of(2025, 1, 1), JAN_31))).containsOnly(
                Map.entry(membership, Money.parse("330.00")), Map.entry(electricity, Money.parse("250.00")));

        // F (C's allocation and release cancel out; B is after 28.02) and K by G
        assertThat(read(() -> readModel.paidByType(LocalDate.of(2025, 2, 1), FEB_28))).containsOnly(
                Map.entry(membership, Money.parse("100.00")));

        // the quarter: J 300 + F 150 + J2 30 + K 100, E 200 + 50
        assertThat(read(() -> readModel.paidByType(LocalDate.of(2025, 1, 1), MAR_31))).containsOnly(
                Map.entry(membership, Money.parse("580.00")), Map.entry(electricity, Money.parse("250.00")));
        assertThatThrownBy(() -> read(() -> readModel.paidByType(MAR_31, JAN_31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePrepaymentOfTheCooperativeIsTheSumOfThePlotsAsOfTheDay() {
        assertThat(read(() -> readModel.prepaymentAsOf(LocalDate.of(2025, 1, 4)))).isEqualTo(Money.ZERO);
        // P2 20.00, P3 150.00
        assertThat(read(() -> readModel.prepaymentAsOf(JAN_31))).isEqualTo(Money.parse("170.00"));
        // P1 100.00 (C), P2 20.00, P3 50.00
        assertThat(read(() -> readModel.prepaymentAsOf(MAR_31))).isEqualTo(Money.parse("170.00"));
    }

    // ---- month totals

    @Test
    void theMonthTotalsGainWhatIsPaidAsOfTheDayWhileTheEffectiveTotalsStayThoseOfTheScreen() throws Exception {
        Map<String, String> endOfJanuary = paidByMonthAndType(JAN_31);
        Map<String, String> endOfMarch = paidByMonthAndType(MAR_31);

        assertThat(endOfJanuary).containsOnly(Map.entry("2025-01 membership", "330.00/330.00"),
                Map.entry("2025-01 electricity", "250.00/200.00"), Map.entry("2025-02 membership", "400.00/0.00"));
        assertThat(endOfMarch).containsOnly(Map.entry("2025-01 membership", "330.00/330.00"),
                Map.entry("2025-01 electricity", "250.00/250.00"), Map.entry("2025-02 membership", "400.00/250.00"));

        // the effective totals are the billing months screen's, whatever the day of the paid amounts
        JsonNode screen = body(send(chairman, get("/api/v1/billing-months"), null).andExpect(status().isOk()));
        List<MonthTypeTotals> rows = read(() -> readModel.monthTotals(JAN, MAR, Set.of(), null,
                MonthGrouping.BILLING_MONTH, JAN_31));
        for (JsonNode month : screen) {
            YearMonth ym = YearMonth.parse(month.get("month").asString());
            Map<String, String> screenTypes = new HashMap<>();
            month.get("totals").forEach(t -> screenTypes.put(t.get("chargeTypeId").asString(),
                    t.get("amount").asString()));
            Map<String, String> ours = rows.stream().filter(row -> row.month().equals(ym)).collect(Collectors.toMap(
                    row -> row.chargeTypeId().toString(), row -> row.effective().toString()));
            assertThat(ours).as("effective of %s", ym).isEqualTo(screenTypes);
        }

        // by service month the paid amounts follow the same charges
        List<MonthTypeTotals> byService = read(() -> readModel.monthTotals(JAN, FEB, Set.of(membership), null,
                MonthGrouping.SERVICE_MONTH, MAR_31));
        assertThat(byService).extracting(row -> row.month() + " " + row.paid()).containsExactly("2025-01 330.00",
                "2025-02 250.00");
    }

    // ---- payments

    @Test
    void thePaymentsOfTheMonthsOrOfTheDaysComeOnePerPaymentWithWhatTheyPayAndWhatIsLeft() {
        List<PaymentRow> january = read(() -> readModel.payments(PaymentFilter.months(JAN, JAN), 100));

        assertThat(january).extracting(PaymentRow::paymentId).containsExactly(paymentG, paymentD, paymentR,
                paymentA);
        PaymentRow a = january.getLast();
        assertThat(a.receiptNumber()).isEqualTo(1L);
        assertThat(a.bankReference()).isNull();
        assertThat(a.receivedOn()).isEqualTo(LocalDate.of(2025, 1, 25));
        assertThat(a.billingMonth()).isEqualTo(JAN);
        assertThat(a.plotId()).isEqualTo(p1);
        assertThat(a.payerMemberId()).isEqualTo(memberId);
        assertThat(a.source()).isEqualTo(PaymentSource.CASH);
        assertThat(a.amount() + " " + a.allocated() + " " + a.remaining() + " " + a.reversed())
                .isEqualTo("500.00 500.00 0.00 false");
        PaymentRow r = january.get(2);
        assertThat(r.amount() + " " + r.allocated() + " " + r.remaining() + " " + r.reversed())
                .isEqualTo("40.00 0.00 0.00 true");
        PaymentRow d = january.get(1);
        assertThat(d.allocated() + " " + d.remaining()).isEqualTo("30.00 20.00");

        List<PaymentRow> days = read(() -> readModel.payments(PaymentFilter.days(LocalDate.of(2025, 2, 1), MAR_31),
                100));
        assertThat(days).extracting(PaymentRow::paymentId).containsExactly(paymentC, paymentB);
        assertThat(days.getFirst().allocated() + " " + days.getFirst().remaining()).isEqualTo("0.00 100.00");
        assertThat(days.getLast().bankReference()).isEqualTo("ПД-17");
        assertThat(days.getLast().receiptNumber()).isNull();
    }

    @Test
    void thePaymentsFilterBySourceAndPlotAndStopAtTheLimit() {
        assertThat(read(() -> readModel.payments(PaymentFilter.months(JAN, MAR).withSource(PaymentSource.BANK), 100)))
                .extracting(PaymentRow::paymentId).containsExactly(paymentG, paymentB);
        assertThat(read(() -> readModel.payments(PaymentFilter.months(JAN, MAR).withPlots(Set.of(p2)), 100)))
                .extracting(PaymentRow::paymentId).containsExactly(paymentD, paymentR);
        assertThat(read(() -> readModel.payments(PaymentFilter.months(JAN, MAR).withPlots(Set.of()), 100))).isEmpty();
        assertThat(read(() -> readModel.payments(PaymentFilter.months(JAN, JAN), 2)))
                .extracting(PaymentRow::paymentId).containsExactly(paymentG, paymentD);
        assertThatThrownBy(() -> read(() -> readModel.payments(PaymentFilter.months(JAN, JAN), 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentFilter.months(MAR, JAN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentFilter(JAN, JAN, JAN_31, JAN_31, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePayerNotesOpenOnlyForACallerWhoMayViewMembers() {
        Set<UUID> asked = Set.of(paymentA, paymentB);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("chairman", null,
                "PERM_MEMBERS_VIEW"));
        assertThat(read(() -> paymentNotes.notes(asked))).containsOnly(Map.entry(paymentA, "Брат власника"));

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("treasurer", null,
                "PERM_PAYMENTS_VIEW", "PERM_REPORTS_VIEW"));
        assertThatThrownBy(() -> read(() -> paymentNotes.notes(asked)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void everyReadRunsOnlyInTheCallersTransaction() {
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> readModel.prepaymentAsOf(MAR_31)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> readModel.payments(PaymentFilter.months(JAN,
                JAN), 1))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> readModel.paidByType(JAN_31, MAR_31)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---- fixture

    private <T> T read(Supplier<T> query) {
        return TenantContext.runAs(tenant, () -> tx.execute(status -> query.get()));
    }

    private Map<UUID, PlotDebts> debts(LocalDate asOf, Set<UUID> types, List<UUID> plots) {
        return read(() -> readModel.debtsAsOf(asOf, types, plots));
    }

    /** "month type" -> "effective/paid" by billing month as of the day. */
    private Map<String, String> paidByMonthAndType(LocalDate asOf) {
        return read(() -> readModel.monthTotals(JAN, MAR, Set.of(), null, MonthGrouping.BILLING_MONTH, asOf))
                .stream().collect(Collectors.toMap(
                        row -> row.month() + " " + (row.chargeTypeId().equals(membership) ? "membership"
                                : "electricity"),
                        row -> row.effective() + "/" + row.paid()));
    }

    /** charged/paid/debt per type, the totals, prepayment, oldest unpaid date and days. */
    private String line(PlotDebts debts) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, UUID> type : List.of(Map.entry("membership", membership),
                Map.entry("electricity", electricity))) {
            Money charged = debts.chargedByType().get(type.getValue());
            if (charged != null) {
                text.append(type.getKey()).append('=').append(charged).append('/')
                        .append(debts.paidByType().get(type.getValue())).append('/')
                        .append(debts.byChargeType().get(type.getValue())).append(' ');
            }
        }
        assertThat(debts.paidByType().keySet()).isEqualTo(debts.chargedByType().keySet())
                .isEqualTo(debts.byChargeType().keySet());
        return text.append("total=").append(debts.charged()).append('/').append(debts.paid()).append('/')
                .append(debts.total()).append(" prepayment=").append(debts.prepayment())
                .append(" oldest=").append(debts.oldestUnpaid() == null ? "-" : debts.oldestUnpaid())
                .append(" days=").append(debts.daysOverdue() == null ? "-" : debts.daysOverdue()).toString();
    }

    private UUID monthRow(String month) {
        owner().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month + "-01");
        return owner().queryForObject("select id from billing_month where tenant_id = ? and month = ?::date",
                UUID.class, tenant, month + "-01");
    }

    /** A regular tariff charge with its debt date as its one service day. */
    private UUID charge(UUID plot, UUID type, String amount, String accruedOn, String bookedIn) {
        UUID id = UUID.randomUUID();
        owner().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, 'REGULAR', "
                + "'TARIFF', ?, ?::date, ?::date, ?::date, ?)", id, tenant, plot, type, monthRow(bookedIn),
                new BigDecimal(amount), accruedOn, accruedOn, accruedOn, "TEST:" + id);
        return id;
    }

    /** A payment of the plot paid by the test's member, its own group; the note sealed as the app seals it. */
    private UUID payment(UUID plot, String source, String amount, String receivedOn, String bookedIn, Long receipt,
            String bankReference, String note) {
        UUID id = UUID.randomUUID();
        byte[] sealed = note == null ? null : TenantContext.runAs(tenant, () -> tx.execute(status ->
                cipher.encrypt(tenant, new FieldRef("payment", "payer_note", id), note)));
        owner().update("insert into payment (id, tenant_id, plot_id, kind, source, amount, received_on, "
                + "billing_month_id, group_id, receipt_number, bank_reference, payer_member_id, payer_note_enc) "
                + "values (?, ?, ?, 'PAYMENT', ?, ?, ?::date, ?, ?, ?, ?, ?, ?)", id, tenant, plot, source,
                new BigDecimal(amount), receivedOn, monthRow(bookedIn), UUID.randomUUID(), receipt, bankReference,
                memberId, sealed);
        return id;
    }

    /** The reversal of the whole payment in its own month, on the original's day. */
    private void reversePayment(UUID original) {
        owner().update("insert into payment (tenant_id, plot_id, kind, source, amount, received_on, billing_month_id, "
                + "group_id, reverses_payment_id, reason) select tenant_id, plot_id, 'PAYMENT_REVERSAL', source, "
                + "-amount, received_on, billing_month_id, group_id, id, 'Помилковий платіж' from payment where id = ?",
                original);
    }

    private UUID allocate(UUID payment, UUID charge, UUID plot, String amount) {
        UUID id = UUID.randomUUID();
        owner().update("insert into allocation (id, tenant_id, payment_id, charge_id, plot_id, amount, origin) "
                + "values (?, ?, ?, ?, ?, ?, 'ENTRY')", id, tenant, payment, charge, plot, new BigDecimal(amount));
        return id;
    }

    private void release(UUID allocation, UUID payment, UUID charge, UUID plot, String amount) {
        owner().update("insert into allocation (tenant_id, payment_id, charge_id, plot_id, amount, "
                + "releases_allocation_id, release_cause) values (?, ?, ?, ?, ?, ?, 'REALLOCATED')", tenant, payment,
                charge, plot, new BigDecimal(amount), allocation);
    }
}
