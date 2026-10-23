package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The plot status follows the ledger (feature 011 §2.10 and its acceptance rows): every booking or reversal publishes
 * {@link ChargesBooked}, the status refresher rebuilds the debt cache of the plots after the commit (asynchronously, so
 * the tests wait for it), and the plot card and list derive the colour from the cached debt, the red threshold of the
 * settings and today. A change of the threshold applies at once, without a refresh.
 */
class PlotStatusRefreshTests extends LedgerApiSupport {

    private static final String PLOT = "/api/v1/plots/%s";

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void restoreSettings() {
        ownerJdbc().update("delete from cooperative_settings where tenant_id = ?", DEMO_TENANT);
    }

    /** A plot of the demo cooperative owned by the demo member since 2020. */
    private UUID ownedPlot() {
        UUID plot = newPlot();
        own(demoMember("member@demo.local"), plot);
        return plot;
    }

    private String statusOf(String bearer, UUID plot) throws Exception {
        return body(send(bearer, get(PLOT.formatted(plot)), null).andExpect(status().isOk())).get("status").asString();
    }

    private void awaitStatus(UUID plot, String expected) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(statusOf(chairman(), plot)).isEqualTo(expected));
    }

    private UUID enterOpening(UUID plot, String type, String amount, LocalDate date) throws Exception {
        return UUID.fromString(body(send(chairman(), post(OPENING.formatted(plot)),
                opening(typeId(DEMO_TENANT, type), amount, date.toString(), null)).andExpect(status().isCreated()))
                .get("id").asString());
    }

    private void reverse(UUID charge) throws Exception {
        send(chairman(), post(REVERSE.formatted(charge)), Map.of("reason", "Помилкове нарахування"))
                .andExpect(status().isCreated());
    }

    private Map<String, Object> cache(UUID plot) {
        return ownerJdbc().queryForMap("select status, debt, oldest_debt_date from plot_status where plot_id = ?",
                plot);
    }

    @Test
    void aChargeTurnsAnOwnedPlotYellowAndItsReversalGreenAgain() throws Exception {
        UUID plot = ownedPlot();
        assertThat(statusOf(chairman(), plot)).isEqualTo("GREEN");

        // §2.10: plot 7 has no charges and an owner; it gets a charge of 25,00 ₴
        UUID charge = enterOpening(plot, "MEMBERSHIP", "25.00", kyivToday());

        awaitStatus(plot, "YELLOW");
        assertThat(cache(plot)).containsEntry("debt", new BigDecimal("25.00"))
                .containsEntry("status", "YELLOW");
        assertThat(cache(plot).get("oldest_debt_date").toString()).isEqualTo(kyivToday().toString());
        // the owner sees it on their own plot too
        assertThat(statusOf(member(), plot)).isEqualTo("YELLOW");

        reverse(charge);

        awaitStatus(plot, "GREEN");
        assertThat(cache(plot)).containsEntry("debt", new BigDecimal("0.00")).containsEntry("oldest_debt_date", null);
    }

    @Test
    void anOpeningBalanceAboveTheRedAmountIsRedAndItsReversalLeavesTheSmallerDebtYellow() throws Exception {
        UUID plot = ownedPlot();
        enterOpening(plot, "LAND_TAX", "25.00", kyivToday());
        awaitStatus(plot, "YELLOW");

        // §2.10: the red amount is 1 000,00 ₴; plot 12 gets an opening balance of 1 500,00 ₴
        UUID opening = enterOpening(plot, "MEMBERSHIP", "1500.00", kyivToday());

        awaitStatus(plot, "RED");
        assertThat(cache(plot)).containsEntry("debt", new BigDecimal("1525.00"));
        assertThat(body(send(chairman(), get("/api/v1/plots?q=" + plotNumber(plot)), null)
                .andExpect(status().isOk())).get("items").get(0).get("status").asString()).isEqualTo("RED");

        // red only because of the amount: reversed below the threshold, it turns yellow
        reverse(opening);

        awaitStatus(plot, "YELLOW");
        assertThat(cache(plot)).containsEntry("debt", new BigDecimal("25.00"));
    }

    @Test
    void aDebtOlderThanTheRedDaysIsRed() throws Exception {
        UUID plot = ownedPlot();

        enterOpening(plot, "MEMBERSHIP", "25.00", kyivToday().minusDays(91));

        awaitStatus(plot, "RED");
        assertThat(cache(plot).get("oldest_debt_date").toString()).isEqualTo(kyivToday().minusDays(91).toString());
    }

    @Test
    void aChangeOfTheThresholdAppliesAtOnce() throws Exception {
        UUID plot = ownedPlot();
        enterOpening(plot, "MEMBERSHIP", "1500.00", kyivToday());
        awaitStatus(plot, "RED");

        // §2.10: the chairman changes the red amount to 2 000,00 ₴
        send(chairman(), put("/api/v1/settings"), Map.of("redAmount", "2000.00")).andExpect(status().isOk());

        assertThat(statusOf(chairman(), plot)).isEqualTo("YELLOW");
        assertThat(body(send(chairman(), get("/api/v1/plots?status=YELLOW&size=100"), null)
                .andExpect(status().isOk())).get("items").findValuesAsString("id")).contains(plot.toString());

        send(chairman(), put("/api/v1/settings"), Map.of("redDays", 0)).andExpect(status().isOk());
        // today's debt is not older than 0 days; a debt of yesterday would be
        assertThat(statusOf(chairman(), plot)).isEqualTo("YELLOW");
    }

    @Test
    void aPlotWithoutAnOwnerStaysGreyWithADebt() throws Exception {
        UUID plot = newPlot();

        enterOpening(plot, "MEMBERSHIP", "1500.00", kyivToday());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(cache(plot))
                .containsEntry("debt", new BigDecimal("1500.00")));
        assertThat(statusOf(chairman(), plot)).isEqualTo("GREY");
        assertThat(cache(plot)).containsEntry("status", "GREY");
    }

    @Test
    void aRedeliveredEventChangesNothing() throws Exception {
        UUID plot = ownedPlot();
        enterOpening(plot, "MEMBERSHIP", "25.00", kyivToday());
        awaitStatus(plot, "YELLOW");
        Map<String, Object> before = cache(plot);

        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> events.publishEvent(new ChargesBooked(DEMO_TENANT, Set.of(plot))));

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(cache(plot)).isEqualTo(before));
    }

    @Test
    void aMissingCacheRowIsCreatedByTheRefresh() throws Exception {
        UUID plot = ownedPlot();
        ownerJdbc().update("delete from plot_status where plot_id = ?", plot);

        enterOpening(plot, "MEMBERSHIP", "25.00", kyivToday());

        awaitStatus(plot, "YELLOW");
        assertThat(cache(plot)).containsEntry("debt", new BigDecimal("25.00"));
    }
}
