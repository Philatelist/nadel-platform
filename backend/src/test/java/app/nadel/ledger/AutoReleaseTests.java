package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Money paid on a charge is taken back automatically when the charge is reversed or lowered (feature 015 §2.3, FS 2.8,
 * worked examples 6, 7 and 8): every path that reverses or lowers a charge (a reversal by hand, the reversal of a
 * losses run or a common consumer run, a recalculation that reverses or adjusts) takes the plot ledger locks and writes
 * the releases in its own transaction, so it commits (before slice 5 the deferred backstop {@code allocation_charge_cap}
 * refused such a commit). A reversed charge releases everything on it ({@code CHARGE_REVERSED}), a lowered one the
 * excess from its latest allocation ({@code CHARGE_ADJUSTED}); the releases name the actor. The released money becomes
 * prepayment, and with automatic allocation on it moves on to the plot's other open charges (origin
 * {@code PREPAYMENT_AUTO}, by the system). Each test runs in a cooperative of its own; months are relative to the
 * current month in Europe/Kyiv.
 */
class AutoReleaseTests extends FreshPaymentSupport {

    private static final String LOSSES_RUNS = "/api/v1/losses-runs";
    private static final String CONSUMER_RUNS = "/api/v1/common-consumer-runs";

    // ---- a reversal by hand

    @Test
    void example6TheReversedMembershipReleases600And150MovesToTheOpenElectricity() throws Exception {
        // example 1: membership 600.00 (January), electricity 250.00 (February), a payment of 700.00
        UUID plot = plot("7", "600");
        UUID membership = charge(plot, "MEMBERSHIP", "600.00", "2026-01");
        UUID electricity = charge(plot, "ELECTRICITY", "250.00", "2026-02");
        UUID payment = pay(plot, "700.00");
        assertThat(netAllocated(membership)).isEqualByComparingTo("600.00");
        assertThat(netAllocated(electricity)).isEqualByComparingTo("100.00");
        UUID entry = allocations(plot, "ENTRY").stream().filter(row -> membership.equals(row.get("charge_id")))
                .map(row -> (UUID) row.get("id")).findFirst().orElseThrow();

        reverseCharge(membership);

        // release 600.00 in the reversal's transaction, by the chairman
        List<Map<String, Object>> releases = releases(plot);
        assertThat(releases).hasSize(1);
        assertThat((BigDecimal) releases.get(0).get("amount")).isEqualByComparingTo("-600.00");
        assertThat(releases.get(0)).containsEntry("release_cause", "CHARGE_REVERSED")
                .containsEntry("created_by", memberOf("CHAIRMAN")).containsEntry("releases_allocation_id", entry)
                .containsEntry("charge_id", membership).containsEntry("payment_id", payment);
        assertThat(netAllocated(membership)).isEqualByComparingTo("0.00");

        // automatic allocation (on by default) moves 150.00 to the open electricity; 450.00 stays prepayment
        awaitListeners();
        List<Map<String, Object>> auto = allocations(plot, "PREPAYMENT_AUTO");
        assertThat(auto).hasSize(1);
        assertThat((BigDecimal) auto.get(0).get("amount")).isEqualByComparingTo("150.00");
        assertThat(auto.get(0)).containsEntry("charge_id", electricity).containsEntry("payment_id", payment);
        assertThat(auto.get(0).get("created_by")).isNull();
        assertThat(open(electricity)).isEqualByComparingTo("0.00");
        assertThat(prepayment(plot)).isEqualByComparingTo("450.00");
        assertThat(owner().queryForObject("select prepayment from plot_status where plot_id = ?", BigDecimal.class,
                plot)).isEqualByComparingTo("450.00");
        invariantsHold(plot);

        // the card: the payment's money is on the electricity now (100.00 at entry + 150.00 from the prepayment), the
        // reversed membership is gone from it; the history keeps every row with its author (none for the system)
        JsonNode card = body(send(treasurer, get(PAYMENTS + "/" + payment), null).andExpect(status().isOk()));
        assertThat(card.get("currentAllocations")).singleElement().satisfies(net -> {
            assertThat(net.get("chargeId").asString()).isEqualTo(electricity.toString());
            assertThat(net.get("amount").asString()).isEqualTo("250.00");
            assertThat(net.get("chargeType").get("code").asString()).isEqualTo("ELECTRICITY");
        });
        List<String> history = new ArrayList<>();
        card.get("allocations").forEach(row -> history.add((row.has("origin") ? row.get("origin").asString()
                : row.get("releaseCause").asString()) + " " + row.get("amount").asString() + " "
                + (row.has("createdByName") ? "member" : "system")));
        assertThat(history).hasSize(4);
        assertThat(history.subList(0, 2)).containsExactlyInAnyOrder("ENTRY 600.00 member", "ENTRY 100.00 member");
        assertThat(history.subList(2, 4)).containsExactly("CHARGE_REVERSED -600.00 member",
                "PREPAYMENT_AUTO 150.00 system");
    }

    @Test
    void withAutomaticAllocationOffTheReleasedMoneyStaysPrepayment() throws Exception {
        autoAllocation(false);
        UUID plot = plot("7", "600");
        UUID membership = charge(plot, "MEMBERSHIP", "600.00", "2026-01");
        UUID electricity = charge(plot, "ELECTRICITY", "250.00", "2026-02");
        pay(plot, "700.00");

        reverseCharge(membership);
        awaitListeners();

        assertThat(releases(plot)).hasSize(1);
        assertThat(allocations(plot, "PREPAYMENT_AUTO")).isEmpty();
        assertThat(open(electricity)).isEqualByComparingTo("150.00");
        assertThat(prepayment(plot)).isEqualByComparingTo("600.00");
        invariantsHold(plot);
    }

    @Test
    void anUnpaidChargeIsReversedWithoutReleases() throws Exception {
        UUID plot = plot("7", "600");
        UUID membership = charge(plot, "MEMBERSHIP", "600.00", "2026-01");

        reverseCharge(membership);

        assertThat(releases(plot)).isEmpty();
        assertThat(allocationCount(plot)).isZero();
    }

    // ---- the reversal of runs

    @Test
    void theReversalOfALossesRunReleasesWhatWasPaidOnItsCharges() throws Exception {
        runFixtures();
        // quarter A 1 100 kWh with P1 600 and P2 400: losses P1 259.20, P2 172.80
        UUID quarter = meter("QUARTER", "Квартал А", null, null, 5000, 6100);
        meter("PLOT", null, p1, quarter, 1000, 1600);
        meter("PLOT", null, p2, quarter, 2000, 2400);
        UUID run = bookRun(LOSSES_RUNS);
        UUID p1Charge = runCharge(run, p1);
        UUID payment = pay(p1, "259.20");
        assertThat(netAllocated(p1Charge)).isEqualByComparingTo("259.20");

        send(chairman, post(LOSSES_RUNS + "/" + run + "/reverse"), Map.of("reason", "Помилкові покази"))
                .andExpect(status().isOk());

        List<Map<String, Object>> releases = releases(p1);
        assertThat(releases).hasSize(1);
        assertThat((BigDecimal) releases.get(0).get("amount")).isEqualByComparingTo("-259.20");
        assertThat(releases.get(0)).containsEntry("release_cause", "CHARGE_REVERSED")
                .containsEntry("charge_id", p1Charge).containsEntry("payment_id", payment)
                .containsEntry("created_by", memberOf("CHAIRMAN"));
        assertThat(releases(p2)).isEmpty();
        awaitListeners();
        // nothing else is open on P1: the money stays as prepayment
        assertThat(prepayment(p1)).isEqualByComparingTo("259.20");
        invariantsHold(p1);
    }

    @Test
    void theReversalOfACommonConsumerRunReleasesWhatWasPaidOnItsCharges() throws Exception {
        runFixtures();
        // street lighting 50 kWh equally per plot among three plots: 72.00 each
        UUID lighting = meter("COMMON_CONSUMER", "Освітлення", null, null, 1000, 1050);
        Map<String, Object> billing = new LinkedHashMap<>();
        billing.put("split", "EQUAL_PER_PLOT");
        billing.put("servicePlotIds", List.of());
        send(chairman, put("/api/v1/common-consumers/" + lighting + "/billing"), billing)
                .andExpect(status().isOk());
        UUID run = bookRun(CONSUMER_RUNS);
        UUID p3Charge = runCharge(run, p3);
        UUID membership = charge(p3, "MEMBERSHIP", "50.00", "2026-01");
        pay(p3, "122.00");
        assertThat(netAllocated(p3Charge)).isEqualByComparingTo("72.00");
        assertThat(netAllocated(membership)).isEqualByComparingTo("50.00");

        send(chairman, post(CONSUMER_RUNS + "/" + run + "/reverse"), Map.of("reason", "Помилка"))
                .andExpect(status().isOk());

        List<Map<String, Object>> releases = releases(p3);
        assertThat(releases).hasSize(1);
        assertThat((BigDecimal) releases.get(0).get("amount")).isEqualByComparingTo("-72.00");
        assertThat(releases.get(0)).containsEntry("release_cause", "CHARGE_REVERSED")
                .containsEntry("charge_id", p3Charge);
        awaitListeners();
        assertThat(netAllocated(membership)).isEqualByComparingTo("50.00");
        assertThat(prepayment(p3)).isEqualByComparingTo("72.00");
        invariantsHold(p3);
    }

    // ---- recalculations

    @Test
    void aRecalculationThatReversesAndChargesAgainReleasesAndThePrepaymentPaysTheNewCharge() throws Exception {
        String october = ym(0);
        UUID plot = membershipOf600(october);
        UUID original = bookedCharge(plot, "MEMBERSHIP", october);
        UUID payment = pay(plot, "600.00");
        owner().update("update plot set area_m2 = 700 where id = ?", plot);

        recalculate(plot, "MEMBERSHIP", october, october);

        // the open month: the charge reversed (its 600.00 released) and charged again at 700.00
        List<Map<String, Object>> releases = releases(plot);
        assertThat(releases).hasSize(1);
        assertThat((BigDecimal) releases.get(0).get("amount")).isEqualByComparingTo("-600.00");
        assertThat(releases.get(0)).containsEntry("release_cause", "CHARGE_REVERSED")
                .containsEntry("charge_id", original).containsEntry("created_by", memberOf("CHAIRMAN"));
        UUID recharged = bookedCharge(plot, "MEMBERSHIP", october);
        assertThat(recharged).isNotEqualTo(original);
        awaitListeners();
        assertThat(allocations(plot, "PREPAYMENT_AUTO")).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("charge_id", recharged)
                        .containsEntry("payment_id", payment));
        assertThat(netAllocated(recharged)).isEqualByComparingTo("600.00");
        assertThat(open(recharged)).isEqualByComparingTo("100.00");
        assertThat(prepayment(plot)).isEqualByComparingTo("0.00");
        invariantsHold(plot);
    }

    @Test
    void example7ANegativeAdjustmentReleases100FromTheLatestAllocation() throws Exception {
        String september = ym(1);
        UUID plot = membershipOf600(september);
        closeMonth(september);
        UUID membership = bookedCharge(plot, "MEMBERSHIP", september);
        pay(plot, "400.00");
        UUID latest = pay(plot, "200.00");
        UUID latestAllocation = (UUID) allocations(plot, "ENTRY").getLast().get("id");
        assertThat(netAllocated(membership)).isEqualByComparingTo("600.00");
        owner().update("update plot set area_m2 = 500 where id = ?", plot);

        recalculate(plot, "MEMBERSHIP", september, september);

        // the closed month: an adjustment of -100.00; 100.00 taken back from the latest allocation (the 200.00)
        assertThat(owner().queryForObject("select amount from charge where adjusts_charge_id = ?", BigDecimal.class,
                membership)).isEqualByComparingTo("-100.00");
        List<Map<String, Object>> releases = releases(plot);
        assertThat(releases).hasSize(1);
        assertThat((BigDecimal) releases.get(0).get("amount")).isEqualByComparingTo("-100.00");
        assertThat(releases.get(0)).containsEntry("release_cause", "CHARGE_ADJUSTED")
                .containsEntry("releases_allocation_id", latestAllocation).containsEntry("payment_id", latest)
                .containsEntry("created_by", memberOf("CHAIRMAN"));
        awaitListeners();
        assertThat(netAllocated(membership)).isEqualByComparingTo("500.00");
        assertThat(open(membership)).isEqualByComparingTo("0.00");
        assertThat(prepayment(plot)).isEqualByComparingTo("100.00");
        assertThat(allocations(plot, "PREPAYMENT_AUTO")).isEmpty();
        invariantsHold(plot);
    }

    @Test
    void example8APositiveAdjustmentLeaves50OpenAndThePlotIsNoLongerGreen() throws Exception {
        String september = ym(1);
        UUID plot = membershipOf600(september);
        closeMonth(september);
        UUID membership = bookedCharge(plot, "MEMBERSHIP", september);
        // a plot without an owner is grey whatever its debt: the member owns it
        owner().update("insert into ownership (tenant_id, plot_id, member_id, share_numerator, share_denominator, "
                + "valid_from) values (?, ?, ?, 1, 1, date '2020-01-01')", tenant, plot, memberId);
        pay(plot, "600.00");
        awaitListeners();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(statusOf(plot)).isEqualTo("GREEN"));
        owner().update("update plot set area_m2 = 650 where id = ?", plot);

        recalculate(plot, "MEMBERSHIP", september, september);

        assertThat(releases(plot)).isEmpty();
        awaitListeners();
        assertThat(open(membership)).isEqualByComparingTo("50.00");
        assertThat(owner().queryForObject("select debt from plot_status where plot_id = ?", BigDecimal.class, plot))
                .isEqualByComparingTo("50.00");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(statusOf(plot)).isNotEqualTo("GREEN"));
        invariantsHold(plot);
    }

    // ---- fixtures

    private UUID p1;
    private UUID p2;
    private UUID p3;
    private String installed;
    private String before;
    private String to;

    /**
     * A plot of 600 m2 with membership 1 200.00 per sotka per year, 600.00 a month, booked by the month run of the
     * month (YYYY-MM).
     */
    private UUID membershipOf600(String month) throws Exception {
        UUID plot = plot("7", "600");
        tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "1200.0000", "2020-01-01");
        send(chairman, post(RUN.formatted(month, false)), null).andExpect(status().isOk());
        assertThat(open(bookedCharge(plot, "MEMBERSHIP", month))).isEqualByComparingTo("600.00");
        return plot;
    }

    private String statusOf(UUID plot) throws Exception {
        return body(send(chairman, get("/api/v1/plots/" + plot), null).andExpect(status().isOk())).get("status")
                .asString();
    }

    /** Proportional losses, electricity and common consumer tariffs, three plots; runs over the previous month. */
    private void runFixtures() {
        YearMonth september = current().minusMonths(1);
        installed = september.minusMonths(6).atDay(1).toString();
        before = september.atDay(1).minusDays(1).toString();
        to = september.atEndOfMonth().toString();
        owner().update("insert into cooperative_settings (tenant_id, losses_mode) values (?, 'PROPORTIONAL')", tenant);
        tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", installed);
        tariff("COMMON_CONSUMERS", "Спільні споживачі", "PER_KWH", "MONTHLY", "4.3200", installed);
        p1 = plot("1", "600");
        p2 = plot("2", "600");
        p3 = plot("3", "600");
    }

    private UUID meter(String kind, String name, UUID plot, UUID parent, int beforeValue, int toValue) {
        UUID id = UUID.randomUUID();
        owner().update("insert into meter (id, tenant_id, kind, resource, serial, name, plot_id, installed_on, "
                + "initial_value) values (?, ?, ?, 'ELECTRICITY', ?, ?, ?, ?::date, 0)", id, tenant, kind,
                "AR-" + id.toString().substring(0, 8), name, plot, installed);
        if (parent != null) {
            owner().update("insert into meter_parent (tenant_id, meter_id, parent_meter_id, valid_from) "
                    + "values (?, ?, ?, ?::date)", tenant, id, parent, installed);
        }
        reading(id, before, beforeValue);
        reading(id, to, toValue);
        return id;
    }

    private void reading(UUID meter, String on, int value) {
        owner().update("insert into meter_reading (tenant_id, meter_id, taken_on, value, source, status) "
                + "values (?, ?, ?::date, ?, 'ENTERED', 'APPROVED')", tenant, meter, on, new BigDecimal(value));
    }

    private UUID bookRun(String path) throws Exception {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("from", current().minusMonths(1).atDay(1).toString());
        range.put("to", to);
        return UUID.fromString(body(send(chairman, post(path + "?preview=false"), range)
                .andExpect(status().isOk())).get("runId").asString());
    }

    private UUID runCharge(UUID run, UUID plot) {
        return owner().queryForObject("select id from charge where run_id = ? and plot_id = ? and kind = 'REGULAR'",
                UUID.class, run, plot);
    }
}
