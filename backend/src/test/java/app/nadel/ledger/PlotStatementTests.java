package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.money.Money;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The statement of a plot for a period ({@link LedgerReadModel#plotStatement}, {@link LedgerReadModel#firstDebtDate};
 * feature 018 §2.10, worked example 11) on a ledger of a cooperative of its own, months counted back from today
 * ({@code M-2}, {@code M-1}; debt date or day received in brackets):
 * <pre>
 * plot 9   opening balance membership 100.00 (M-2-01)
 *          C1 membership 300.00 (M-2-01)
 *          P1 cash 200.00 (M-2-15)                      -> as of the end of M-2: debt 200.00, no prepayment
 *          C2 membership 300.00 (M-1-01), adjusted -50.00 (booked later, dated M-1-01)
 *          C3 electricity 52.50 (M-1-05)
 *          C4 water 40.00 (M-1-10) and its reversal -40.00 (dated M-1-10)
 *          split cash 250.00 (M-1-20): 150.00 to plot 9, 100.00 to plot 10
 *          P3 bank 80.00 (M-1-25), reversed today: the reversal is dated M-1-25, the day of its payment
 *          P4 cash 600.00 (today)                        -> today: no debt, prepayment 247.50
 * plot 10  membership 100.00 (M-1-01), paid by the split
 * </pre>
 * From M-1-01 to today: charged 300 - 50 + 52.50 + 40 - 40 = 302.50, paid 150 + 80 - 80 + 600 = 750.00, so the net
 * balance goes from 200.00 to 200.00 + 302.50 - 750.00 = -247.50 (an overpayment). Charges and payments are entered as
 * the debt dating of {@code debtsAsOf} counts them, so both ends of any period equal {@code debtsAsOf} and the plot
 * balances report, and the start plus the lines is the end.
 */
class PlotStatementTests extends FreshPaymentSupport {

    private static final String REPORT = "/api/v1/reports/plot-balances";

    @Autowired
    LedgerReadModel readModel;

    @Autowired
    TransactionTemplate tx;

    private YearMonth twoAgo;
    private YearMonth oneAgo;
    private UUID nine;
    private UUID ten;
    private UUID p3;

    @BeforeEach
    void bookTheLedger() throws Exception {
        twoAgo = current().minusMonths(2);
        oneAgo = current().minusMonths(1);
        nine = plot("9", "600");
        ten = plot("10", "600");
        owner().update("insert into ownership (id, tenant_id, plot_id, member_id, share_numerator, share_denominator, "
                + "valid_from) values (?, ?, ?, ?, 1, 1, date '2020-01-01'), (?, ?, ?, ?, 1, 1, date '2020-01-01')",
                UUID.randomUUID(), tenant, nine, memberId, UUID.randomUUID(), tenant, ten, memberId);
        UUID membership = typeId("MEMBERSHIP");

        charge(nine, membership, "OPENING", "OPENING", "100.00", day(twoAgo, 1), twoAgo);
        charge(nine, membership, "REGULAR", "TARIFF", "300.00", day(twoAgo, 1), twoAgo);
        UUID c2 = charge(nine, membership, "REGULAR", "TARIFF", "300.00", day(oneAgo, 1), oneAgo);
        adjustment(c2, "-50.00", current());
        charge(nine, typeId("ELECTRICITY"), "REGULAR", "CONSUMPTION", "52.50", day(oneAgo, 5), oneAgo);
        reversal(charge(nine, typeId("WATER"), "REGULAR", "TARIFF", "40.00", day(oneAgo, 10), oneAgo));
        charge(ten, membership, "REGULAR", "TARIFF", "100.00", day(oneAgo, 1), oneAgo);

        pay(nine, "CASH", "200.00", day(twoAgo, 15), null);
        Map<String, Object> split = new LinkedHashMap<>();
        split.put("source", "CASH");
        split.put("amount", "250.00");
        split.put("receivedOn", day(oneAgo, 20).toString());
        split.put("split", Map.of("payerMemberId", memberId.toString(), "parts", List.of(
                Map.of("plotId", nine.toString(), "amount", "150.00"),
                Map.of("plotId", ten.toString(), "amount", "100.00"))));
        send(treasurer, post(PAYMENTS + "?preview=false"), split).andExpect(status().isCreated());
        p3 = pay(nine, "BANK", "80.00", day(oneAgo, 25), "ПД-77");
        send(chairman, post(PAYMENTS + "/" + p3 + "/reverse"), Map.of("reason", "Помилковий платіж"))
                .andExpect(status().isCreated());
        pay(nine, "CASH", "600.00", today(), null);
        awaitListeners();
    }

    @Test
    void theStartPlusTheLinesIsTheEndAndBothEndsAreDebtsAsOfForEveryPeriod() {
        LocalDate today = today();
        List<LocalDate[]> periods = List.of(
                new LocalDate[] {day(oneAgo, 1), today},
                new LocalDate[] {day(oneAgo, 1), oneAgo.atEndOfMonth()},
                new LocalDate[] {day(twoAgo, 1), day(oneAgo, 15)},
                new LocalDate[] {day(twoAgo, 16), day(oneAgo, 24)},
                new LocalDate[] {day(oneAgo, 25), day(oneAgo, 25)},
                new LocalDate[] {day(twoAgo, 1), today});
        for (LocalDate[] period : periods) {
            PlotStatement statement = statement(nine, period[0], period[1]);
            String label = period[0] + ".." + period[1];
            assertThat(statement.opening()).as("opening of %s", label)
                    .isEqualTo(debts(period[0].minusDays(1)));
            assertThat(statement.closing()).as("closing of %s", label).isEqualTo(debts(period[1]));
            assertThat(statement.openingNet().plus(statement.charged()).minus(statement.paid()))
                    .as("start + charged - paid of %s", label).isEqualTo(statement.closingNet());
        }

        PlotStatement act = statement(nine, day(oneAgo, 1), today);
        assertThat(act.opening().total()).isEqualTo(Money.parse("200.00"));
        assertThat(act.opening().prepayment()).isEqualTo(Money.ZERO);
        assertThat(act.charged()).isEqualTo(Money.parse("302.50"));
        assertThat(act.paid()).isEqualTo(Money.parse("750.00"));
        assertThat(act.closingNet()).isEqualTo(Money.parse("-247.50"));
        assertThat(act.closing().total()).isEqualTo(Money.ZERO);
        assertThat(act.closing().prepayment()).isEqualTo(Money.parse("247.50"));
    }

    @Test
    void linesComeByDateWithTheDebtDatingChargesBeforePaymentsAndOnlyThePlotsPartOfASplit() {
        PlotStatement act = statement(nine, day(oneAgo, 1), today());

        List<String> lines = new ArrayList<>();
        for (PlotStatement.Line line : act.lines()) {
            lines.add(line.date() + " " + line.kind() + " " + line.charged() + " " + line.paid()
                    + (line.paymentSource() == null ? "" : " " + line.paymentSource())
                    + (line.receiptNumber() == null ? "" : " #")
                    + (line.bankReference() == null ? "" : " " + line.bankReference()));
        }
        assertThat(lines).containsExactly(
                day(oneAgo, 1) + " CHARGE 300.00 0.00",
                day(oneAgo, 1) + " ADJUSTMENT -50.00 0.00",
                day(oneAgo, 5) + " CHARGE 52.50 0.00",
                day(oneAgo, 10) + " CHARGE 40.00 0.00",
                day(oneAgo, 10) + " CHARGE_REVERSAL -40.00 0.00",
                day(oneAgo, 20) + " PAYMENT 0.00 150.00 CASH #",
                day(oneAgo, 25) + " PAYMENT 0.00 80.00 BANK ПД-77",
                // reversed today, dated on the day of its payment, with the payment's bank reference
                day(oneAgo, 25) + " PAYMENT_REVERSAL 0.00 -80.00 BANK ПД-77",
                today() + " PAYMENT 0.00 600.00 CASH #");
        assertThat(act.lines().get(0).chargeTypeId()).isEqualTo(typeId("MEMBERSHIP"));
        assertThat(act.lines().get(0).serviceFrom()).isEqualTo(day(oneAgo, 1));
        assertThat(act.lines().get(6).rowId()).isEqualTo(p3);
        assertThat(act.lines().get(5).receiptNumber()).isEqualTo(act.lines().get(8).receiptNumber() - 1);
    }

    @Test
    void bothEndsEqualThePlotBalancesReportOnThoseDays() throws Exception {
        for (LocalDate[] period : List.of(new LocalDate[] {day(oneAgo, 1), today()},
                new LocalDate[] {day(twoAgo, 16), oneAgo.atEndOfMonth()})) {
            PlotStatement act = statement(nine, period[0], period[1]);
            assertThat(report(period[0].minusDays(1))).as("report on %s", period[0].minusDays(1))
                    .isEqualTo(act.opening().total() + " " + act.opening().prepayment());
            assertThat(report(period[1])).as("report on %s", period[1])
                    .isEqualTo(act.closing().total() + " " + act.closing().prepayment());
        }
    }

    @Test
    void theFirstDebtDateIsTheEarliestChargeOrPaymentOfThePlot() throws Exception {
        UUID onlyPaid = plot("11", "600");
        UUID empty = plot("12", "600");
        pay(onlyPaid, "CASH", "10.00", day(oneAgo, 3), null);

        assertThat(firstDebtDate(nine)).contains(day(twoAgo, 1));
        assertThat(firstDebtDate(ten)).contains(day(oneAgo, 1));
        assertThat(firstDebtDate(onlyPaid)).contains(day(oneAgo, 3));
        assertThat(firstDebtDate(empty)).isEmpty();
        assertThat(firstDebtDate(UUID.randomUUID())).isEmpty();
        // another cooperative sees nothing of this one
        Optional<LocalDate> fromThere = TenantContext.runAs(OTHER_TENANT,
                () -> tx.execute(status -> readModel.firstDebtDate(nine)));
        assertThat(fromThere).isEmpty();
    }

    @Test
    void aPeriodBackwardsIsRefusedAndTheReadRunsOnlyInTheCallersTransaction() {
        assertThatThrownBy(() -> statement(nine, today(), today().minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> readModel.plotStatement(nine, today(), today())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---- fixture

    private PlotStatement statement(UUID plot, LocalDate from, LocalDate to) {
        return TenantContext.runAs(tenant, () -> tx.execute(status -> readModel.plotStatement(plot, from, to)));
    }

    private PlotDebts debts(LocalDate asOf) {
        return TenantContext.runAs(tenant, () -> tx.execute(status -> readModel.debtsAsOf(asOf, Set.of(),
                List.of(nine)).get(nine)));
    }

    private Optional<LocalDate> firstDebtDate(UUID plot) {
        return TenantContext.runAs(tenant, () -> tx.execute(status -> readModel.firstDebtDate(plot)));
    }

    /** The debt and the prepayment of plot 9 in the plot balances report on the day. */
    private String report(LocalDate asOf) throws Exception {
        JsonNode sheet = body(send(chairman, get(REPORT).param("asOf", asOf.toString()), null)
                .andExpect(status().isOk())).get("sheets").get(0);
        List<String> keys = new ArrayList<>();
        sheet.get("columns").forEach(column -> keys.add(column.get("key").asString()));
        for (JsonNode row : sheet.get("rows")) {
            JsonNode cells = row.get("cells");
            if ("9".equals(cells.get(keys.indexOf("number")).get("value").asString())) {
                return cells.get(keys.indexOf("total")).get("value").asString() + " "
                        + cells.get(keys.indexOf("prepayment")).get("value").asString();
            }
        }
        throw new AssertionError("Plot 9 is not in the report on " + asOf);
    }

    private static LocalDate day(YearMonth month, int day) {
        return month.atDay(day);
    }

    /** A payment of the plot entered by the treasurer on the day received, saving the proposal; returns its id. */
    private UUID pay(UUID plot, String source, String amount, LocalDate receivedOn, String bankReference)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plotId", plot.toString());
        body.put("source", source);
        body.put("amount", amount);
        body.put("receivedOn", receivedOn.toString());
        if (bankReference != null) {
            body.put("bankReference", bankReference);
        }
        return UUID.fromString(body(send(treasurer, post(PAYMENTS + "?preview=false"), body)
                .andExpect(status().isCreated())).get("payments").get(0).get("id").asString());
    }

    private UUID monthRow(YearMonth month) {
        owner().update("insert into billing_month (tenant_id, month) values (?, ?) "
                + "on conflict (tenant_id, month) do nothing", tenant, month.atDay(1));
        return owner().queryForObject("select id from billing_month where tenant_id = ? and month = ?", UUID.class,
                tenant, month.atDay(1));
    }

    /** A charge of the kind written as the database owner, with its debt date as its one service day. */
    private UUID charge(UUID plot, UUID type, String kind, String source, String amount, LocalDate accruedOn,
            YearMonth bookedIn) {
        UUID id = UUID.randomUUID();
        owner().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "?)", id, tenant, plot, type, monthRow(bookedIn), kind, source, new BigDecimal(amount), accruedOn,
                accruedOn, accruedOn, "TEST:" + id);
        return id;
    }

    /** A reversal by hand in the original's month: the opposite amount, the same service period and debt date. */
    private void reversal(UUID original) {
        owner().update("insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, reverses_charge_id, reason) select tenant_id, "
                + "plot_id, charge_type_id, billing_month_id, 'REVERSAL', 'MANUAL_REVERSAL', -amount, service_from, "
                + "service_to, accrued_on, id, 'Помилкове нарахування' from charge where id = ?", original);
    }

    /** An adjustment of the charge booked in {@code bookedIn}, with the original's service period and debt date. */
    private void adjustment(UUID original, String amount, YearMonth bookedIn) {
        UUID recalculation = UUID.randomUUID();
        owner().update("insert into recalculation (id, tenant_id, cause, reason, total, line_count, plot_count, "
                + "digest) values (?, ?, 'MANUAL', 'Виправлено ставку', ?, 1, 1, 'test-digest')", recalculation,
                tenant, new BigDecimal(amount));
        owner().update("insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key, adjusts_charge_id, recalculation_id, "
                + "reason) select tenant_id, plot_id, charge_type_id, ?, 'ADJUSTMENT', 'RECALCULATION', ?, "
                + "service_from, service_to, accrued_on, ?, id, ?, 'Виправлено ставку' from charge where id = ?",
                monthRow(bookedIn), new BigDecimal(amount), "ADJUSTMENT:" + recalculation + ":" + original,
                recalculation, original);
    }
}
