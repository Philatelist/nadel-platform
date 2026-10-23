package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Proportional losses runs over the API (feature 011 §2.5): the preview of a quarter and a substation with their plots
 * (§3 rows), the booking with its charges, parts, value uses, queue items and audit, the overlap refusal, the reversal
 * of a whole run with the run's reason, a skipped node charged later by "Повторити", calculated values, the losses
 * mode, validation, the readings a booked run uses, and the permissions. Each test has a cooperative of its own; the
 * meters, the tree and the readings are written as the database owner (no reading events, so no reading charges).
 */
class LossesRunsTests extends FreshCooperativeSupport {

    private static final String RUNS = "/api/v1/losses-runs";
    private static final String PREVIEW = RUNS + "?preview=true";
    private static final String BOOK = RUNS + "?preview=false";
    private static final String FROM = "2026-09-01";
    private static final String TO = "2026-09-30";
    private static final String BEFORE = "2026-08-31";
    private static final String INSTALLED = "2026-01-01";

    private UUID p1;
    private UUID p2;
    private UUID p3;
    private UUID substation;
    private UUID quarterA;
    private UUID quarterB;
    private UUID well;
    private UUID meterP1;
    private UUID meterP2;
    private UUID meterP3;

    @BeforeEach
    void proportionalLossesAndAnElectricityTariff() {
        owner().update("insert into cooperative_settings (tenant_id, losses_mode) values (?, 'PROPORTIONAL')", tenant);
        tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", INSTALLED);
        p1 = plot("1", "600");
        p2 = plot("2", "600");
        p3 = plot("3", "600");
    }

    /** Quarter A 1 100 kWh with P1 600 and P2 400 (lighting 0), read on 31.08 and 30.09. */
    private void quarter() {
        quarterA = namedMeter("QUARTER", "Квартал А", null, 5000, 6100);
        meterP1 = plotMeter(p1, quarterA, 1000, 1600);
        meterP2 = plotMeter(p2, quarterA, 2000, 2400);
        namedMeter("COMMON_CONSUMER", "Освітлення", quarterA, 300, 300);
    }

    /**
     * Substation 2 300 with quarter A (as above), quarter B (P3 1 000) and the well 50; B is read after the range end
     * only when {@code bRead}.
     */
    private void substation(boolean bRead) {
        substation = namedMeter("SUBSTATION", "ТП-1", null, 10000, 12300);
        quarterA = namedMeter("QUARTER", "Квартал А", substation, 5000, 6100);
        quarterB = bRead ? namedMeter("QUARTER", "Квартал Б", substation, 7000, 8050)
                : namedMeter("QUARTER", "Квартал Б", substation, 7000, null);
        well = namedMeter("COMMON_CONSUMER", "Свердловина №1", substation, 300, 350);
        meterP1 = plotMeter(p1, quarterA, 1000, 1600);
        meterP2 = plotMeter(p2, quarterA, 2000, 2400);
        meterP3 = plotMeter(p3, quarterB, 3000, 4000);
    }

    // ---- preview and booking

    @Test
    void thePreviewShowsTheQuarterWithItsPlotsAndAmountsAndWritesNothing() throws Exception {
        quarter();

        JsonNode preview = body(send(chairman, post(PREVIEW), range()).andExpect(status().isOk()));

        assertThat(preview.get("preview").asBoolean()).isTrue();
        assertThat(preview.get("billingMonth").asString()).isEqualTo("2026-09");
        JsonNode node = preview.get("nodes").get(0);
        assertThat(preview.get("nodes")).hasSize(1);
        assertThat(node.get("meterId").asString()).isEqualTo(quarterA.toString());
        assertThat(node.get("kind").asString()).isEqualTo("QUARTER");
        assertThat(node.get("name").asString()).isEqualTo("Квартал А");
        assertThat(node.get("consumption").asString()).isEqualTo("1100.000");
        assertThat(node.get("children").asString()).isEqualTo("1000.000");
        assertThat(node.get("losses").asString()).isEqualTo("100.000");
        assertThat(node.get("pct").asString()).isEqualTo("9.09");
        assertThat(node.get("calculated").asBoolean()).isFalse();
        assertThat(node.get("outcome").asString()).isEqualTo("CHARGED");
        assertThat(node.has("warning")).isFalse();
        assertThat(lines(node.get("plots"), "plotNumber", "kwh", "amount"))
                .containsExactly("1|60.000|259.20", "2|40.000|172.80");
        assertThat(node.get("amount").asString()).isEqualTo("432.00");
        assertThat(lines(preview.get("charges"), "plotNumber", "kwh", "amount"))
                .containsExactly("1|60.000|259.20", "2|40.000|172.80");
        assertThat(preview.get("total").asString()).isEqualTo("432.00");
        assertThat(preview.get("plots").asInt()).isEqualTo(2);
        assertThat(preview.get("created").asInt()).isZero();
        assertThat(chargeCount()).isZero();
        assertThat(runCount()).isZero();
    }

    @Test
    void theSubstationSharesItsLossesAmongEveryPlotUnderIt() throws Exception {
        substation(true);

        JsonNode preview = body(send(chairman, post(PREVIEW), range()).andExpect(status().isOk()));

        // substations first, then quarters by name
        assertThat(lines(preview.get("nodes"), "name", "losses", "outcome")).containsExactly(
                "ТП-1|100.000|CHARGED", "Квартал А|100.000|CHARGED", "Квартал Б|50.000|CHARGED");
        assertThat(lines(preview.get("nodes").get(0).get("plots"), "plotNumber", "kwh"))
                .containsExactly("1|30.000", "2|20.000", "3|50.000");
        assertThat(lines(preview.get("nodes").get(2).get("plots"), "plotNumber", "kwh")).containsExactly("3|50.000");
        // P1 60 + 30 = 90 kWh, P2 40 + 20 = 60, P3 50 + 50 = 100, each priced once
        assertThat(lines(preview.get("charges"), "plotNumber", "kwh", "amount"))
                .containsExactly("1|90.000|388.80", "2|60.000|259.20", "3|100.000|432.00");
        assertThat(preview.get("total").asString()).isEqualTo("1080.00");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBookingWritesTheRunItsChargesPartsAndUsesAndIsAudited() throws Exception {
        quarter();
        String digest = body(send(chairman, post(PREVIEW), range()).andExpect(status().isOk())).get("digest")
                .asString();

        JsonNode booked = body(send(chairman, post(BOOK), range(digest)).andExpect(status().isOk()));

        assertThat(booked.get("preview").asBoolean()).isFalse();
        assertThat(booked.get("created").asInt()).isEqualTo(2);
        UUID run = UUID.fromString(booked.get("runId").asString());
        assertThat(booked.get("charges").get(0).has("chargeId")).isTrue();
        List<Map<String, Object>> charges = charges(p1);
        assertThat(charges).singleElement().satisfies(charge -> {
            assertThat(charge.get("amount")).isEqualTo("259.20");
            assertThat(charge.get("kind")).isEqualTo("REGULAR");
            assertThat(charge.get("source")).isEqualTo("LOSSES");
            assertThat(date(charge.get("service_from"))).hasToString(FROM);
            assertThat(date(charge.get("service_to"))).hasToString(TO);
            assertThat(date(charge.get("accrued_on"))).hasToString(TO);
            assertThat(charge.get("source_key")).isEqualTo("LOSSES_RUN:" + run + ":" + p1);
            assertThat(date(charge.get("billing_month"))).hasToString("2026-09-01");
        });
        assertThat(owner().queryForObject("select count(*) from charge where run_id = ?", Integer.class, run))
                .isEqualTo(2);
        assertThat(owner().queryForList("select node_meter_id from charge_part p join charge c on c.id = p.charge_id "
                + "where c.run_id = ?", UUID.class, run)).containsOnly(quarterA);
        assertThat(owner().queryForList("select meter_id::text || '@' || used_on::text from meter_value_use "
                + "where run_id = ?", String.class, run)).containsExactlyInAnyOrder(
                        quarterA + "@" + BEFORE, quarterA + "@" + TO, meterP1 + "@" + BEFORE, meterP1 + "@" + TO,
                        meterP2 + "@" + BEFORE, meterP2 + "@" + TO,
                        owner().queryForObject("select id from meter where tenant_id = ? and name = 'Освітлення'",
                                UUID.class, tenant) + "@" + BEFORE,
                        owner().queryForObject("select id from meter where tenant_id = ? and name = 'Освітлення'",
                                UUID.class, tenant) + "@" + TO);

        // the account shows the charge with its node and run
        JsonNode account = body(send(chairman, get(ACCOUNT.formatted(p1)), null).andExpect(status().isOk()));
        JsonNode calculation = account.get("charges").get(0).get("calculation");
        assertThat(calculation.get("runId").asString()).isEqualTo(run.toString());
        assertThat(calculation.get("nodeMeterId").asString()).isEqualTo(quarterA.toString());
        assertThat(calculation.get("parts").get(0).get("quantity").asString()).isEqualTo("60.000");
        assertThat(calculation.get("parts").get(0).get("calculated").asBoolean()).isFalse();

        // the history and the details
        JsonNode list = body(send(chairman, get(RUNS), null).andExpect(status().isOk()));
        assertThat(list).hasSize(1);
        JsonNode summary = list.get(0);
        assertThat(summary.get("status").asString()).isEqualTo("BOOKED");
        assertThat(summary.get("total").asString()).isEqualTo("432.00");
        assertThat(summary.get("plots").asInt()).isEqualTo(2);
        assertThat(summary.get("chargedNodes").asInt()).isEqualTo(1);
        assertThat(summary.get("skippedNodes").asInt()).isZero();
        assertThat(summary.get("billingMonth").asString()).isEqualTo("2026-09");
        assertThat(summary.get("reversible").asBoolean()).isTrue();
        assertThat(summary.get("createdByName").asString()).isNotBlank();
        JsonNode details = body(send(chairman, get(RUNS + "/" + run), null).andExpect(status().isOk()));
        assertThat(lines(details.get("nodes").get(0).get("plots"), "plotNumber", "kwh", "amount"))
                .containsExactly("1|60.000|259.20", "2|40.000|172.80");
        assertThat(details.get("nodes").get(0).get("pct").asString()).isEqualTo("9.09");
        assertThat(lines(details.get("charges"), "plotNumber", "kwh", "amount", "reversed"))
                .containsExactly("1|60.000|259.20|false", "2|40.000|172.80|false");

        await().atMost(Duration.ofSeconds(10)).until(() -> auditEntries("LOSSES_RUN_BOOKED").size() == 1);
        Map<String, Object> entry = auditEntries("LOSSES_RUN_BOOKED").getFirst();
        assertThat(entry.get("entity_type")).isEqualTo("LOSSES_RUN");
        assertThat(entry.get("entity_label")).isEqualTo(FROM + "/" + TO);
        assertThat((String) entry.get("changes")).contains("\"total\"", "432.00", "\"count\"");
    }

    @Test
    void aBookingWithAnOutdatedDigestIsRefusedAndWritesNothing() throws Exception {
        quarter();
        String digest = body(send(chairman, post(PREVIEW), range()).andExpect(status().isOk())).get("digest")
                .asString();
        // a new reading of P1 changes the shares
        owner().update("update meter_reading set value = 1700 where meter_id = ? and taken_on = ?::date", meterP1,
                TO);

        send(chairman, post(BOOK), range(digest)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREVIEW_OUTDATED"));
        assertThat(runCount()).isZero();
        assertThat(chargeCount()).isZero();
    }

    @Test
    void aNodeChargedForSomeOfTheDaysIsShownAndItsBookingRefused() throws Exception {
        quarter();
        send(chairman, post(BOOK), range()).andExpect(status().isOk());
        UUID first = UUID.fromString(body(send(chairman, get(RUNS), null)).get(0).get("id").asString());

        JsonNode preview = body(send(chairman, post(PREVIEW), range("2026-09-15", "2026-10-15"))
                .andExpect(status().isOk()));
        JsonNode node = preview.get("nodes").get(0);
        assertThat(node.get("outcome").asString()).isEqualTo("ALREADY_CHARGED");
        assertThat(node.get("warning").get("code").asString()).isEqualTo("ALREADY_CHARGED");
        assertThat(node.get("warning").get("runId").asString()).isEqualTo(first.toString());
        assertThat(node.get("warning").get("from").asString()).isEqualTo(FROM);
        assertThat(preview.get("charges")).isEmpty();

        send(chairman, post(BOOK), range("2026-09-15", "2026-10-15")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOSSES_RANGE_OVERLAP"));
        assertThat(runCount()).isEqualTo(1);
    }

    // ---- reversal

    @Test
    void aReversalReversesEveryChargeWithTheRunsReasonAndFreesTheDays() throws Exception {
        quarter();
        UUID run = UUID.fromString(body(send(chairman, post(BOOK), range()).andExpect(status().isOk()))
                .get("runId").asString());

        JsonNode reversed = body(send(chairman, post(RUNS + "/" + run + "/reverse"),
                Map.of("reason", "  Помилкові покази  ")).andExpect(status().isOk()));

        assertThat(reversed.get("status").asString()).isEqualTo("REVERSED");
        assertThat(reversed.get("reverseReason").asString()).isEqualTo("Помилкові покази");
        assertThat(reversed.get("reversible").asBoolean()).isFalse();
        assertThat(lines(reversed.get("charges"), "plotNumber", "reversed")).containsExactly("1|true", "2|true");
        assertThat(owner().queryForList("select amount::text || '|' || reason || '|' || source from charge "
                + "where kind = 'REVERSAL' and run_id = ? order by amount", String.class, run)).containsExactly(
                        "-259.20|Помилкові покази|MANUAL_REVERSAL", "-172.80|Помилкові покази|MANUAL_REVERSAL");
        assertThat(owner().queryForList("select distinct run_status from losses_run_node where run_id = ?",
                String.class, run)).containsExactly("REVERSED");

        send(chairman, post(RUNS + "/" + run + "/reverse"), Map.of("reason", "Ще раз"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RUN_ALREADY_REVERSED"));
        // the same days can be charged again
        send(chairman, post(BOOK), range()).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10)).until(() -> auditEntries("LOSSES_RUN_REVERSED").size() == 1);
        assertThat((String) auditEntries("LOSSES_RUN_REVERSED").getFirst().get("changes"))
                .contains("Помилкові покази", "-432.00");
    }

    @Test
    void aReversalNeedsAReasonAndAnOpenMonth() throws Exception {
        quarter();
        UUID run = UUID.fromString(body(send(chairman, post(BOOK), range()).andExpect(status().isOk()))
                .get("runId").asString());

        send(chairman, post(RUNS + "/" + run + "/reverse"), Map.of("reason", "   "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        closeMonth("2026-09");
        send(chairman, post(RUNS + "/" + run + "/reverse"), Map.of("reason", "Помилка"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RUN_IN_CLOSED_MONTH"));
        assertThat(body(send(chairman, get(RUNS), null)).get(0).get("reversible").asBoolean()).isFalse();
        send(chairman, post(RUNS + "/" + UUID.randomUUID() + "/reverse"), Map.of("reason", "Помилка"))
                .andExpect(status().isNotFound());
    }

    // ---- unknown values and the queue

    @Test
    void anUnknownNodeIsSkippedWithTheNodesAboveItQueuedAndChargedLaterByARetry() throws Exception {
        substation(false);

        JsonNode booked = body(send(chairman, post(BOOK), range()).andExpect(status().isOk()));

        assertThat(lines(booked.get("nodes"), "name", "outcome")).containsExactly("ТП-1|SKIPPED_ABOVE",
                "Квартал А|CHARGED", "Квартал Б|UNKNOWN");
        JsonNode unknown = booked.get("nodes").get(2).get("warning");
        assertThat(unknown.get("code").asString()).isEqualTo("UNKNOWN_VALUE");
        assertThat(unknown.get("meterId").asString()).isEqualTo(quarterB.toString());
        assertThat(unknown.get("meterName").asString()).isEqualTo("Квартал Б");
        assertThat(unknown.get("lastReadingOn").asString()).isEqualTo(BEFORE);
        JsonNode above = booked.get("nodes").get(0).get("warning");
        assertThat(above.get("code").asString()).isEqualTo("SKIPPED_ABOVE");
        assertThat(above.get("meterId").asString()).isEqualTo(quarterB.toString());
        assertThat(lines(booked.get("charges"), "plotNumber", "amount")).containsExactly("1|259.20", "2|172.80");

        // both skipped nodes are in the queue
        JsonNode queue = body(send(chairman, get(NOT_CHARGED), null).andExpect(status().isOk()));
        assertThat(lines(queue, "kind", "reason", "meterId", "from", "to")).containsExactlyInAnyOrder(
                "LOSSES_NODE|UNKNOWN_NODE_VALUE|" + substation + "|" + FROM + "|" + TO,
                "LOSSES_NODE|UNKNOWN_NODE_VALUE|" + quarterB + "|" + FROM + "|" + TO);
        UUID itemB = itemOf(queue, quarterB);
        UUID itemS = itemOf(queue, substation);

        // still unknown: the retry keeps the item
        JsonNode retried = body(send(chairman, post(RETRY.formatted(itemB)), null).andExpect(status().isOk()));
        assertThat(retried.get("resolved").asBoolean()).isFalse();

        // B is read after the range end: its retry books a run of B alone, then the substation's
        reading(quarterB, TO, "8050");
        assertThat(body(send(chairman, post(RETRY.formatted(itemB)), null).andExpect(status().isOk()))
                .get("resolved").asBoolean()).isTrue();
        assertThat(body(send(chairman, post(RETRY.formatted(itemS)), null).andExpect(status().isOk()))
                .get("resolved").asBoolean()).isTrue();

        assertThat(runCount()).isEqualTo(3);
        assertThat(owner().queryForList("select amount::text from charge where plot_id = ? order by created_at",
                String.class, p3)).containsExactly("216.00", "216.00");
        assertThat(owner().queryForList("select amount::text from charge where plot_id = ? order by created_at",
                String.class, p1)).containsExactly("259.20", "129.60");
        assertThat(body(send(chairman, get(NOT_CHARGED), null))).isEmpty();
        await().atMost(Duration.ofSeconds(10)).until(() -> owner().queryForObject(
                "select count(*) from audit_entry where tenant_id = ? and action = 'NOT_CHARGED_RETRIED' "
                        + "and entity_type = 'METER'", Integer.class, tenant) == 3);
    }

    @Test
    void aValueBetweenReadingsIsMarkedCalculated() throws Exception {
        quarterA = namedMeter("QUARTER", "Квартал А", null, 5000, 6100);
        // P1 read on 31.08 and 10.10 (40 days): the end of 30.09 is interpolated
        meterP1 = plotMeter(p1, quarterA, 1000, null);
        reading(meterP1, "2026-10-10", "1800");

        JsonNode preview = body(send(chairman, post(PREVIEW), range()).andExpect(status().isOk()));

        JsonNode node = preview.get("nodes").get(0);
        // 800 x 30 / 40 = 600 kWh
        assertThat(node.get("children").asString()).isEqualTo("600.000");
        assertThat(node.get("calculated").asBoolean()).isTrue();
        send(chairman, post(BOOK), range()).andExpect(status().isOk());
        JsonNode account = body(send(chairman, get(ACCOUNT.formatted(p1)), null).andExpect(status().isOk()));
        assertThat(account.get("charges").get(0).get("calculation").get("parts").get(0).get("calculated")
                .asBoolean()).isTrue();
    }

    @Test
    void theReadingsABookedRunUsesCannotBeCorrectedUntilItIsReversed() throws Exception {
        quarter();
        UUID reading = owner().queryForObject("select id from meter_reading where meter_id = ? and taken_on = ?::date",
                UUID.class, quarterA, TO);
        UUID run = UUID.fromString(body(send(chairman, post(BOOK), range()).andExpect(status().isOk()))
                .get("runId").asString());

        send(chairman, put("/api/v1/readings/" + reading), Map.of("takenOn", TO, "value", "6150"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("READING_IN_USE"));

        send(chairman, post(RUNS + "/" + run + "/reverse"), Map.of("reason", "Помилковий показ"))
                .andExpect(status().isOk());
        send(chairman, put("/api/v1/readings/" + reading), Map.of("takenOn", TO, "value", "6150"))
                .andExpect(status().isOk());
    }

    // ---- rules of the request

    @Test
    void runsNeedTheProportionalMode() throws Exception {
        quarter();
        owner().update("update cooperative_settings set losses_mode = 'FIXED_PERCENT' where tenant_id = ?", tenant);

        send(chairman, post(PREVIEW), range()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOSSES_MODE_NOT_PROPORTIONAL"));
        send(chairman, post(BOOK), range()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOSSES_MODE_NOT_PROPORTIONAL"));
    }

    @Test
    void theRangeAndTheNodesAreChecked() throws Exception {
        quarter();

        send(chairman, post(PREVIEW), range(TO, FROM)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("to"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        send(chairman, post(PREVIEW), range(FROM, current().plusMonths(1).atDay(1).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DATE_IN_FUTURE"));
        Map<String, Object> unknownNode = range();
        unknownNode.put("nodeIds", List.of(UUID.randomUUID().toString()));
        send(chairman, post(PREVIEW), unknownNode).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("nodeIds[0]"))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        send(chairman, post(PREVIEW), Map.of("from", FROM)).andExpect(status().isBadRequest());
    }

    @Test
    void aRunCanBeLimitedToSomeNodes() throws Exception {
        substation(true);
        Map<String, Object> onlyB = range();
        onlyB.put("nodeIds", List.of(quarterB.toString()));

        JsonNode preview = body(send(chairman, post(PREVIEW), onlyB).andExpect(status().isOk()));

        assertThat(lines(preview.get("nodes"), "name", "outcome")).containsExactly("Квартал Б|CHARGED");
        assertThat(lines(preview.get("charges"), "plotNumber", "amount")).containsExactly("3|216.00");
    }

    // ---- permissions

    @Test
    void theTreasurerSeesTheRunsButRunsAndReversesNothingAndAMemberNothing() throws Exception {
        quarter();
        UUID run = UUID.fromString(body(send(chairman, post(BOOK), range()).andExpect(status().isOk()))
                .get("runId").asString());

        send(treasurer, get(RUNS), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reversible").value(false));
        send(treasurer, get(RUNS + "/" + run), null).andExpect(status().isOk());
        send(treasurer, post(PREVIEW), range()).andExpect(status().isForbidden());
        send(treasurer, post(BOOK), range()).andExpect(status().isForbidden());
        send(treasurer, post(RUNS + "/" + run + "/reverse"), Map.of("reason", "Помилка"))
                .andExpect(status().isForbidden());
        for (String bearer : List.of(member,
                TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer())) {
            send(bearer, get(RUNS), null).andExpect(status().isForbidden());
            send(bearer, get(RUNS + "/" + run), null).andExpect(status().isForbidden());
            send(bearer, post(PREVIEW), range()).andExpect(status().isForbidden());
            send(bearer, post(RUNS + "/" + run + "/reverse"), Map.of("reason", "Помилка"))
                    .andExpect(status().isForbidden());
        }
        assertThat(runCount()).isEqualTo(1);
    }

    // ---- fixtures

    /** A named meter (substation, quarter or common consumer) under the parent, read on 31.08 and 30.09 (or not). */
    private UUID namedMeter(String kind, String name, UUID parent, int before, Integer after) {
        UUID id = UUID.randomUUID();
        owner().update("insert into meter (id, tenant_id, kind, resource, serial, name, installed_on, initial_value) "
                + "values (?, ?, ?, 'ELECTRICITY', ?, ?, ?::date, 0)", id, tenant, kind,
                "LR-" + id.toString().substring(0, 8), name, INSTALLED);
        place(id, parent);
        reading(id, BEFORE, Integer.toString(before));
        if (after != null) {
            reading(id, TO, Integer.toString(after));
        }
        return id;
    }

    private UUID plotMeter(UUID plot, UUID parent, int before, Integer after) {
        UUID id = UUID.randomUUID();
        owner().update("insert into meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, "
                + "initial_value) values (?, ?, 'PLOT', 'ELECTRICITY', ?, ?, ?::date, 0)", id, tenant,
                "LR-" + id.toString().substring(0, 8), plot, INSTALLED);
        place(id, parent);
        reading(id, BEFORE, Integer.toString(before));
        if (after != null) {
            reading(id, TO, Integer.toString(after));
        }
        return id;
    }

    private void place(UUID meter, UUID parent) {
        if (parent != null) {
            owner().update("insert into meter_parent (tenant_id, meter_id, parent_meter_id, valid_from) "
                    + "values (?, ?, ?, ?::date)", tenant, meter, parent, INSTALLED);
        }
    }

    private void reading(UUID meter, String on, String value) {
        owner().update("insert into meter_reading (tenant_id, meter_id, taken_on, value, source, status) "
                + "values (?, ?, ?::date, ?, 'ENTERED', 'APPROVED')", tenant, meter, on, new BigDecimal(value));
    }

    private static Map<String, Object> range() {
        return range(FROM, TO);
    }

    private static Map<String, Object> range(String from, String to) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("to", to);
        return body;
    }

    private static Map<String, Object> range(String digest) {
        Map<String, Object> body = range();
        body.put("digest", digest);
        return body;
    }

    private int runCount() {
        return owner().queryForObject("select count(*) from losses_run where tenant_id = ?", Integer.class, tenant);
    }

    private List<Map<String, Object>> auditEntries(String action) {
        return owner().queryForList("select entity_type, entity_label, cast(changes as text) as changes "
                + "from audit_entry where tenant_id = ? and action = ?", tenant, action);
    }

    private static UUID itemOf(JsonNode queue, UUID meter) {
        for (JsonNode item : queue) {
            if (meter.toString().equals(item.get("meterId").asString())) {
                return UUID.fromString(item.get("id").asString());
            }
        }
        throw new AssertionError("No item of " + meter);
    }

    /** The given fields of every element joined by {@code |}. */
    private static List<String> lines(JsonNode array, String... fields) {
        List<String> lines = new ArrayList<>();
        for (JsonNode element : array) {
            List<String> values = new ArrayList<>();
            for (String field : fields) {
                JsonNode value = element.get(field);
                values.add(value == null || value.isNull() ? "" : value.asString());
            }
            lines.add(String.join("|", values));
        }
        return lines;
    }
}
