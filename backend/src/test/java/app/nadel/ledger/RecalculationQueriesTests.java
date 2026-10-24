package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The booked recalculations through the API (feature 012 §2.7 and §2.8): the list newest first, a record with its scope,
 * lines and totals, 404 for an unknown record and for one of another cooperative, and who may read them (chairman,
 * treasurer and a custom role with {@code CHARGES_VIEW}; not a member nor the platform operator). Records are written
 * through the test support so their shape is exact; {@code POST /recalculations} answers a caller with
 * {@code RECALCULATION_RUN} and 403 to everyone else (the booking itself: {@code ManualRecalculationTests}).
 */
class RecalculationQueriesTests extends LedgerApiSupport {

    private static final String LIST = "/api/v1/recalculations";
    private static final String ONE = "/api/v1/recalculations/%s";
    private static final String REASON = "Виправлено ставку членського внеску";
    private static final Map<String, Object> MANUAL = Map.of("cause", Map.of("type", "MANUAL", "manual",
            Map.of("from", "2024-03", "to", "2024-04")), "reason", REASON);

    /** The 60 -> 50 example on one plot, booked: March (closed) adjusted -5.00 in April; April reversed and 25.00. */
    private record Booked(UUID plot, UUID march, UUID april, UUID recalculation, UUID adjustment, UUID reversal,
            UUID recharged) {
    }

    private Booked booked(String createdAt) {
        UUID plot = newPlot();
        UUID membership = typeId(DEMO_TENANT, "MEMBERSHIP");
        UUID march = tariffCharge(plot, "MEMBERSHIP", "30.00", "2024-03");
        closedMonth(DEMO_TENANT, "2024-03");
        UUID april = tariffCharge(plot, "MEMBERSHIP", "30.00", "2024-04");
        UUID recalculation = recalculation(DEMO_TENANT, "TARIFF_VERSION", REASON, "-10.00", 2, 1,
                demoMember("chairman@demo.local"), createdAt, "{\"causeType\": \"TARIFF_VERSION_UPDATE\", "
                        + "\"months\": [\"2024-03\", \"2024-04\"], \"chargeTypeIds\": [\"" + membership + "\"], "
                        + "\"plotIds\": [], \"tariffId\": \"" + UUID.randomUUID() + "\"}");
        UUID adjustment = adjustment(march, recalculation, "-5.00", "2024-04", REASON);
        UUID reversal = recalculationReversal(april, recalculation, REASON);
        UUID recharged = tariffCharge(plot, "MEMBERSHIP", "25.00", "2024-04");
        recalculationLine(DEMO_TENANT, recalculation, 1, new LineRow(plot, membership, "2024-03-01", "2024-03-31",
                march, "30.00", "0.00", "25.00", "-5.00", "ADJUST", "{TARIFF_VERSION}", null, null, adjustment));
        recalculationLine(DEMO_TENANT, recalculation, 2, new LineRow(plot, membership, "2024-04-01", "2024-04-30",
                april, "30.00", "0.00", "25.00", "-5.00", "REVERSE_AND_RECHARGE", "{TARIFF_VERSION}", reversal,
                recharged, null));
        return new Booked(plot, march, april, recalculation, adjustment, reversal, recharged);
    }

    private JsonNode list(String bearer, int size) throws Exception {
        return body(send(bearer, get(LIST).param("size", String.valueOf(size)), null).andExpect(status().isOk()));
    }

    private static List<String> ids(JsonNode page) {
        return page.get("items").valueStream().map(item -> item.get("id").asString()).toList();
    }

    // ---- the list

    @Test
    void theListShowsTheRecalculationsNewestFirst() throws Exception {
        UUID older = recalculation(DEMO_TENANT, "MANUAL", "Площа ділянки", "4.17", 1, 1, null,
                "2026-10-01T08:00:00Z", "{\"causeType\": \"MANUAL\"}");
        Booked newer = booked("2026-10-20T10:00:00Z");

        JsonNode page = list(chairman(), 100);

        List<String> ids = ids(page);
        assertThat(ids).containsSubsequence(newer.recalculation().toString(), older.toString());
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(100);
        assertThat(page.get("totalItems").asLong()).isGreaterThanOrEqualTo(2);
        JsonNode summary = page.get("items").get(ids.indexOf(newer.recalculation().toString()));
        assertThat(summary.get("cause").asString()).isEqualTo("TARIFF_VERSION");
        assertThat(summary.get("causeType").asString()).isEqualTo("TARIFF_VERSION_UPDATE");
        assertThat(summary.get("reason").asString()).isEqualTo(REASON);
        assertThat(summary.get("total").asString()).isEqualTo("-10.00");
        assertThat(summary.get("lineCount").asInt()).isEqualTo(2);
        assertThat(summary.get("plotCount").asInt()).isEqualTo(1);
        assertThat(summary.get("createdAt").asString()).startsWith("2026-10-20T10:00");
        assertThat(summary.get("createdByName").asString()).isNotBlank();
        JsonNode manual = page.get("items").get(ids.indexOf(older.toString()));
        assertThat(manual.get("cause").asString()).isEqualTo("MANUAL");
        assertThat(manual.get("total").asString()).isEqualTo("4.17");
        assertThat(manual.has("createdByName")).isFalse();
    }

    @Test
    void theListIsPaged() throws Exception {
        recalculation(DEMO_TENANT, "MANUAL", "Перша", "1.00", 1, 1, null, "2026-10-01T08:00:00Z", "{}");
        recalculation(DEMO_TENANT, "MANUAL", "Друга", "2.00", 1, 1, null, "2026-10-02T08:00:00Z", "{}");

        JsonNode first = list(chairman(), 1);

        assertThat(first.get("items")).hasSize(1);
        assertThat(first.get("totalPages").asInt()).isGreaterThanOrEqualTo(2);
        send(chairman(), get(LIST).param("size", "0"), null).andExpect(status().isBadRequest());
        send(chairman(), get(LIST).param("size", "101"), null).andExpect(status().isBadRequest());
    }

    // ---- one record

    @Test
    void aRecordShowsItsScopeLinesAndTotals() throws Exception {
        Booked given = booked("2026-10-20T10:00:00Z");
        UUID membership = typeId(DEMO_TENANT, "MEMBERSHIP");

        JsonNode record = body(send(chairman(), get(ONE.formatted(given.recalculation())), null)
                .andExpect(status().isOk()));

        assertThat(record.get("id").asString()).isEqualTo(given.recalculation().toString());
        assertThat(record.get("preview").asBoolean()).isFalse();
        assertThat(record.get("booked").asBoolean()).isTrue();
        assertThat(record.get("changeApplied").asBoolean()).isTrue();
        assertThat(record.get("cause").asString()).isEqualTo("TARIFF_VERSION");
        assertThat(record.get("causeType").asString()).isEqualTo("TARIFF_VERSION_UPDATE");
        assertThat(record.get("reason").asString()).isEqualTo(REASON);
        assertThat(record.get("total").asString()).isEqualTo("-10.00");
        assertThat(record.get("plotCount").asInt()).isEqualTo(1);
        assertThat(record.get("digest").asString()).isEqualTo("test-digest");
        assertThat(record.get("notCharged")).isEmpty();
        assertThat(record.get("createdByName").asString()).isNotBlank();
        assertThat(record.get("scope").get("months").valueStream().map(JsonNode::asString).toList())
                .containsExactly("2024-03", "2024-04");
        assertThat(record.get("scope").get("chargeTypeIds").get(0).asString()).isEqualTo(membership.toString());
        assertThat(record.get("scope").get("plotIds")).isEmpty();
        assertThat(record.get("scope").has("tariffId")).isTrue();
        assertThat(record.get("totals")).hasSize(1);
        assertThat(record.get("totals").get(0).get("chargeTypeCode").asString()).isEqualTo("MEMBERSHIP");
        assertThat(record.get("totals").get(0).get("amount").asString()).isEqualTo("-10.00");

        JsonNode lines = record.get("lines");
        assertThat(lines).hasSize(2);
        JsonNode adjust = lines.get(0);
        assertThat(adjust.get("plotId").asString()).isEqualTo(given.plot().toString());
        assertThat(adjust.get("plotNumber").asString()).isEqualTo(plotNumber(given.plot()));
        assertThat(adjust.get("chargeType").get("code").asString()).isEqualTo("MEMBERSHIP");
        assertThat(adjust.get("serviceFrom").asString()).isEqualTo("2024-03-01");
        assertThat(adjust.get("serviceTo").asString()).isEqualTo("2024-03-31");
        assertThat(adjust.get("originalChargeId").asString()).isEqualTo(given.march().toString());
        assertThat(adjust.get("booked").asString()).isEqualTo("30.00");
        assertThat(adjust.get("adjustments").asString()).isEqualTo("0.00");
        assertThat(adjust.get("effective").asString()).isEqualTo("30.00");
        assertThat(adjust.get("expected").asString()).isEqualTo("25.00");
        assertThat(adjust.get("difference").asString()).isEqualTo("-5.00");
        assertThat(adjust.get("action").asString()).isEqualTo("ADJUST");
        assertThat(adjust.get("bookedIn").asString()).isEqualTo("2024-04");
        assertThat(adjust.get("reasons").valueStream().map(JsonNode::asString).toList())
                .containsExactly("TARIFF_VERSION");
        assertThat(adjust.get("adjustmentChargeId").asString()).isEqualTo(given.adjustment().toString());
        assertThat(adjust.has("reversalChargeId")).isFalse();
        assertThat(adjust.get("createdChargeIds").valueStream().map(JsonNode::asString).toList())
                .containsExactly(given.adjustment().toString());

        JsonNode recharge = lines.get(1);
        assertThat(recharge.get("action").asString()).isEqualTo("REVERSE_AND_RECHARGE");
        assertThat(recharge.get("bookedIn").asString()).isEqualTo("2024-04");
        assertThat(recharge.get("reversalChargeId").asString()).isEqualTo(given.reversal().toString());
        assertThat(recharge.get("newChargeId").asString()).isEqualTo(given.recharged().toString());
        assertThat(recharge.get("createdChargeIds").valueStream().map(JsonNode::asString).toList())
                .containsExactly(given.reversal().toString(), given.recharged().toString());
    }

    @Test
    void theEffectiveAmountAddsTheAdjustmentsSoFar() throws Exception {
        UUID plot = newPlot();
        UUID membership = typeId(DEMO_TENANT, "MEMBERSHIP");
        UUID march = tariffCharge(plot, "MEMBERSHIP", "30.00", "2024-03");
        closedMonth(DEMO_TENANT, "2024-03");
        UUID recalculation = recalculation(DEMO_TENANT, "MANUAL", "Ставка 40,00", "-5.00", 1, 1, null,
                "2026-10-21T10:00:00Z", "{\"causeType\": \"MANUAL\"}");
        UUID adjustment = adjustment(march, recalculation, "-5.00", "2024-04", "Ставка 40,00");
        // functional spec 2.1: 30.00 already adjusted by -5.00, expected 20.00 -> adjustment -5.00
        recalculationLine(DEMO_TENANT, recalculation, 1, new LineRow(plot, membership, "2024-03-01", "2024-03-31",
                march, "30.00", "-5.00", "20.00", "-5.00", "ADJUST", "{}", null, null, adjustment));

        JsonNode line = body(send(chairman(), get(ONE.formatted(recalculation)), null).andExpect(status().isOk()))
                .get("lines").get(0);

        assertThat(line.get("adjustments").asString()).isEqualTo("-5.00");
        assertThat(line.get("effective").asString()).isEqualTo("25.00");
        assertThat(line.get("reasons")).isEmpty();
    }

    @Test
    void anUnknownRecordIsNotFound() throws Exception {
        send(chairman(), get(ONE.formatted(UUID.randomUUID())), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void anotherCooperativeNeverSeesTheRecords() throws Exception {
        Booked demo = booked("2026-10-20T10:00:00Z");
        UUID foreign = recalculation(OTHER_TENANT, "MANUAL", "Інший кооператив", "1.00", 1, 1, null,
                "2026-10-22T10:00:00Z", "{}");
        String other = otherChairman();

        send(other, get(ONE.formatted(demo.recalculation())), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(ids(list(other, 100))).contains(foreign.toString()).doesNotContain(demo.recalculation().toString());
        send(chairman(), get(ONE.formatted(foreign)), null).andExpect(status().isNotFound());
        assertThat(ids(list(chairman(), 100))).doesNotContain(foreign.toString());
    }

    // ---- who may read and run them

    @Test
    void theTreasurerAndChargesViewReadTheRecords() throws Exception {
        Booked given = booked("2026-10-20T10:00:00Z");

        for (String bearer : new String[] {treasurer(), customRoleToken("CHARGES_VIEW")}) {
            assertThat(ids(list(bearer, 100))).contains(given.recalculation().toString());
            send(bearer, get(ONE.formatted(given.recalculation())), null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.lines.length()").value(2));
        }
    }

    @Test
    void aMemberAndThePlatformOperatorCannotReadTheRecords() throws Exception {
        Booked given = booked("2026-10-20T10:00:00Z");
        own(demoMember("member@demo.local"), given.plot());

        for (String bearer : new String[] {member(), operator(), customRoleToken("CHARGES_RUN", "RECALCULATION_RUN")}) {
            send(bearer, get(LIST), null)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            send(bearer, get(ONE.formatted(given.recalculation())), null)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        send(null, get(LIST), null).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyRecalculationRunMayPreviewOrBook() throws Exception {
        for (String bearer : new String[] {treasurer(), member(), operator(),
                customRoleToken("CHARGES_VIEW", "CHARGES_RUN", "CHARGES_REVERSE", "TARIFFS_MANAGE")}) {
            for (String preview : new String[] {"true", "false"}) {
                send(bearer, post(LIST).param("preview", preview), MANUAL)
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            }
        }
        send(null, post(LIST).param("preview", "true"), MANUAL).andExpect(status().isUnauthorized());
    }

    @Test
    void aCallerWithRecalculationRunPreviewsAndIsToldWhenThereIsNothingToBook() throws Exception {
        // a range without any charge or tariff of the demo cooperative: no lines
        Map<String, Object> empty = Map.of("cause", Map.of("type", "MANUAL", "manual",
                Map.of("from", "2001-01", "to", "2001-02")), "reason", REASON);
        for (String bearer : new String[] {chairman(), customRoleToken("RECALCULATION_RUN")}) {
            send(bearer, post(LIST).param("preview", "true"), empty)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.preview").value(true))
                    .andExpect(jsonPath("$.lines.length()").value(0));
            send(bearer, post(LIST).param("preview", "false"), empty)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("NOTHING_TO_RECALCULATE"));
        }
        // the contract is still validated at the boundary
        send(chairman(), post(LIST).param("preview", "true"), Map.of("reason", REASON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
