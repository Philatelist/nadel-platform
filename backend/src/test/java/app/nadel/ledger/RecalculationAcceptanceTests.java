package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

/**
 * Acceptance tests of the whole feature "Retroactive recalculation" (functional-spec.md of 012) through the public HTTP
 * API and the database as a cooperative lives it: the chairman's rate correction from the preview to the member's
 * account, a preview over several plots and charge types, the reason on every cause, who may preview and book every
 * kind of cause, and the append-only ledger after a booking. The slice tests keep the per-case rules (the planner, the
 * intervals, the runs, the cause-driven flows, the schema); this class keeps the stories that cross them. The
 * cooperative is the test's own (see {@link FreshCooperativeSupport}); months are relative to the current month in
 * Europe/Kyiv: the previous month ("September") is closed or open as each test says, the current one ("October") is
 * open. Membership per sotka per year on a 600 m2 plot: 60.00 is 30.00 a month, 50.00 is 25.00.
 * <pre>
 * @layer: integration
 * @spec: 012-retroactive-recalculation
 * @regression
 * </pre>
 */
@Tag("regression")
@Timeout(240)
class RecalculationAcceptanceTests extends ReadingChargeSupport {

    private static final String RECALCULATIONS = "/api/v1/recalculations";
    private static final String TARIFF_VERSION = "/api/v1/tariffs/%s/versions/%s";
    private static final String REASON = "Виправлено ставку членського внеску";

    private final String september = ym(1);
    private final String october = ym(0);

    // ---- requests

    private static Map<String, Object> terms(String validFrom, String rate) {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("validFrom", validFrom);
        terms.put("basis", "PER_AREA_SOTKA");
        terms.put("frequency", "YEARLY");
        terms.put("rate", rate);
        return terms;
    }

    private static Map<String, Object> request(String type, String key, Map<String, Object> change, String reason,
            String digest) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cause", Map.of("type", type, key, change));
        if (reason != null) {
            body.put("reason", reason);
        }
        if (digest != null) {
            body.put("digest", digest);
        }
        return body;
    }

    private static Map<String, Object> tariffCause(String type, Tariff tariff, boolean withVersion,
            Map<String, Object> terms, String reason, String digest) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("tariffId", tariff.id());
        if (withVersion) {
            change.put("versionId", tariff.version());
        }
        if (terms != null) {
            change.put("version", terms);
        }
        return request(type, "tariffVersion", change, reason, digest);
    }

    private static Map<String, Object> manual(String from, String to, List<UUID> types, List<UUID> plots,
            String reason, String digest) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("from", from);
        scope.put("to", to);
        scope.put("chargeTypeIds", types);
        scope.put("plotIds", plots);
        return request("MANUAL", "manual", scope, reason, digest);
    }

    private static Map<String, Object> readingCause(String type, Map<String, Object> change, String reason) {
        return request(type, "reading", change, reason, null);
    }

    private JsonNode preview(Map<String, Object> body) throws Exception {
        return body(send(chairman, post(RECALCULATIONS).param("preview", "true"), body).andExpect(status().isOk()));
    }

    private JsonNode book(Map<String, Object> body) throws Exception {
        return body(send(chairman, post(RECALCULATIONS).param("preview", "false"), body).andExpect(status().isOk()));
    }

    private JsonNode account(String bearer, UUID plot) throws Exception {
        return body(send(bearer, get(ACCOUNT.formatted(plot)), null).andExpect(status().isOk()));
    }

    // ---- fixtures and reads

    /** The month run of September and October, then September closed. */
    private void runSeptemberAndOctoberThenCloseSeptember() throws Exception {
        send(chairman, post(RUN.formatted(september, false)), null).andExpect(status().isOk());
        send(chairman, post(RUN.formatted(october, false)), null).andExpect(status().isOk());
        closeMonth(september);
    }

    /** The member of the cooperative owns the plot since 2020. */
    private void ownedByTheMember(UUID plot) {
        owner().update("insert into ownership (tenant_id, plot_id, member_id, share_numerator, share_denominator, "
                + "valid_from) values (?, ?, ?, 1, 1, date '2020-01-01')", tenant, plot, memberId);
    }

    private String otherCooperative(String baseRole) {
        String email = "recalculation-" + baseRole.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        giveBaseRole(OTHER_TENANT, newMember(OTHER_TENANT, email), baseRole);
        return TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), email);
    }

    private static String operator() {
        return TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();
    }

    private static String debtOf(JsonNode account, String code) {
        for (JsonNode debt : account.get("debts")) {
            if (code.equals(debt.get("chargeTypeCode").asString())) {
                return debt.get("debt").asString();
            }
        }
        return null;
    }

    private static JsonNode chargeOf(JsonNode account, String id) {
        for (JsonNode charge : account.get("charges")) {
            if (charge.get("id").asString().equals(id)) {
                return charge;
            }
        }
        throw new AssertionError("no charge " + id + " on the account");
    }

    private static List<String> ids(JsonNode page) {
        return page.get("items").valueStream().map(item -> item.get("id").asString()).toList();
    }

    private UUID regularCharge(UUID plot, Tariff tariff, String month) {
        return (UUID) charges(plot).stream().filter(c -> "REGULAR".equals(c.get("kind")))
                .filter(c -> tariff.id().equals(c.get("tariff_id")))
                .filter(c -> date(c.get("service_from")).equals(YearMonth.parse(month).atDay(1)))
                .findFirst().orElseThrow().get("id");
    }

    private int recalculationCount() {
        return owner().queryForObject("select count(*) from recalculation where tenant_id = ?", Integer.class,
                tenant);
    }

    private String storedRate(UUID version) {
        return owner().queryForObject("select rate::text from tariff_version where id = ?", String.class, version);
    }

    private int versionCount(UUID tariff) {
        return owner().queryForObject("select count(*) from tariff_version where tariff_id = ? "
                + "and deleted_at is null", Integer.class, tariff);
    }

    private List<String> approvedReadings(UUID meter) {
        return owner().queryForList("select taken_on::text || '|' || value::text from meter_reading "
                + "where meter_id = ? and status = 'APPROVED' order by taken_on", String.class, meter);
    }

    // ---- the story: the rate 60 -> 50, from the chairman's preview to the member's account

    @Test
    void theRateCorrectionIsPreviewedBookedWithTheReasonOnEveryReversalAndAdjustmentAndSeenByEachRole()
            throws Exception {
        UUID plot7 = plot("7", "600.00");
        Tariff membership = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "60.0000",
                september + "-01");
        ownedByTheMember(plot7);
        runSeptemberAndOctoberThenCloseSeptember();
        UUID septemberCharge = regularCharge(plot7, membership, september);
        UUID octoberCharge = regularCharge(plot7, membership, october);
        assertThat(debtOf(account(chairman, plot7), "MEMBERSHIP")).isEqualTo("60.00");
        int before = chargeCount();

        JsonNode preview = preview(tariffCause("TARIFF_VERSION_UPDATE", membership, true, terms(september + "-01",
                "50"), null, null));

        // September: an adjustment of -5.00 in October; October: the 30.00 reversed and 25.00 charged
        assertThat(preview.get("lines").valueStream().map(l -> l.get("action").asString()).toList())
                .containsExactly("ADJUST", "REVERSE_AND_RECHARGE");
        assertThat(preview.get("lines").get(0).get("difference").asString()).isEqualTo("-5.00");
        assertThat(preview.get("lines").get(0).get("bookedIn").asString()).isEqualTo(october);
        assertThat(preview.get("lines").get(1).get("booked").asString()).isEqualTo("30.00");
        assertThat(preview.get("lines").get(1).get("expected").asString()).isEqualTo("25.00");
        assertThat(chargeCount()).isEqualTo(before);
        assertThat(recalculationCount()).isZero();

        JsonNode booked = book(tariffCause("TARIFF_VERSION_UPDATE", membership, true, terms(september + "-01", "50"),
                REASON, preview.get("digest").asString()));
        UUID recalculation = UUID.fromString(booked.get("id").asString());
        assertThat(chargeCount()).isEqualTo(before + 3);

        // every reversal and adjustment of the recalculation carries the reason entered; the new charge is regular
        List<Map<String, Object>> made = owner().queryForList("select id, kind, reason from charge "
                + "where tenant_id = ? and recalculation_id = ?", tenant, recalculation);
        assertThat(made).extracting(row -> row.get("kind")).containsExactlyInAnyOrder("ADJUSTMENT", "REVERSAL",
                "REGULAR");
        assertThat(made).filteredOn(row -> !"REGULAR".equals(row.get("kind"))).hasSize(2)
                .allSatisfy(row -> assertThat(row.get("reason")).isEqualTo(REASON));

        // September is closed and stays as it was; its correction is the adjustment in October
        assertThat(owner().queryForObject("select amount::text from charge where id = ?", String.class,
                septemberCharge)).isEqualTo("30.00");
        UUID adjustment = UUID.fromString(booked.get("lines").get(0).get("adjustmentChargeId").asString());

        // the chairman's account: the adjustment with its detail and links, the debt lowered to 50.00
        JsonNode chairmanAccount = account(chairman, plot7);
        assertThat(debtOf(chairmanAccount, "MEMBERSHIP")).isEqualTo("50.00");
        JsonNode row = chargeOf(chairmanAccount, adjustment.toString());
        assertThat(row.get("kind").asString()).isEqualTo("ADJUSTMENT");
        assertThat(row.get("amount").asString()).isEqualTo("-5.00");
        assertThat(row.get("billingMonth").asString()).isEqualTo(october);
        assertThat(row.get("serviceFrom").asString()).isEqualTo(september + "-01");
        assertThat(row.get("serviceTo").asString()).isEqualTo(YearMonth.parse(september).atEndOfMonth().toString());
        assertThat(row.get("reason").asString()).isEqualTo(REASON);
        assertThat(row.get("adjusts").get("chargeId").asString()).isEqualTo(septemberCharge.toString());
        assertThat(row.get("adjusts").get("billingMonth").asString()).isEqualTo(september);
        assertThat(row.get("recalculationId").asString()).isEqualTo(recalculation.toString());
        assertThat(row.get("reversible").asBoolean()).isFalse();
        assertThat(chargeOf(chairmanAccount, septemberCharge.toString()).get("adjustedBy")).hasSize(1);
        assertThat(chargeOf(chairmanAccount, octoberCharge.toString()).get("reversible").asBoolean()).isFalse();
        send(chairman, post(REVERSE.formatted(adjustment)), Map.of("reason", "Помилка"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADJUSTMENT_NOT_REVERSIBLE"));

        // "Adjustments only" lists the adjustment alone and keeps the debt
        JsonNode only = body(send(chairman, get(ACCOUNT.formatted(plot7)).param("kind", "ADJUSTMENT"), null)
                .andExpect(status().isOk()));
        assertThat(only.get("charges").valueStream().map(c -> c.get("id").asString()).toList())
                .containsExactly(adjustment.toString());
        assertThat(debtOf(only, "MEMBERSHIP")).isEqualTo("50.00");

        // the member of the plot sees the adjustment and the lowered debt, without the recalculation
        JsonNode memberAccount = account(member, plot7);
        assertThat(debtOf(memberAccount, "MEMBERSHIP")).isEqualTo("50.00");
        JsonNode memberRow = chargeOf(memberAccount, adjustment.toString());
        assertThat(memberRow.get("amount").asString()).isEqualTo("-5.00");
        assertThat(memberRow.get("reason").asString()).isEqualTo(REASON);
        assertThat(memberRow.get("adjusts").get("billingMonth").asString()).isEqualTo(september);
        assertThat(memberRow.has("recalculationId")).isFalse();
        send(member, get(RECALCULATIONS), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(member, get(RECALCULATIONS + "/" + recalculation), null).andExpect(status().isForbidden());

        // the treasurer reads the record and sees the recalculation on the account
        assertThat(ids(body(send(treasurer, get(RECALCULATIONS), null).andExpect(status().isOk()))))
                .containsExactly(recalculation.toString());
        assertThat(chargeOf(account(treasurer, plot7), adjustment.toString()).get("recalculationId").asString())
                .isEqualTo(recalculation.toString());

        // another cooperative sees neither the plot, the adjustment nor the record
        for (String other : new String[] {otherCooperative("CHAIRMAN"), otherCooperative("MEMBER")}) {
            send(other, get(ACCOUNT.formatted(plot7)), null).andExpect(status().isNotFound());
            send(other, get(RECALCULATIONS + "/" + recalculation), null).andExpect(status().is4xxClientError());
        }
        String otherChairman = otherCooperative("CHAIRMAN");
        assertThat(ids(body(send(otherChairman, get(RECALCULATIONS), null).andExpect(status().isOk()))))
                .doesNotContain(recalculation.toString());
        send(otherChairman, get(RECALCULATIONS + "/" + recalculation), null).andExpect(status().isNotFound());
        send(operator(), get(RECALCULATIONS + "/" + recalculation), null).andExpect(status().isForbidden());
    }

    // ---- a preview over several plots and charge types

    @Test
    void aPreviewListsTheLinesByPlotAndMonthWithTheTotalsPerChargeTypeAndTheNumberOfPlots() throws Exception {
        UUID plot7 = plot("7", "600.00");
        UUID plot9 = plot("9", "612.00");
        UUID plot12 = plot("12", "1200.00");
        tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "50.0000", "2020-01-01");
        tariff("LAND_TAX", "Земельний податок", "PER_AREA_SOTKA", "YEARLY", "35.0000", "2020-01-01");
        runSeptemberAndOctoberThenCloseSeptember();
        owner().update("update plot set area_m2 = 700 where id in (?, ?)", plot7, plot9);
        int before = chargeCount();

        JsonNode preview = preview(manual(september, october, List.of(), List.of(), null, null));

        // plots 7 and 9 changed (plot 12 did not): membership 29.17 against 25.00 and 25.50, land tax 20.42 against
        // 17.50 and 17.85, in both months
        assertThat(preview.get("plotCount").asInt()).isEqualTo(2);
        assertThat(preview.get("lines")).hasSize(8);
        assertThat(total(preview, "MEMBERSHIP")).isEqualTo("15.68"); // 2 * (4.17 + 3.67)
        assertThat(total(preview, "LAND_TAX")).isEqualTo("10.98"); // 2 * (2.92 + 2.57)
        assertThat(preview.get("total").asString()).isEqualTo("26.66");
        List<String> order = new ArrayList<>();
        for (JsonNode line : preview.get("lines")) {
            assertThat(line.get("plotNumber").asString()).isIn("7", "9");
            assertThat(line.get("chargeType").get("code").asString()).isIn("MEMBERSHIP", "LAND_TAX");
            assertThat(line.get("action").asString()).isIn("ADJUST", "REVERSE_AND_RECHARGE");
            assertThat(line.get("reasons")).isNotEmpty();
            BigDecimal effective = new BigDecimal(line.get("effective").asString());
            assertThat(new BigDecimal(line.get("difference").asString()))
                    .isEqualByComparingTo(new BigDecimal(line.get("expected").asString()).subtract(effective));
            // a closed month is adjusted in the earliest open month, an open one is charged again in itself
            assertThat(line.get("bookedIn").asString()).isEqualTo(october);
            order.add(line.get("plotNumber").asString() + "|" + line.get("serviceFrom").asString());
        }
        // grouped by plot, then by month
        assertThat(order).isSorted();
        assertThat(order.stream().map(key -> key.split("\\|")[0]).distinct().toList()).containsExactly("7", "9");
        assertThat(chargeCount()).isEqualTo(before);
        assertThat(recalculationCount()).isZero();

        JsonNode booked = book(manual(september, october, List.of(), List.of(), REASON,
                preview.get("digest").asString()));

        assertThat(booked.get("total").asString()).isEqualTo("26.66");
        assertThat(booked.get("plotCount").asInt()).isEqualTo(2);
        JsonNode list = body(send(chairman, get(RECALCULATIONS), null).andExpect(status().isOk()));
        assertThat(list.get("items").get(0).get("total").asString()).isEqualTo("26.66");
        assertThat(list.get("items").get(0).get("plotCount").asInt()).isEqualTo(2);
        assertThat(list.get("items").get(0).get("lineCount").asInt()).isEqualTo(8);
        // a plot nothing changed for keeps its account
        assertThat(charges(plot12)).hasSize(4);
    }

    // ---- a reason of at most 500 characters, whatever the cause

    @Test
    void aReasonOfMoreThan500CharactersBooksNothingAndChangesNothingForEveryCause() throws Exception {
        UUID plot7 = plot("7", "600.00");
        Tariff membership = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "60.0000",
                september + "-01");
        tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", "2020-01-01");
        UUID meter = electricityMeter(plot7, current().minusMonths(3).atDay(3).toString(), "8140");
        UUID first = entered(meter, current().minusMonths(2).atDay(5).toString(), "8205");
        entered(meter, current().minusMonths(1).atDay(5).toString(), "8270");
        awaitCharges(2);
        send(chairman, post(RUN.formatted(september, false)), null).andExpect(status().isOk());
        send(chairman, post(RUN.formatted(october, false)), null).andExpect(status().isOk());
        owner().update("update plot set area_m2 = 700 where id = ?", plot7);
        List<String> readingsBefore = approvedReadings(meter);
        int charges = chargeCount();
        String tooLong = "я".repeat(501);
        String inside = current().minusMonths(2).atDay(20).toString();

        List<Map<String, Object>> bodies = List.of(
                manual(september, october, List.of(), List.of(), tooLong, null),
                tariffCause("TARIFF_VERSION_UPDATE", membership, true, terms(september + "-01", "50"), tooLong, null),
                tariffCause("TARIFF_VERSION_DELETE", membership, true, null, tooLong, null),
                tariffCause("TARIFF_VERSION_ADD", membership, false, terms(september + "-16", "55"), tooLong, null),
                readingCause("READING_CORRECT", Map.of("readingId", first, "reading",
                        Map.of("takenOn", current().minusMonths(2).atDay(5).toString(), "value", "8215")), tooLong),
                readingCause("READING_DELETE", Map.of("readingId", first), tooLong),
                readingCause("READING_ENTER", Map.of("meterId", meter, "reading",
                        Map.of("takenOn", inside, "value", "8230")), tooLong));
        for (Map<String, Object> body : bodies) {
            send(chairman, post(RECALCULATIONS).param("preview", "false"), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("reason"))
                    .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        }

        assertThat(chargeCount()).isEqualTo(charges);
        assertThat(recalculationCount()).isZero();
        assertThat(storedRate(membership.version())).isEqualTo("60.0000");
        assertThat(versionCount(membership.id())).isEqualTo(1);
        assertThat(approvedReadings(meter)).isEqualTo(readingsBefore);

        // 500 characters are accepted
        JsonNode booked = book(tariffCause("TARIFF_VERSION_UPDATE", membership, true, terms(september + "-01", "50"),
                "я".repeat(500), null));
        assertThat(booked.get("booked").asBoolean()).isTrue();
        assertThat(storedRate(membership.version())).isEqualTo("50.0000");
        assertThat(recalculationCount()).isEqualTo(1);
    }

    // ---- who may preview and book, whatever the cause

    @Test
    void onlyTheChairmanMayPreviewOrBookAnyKindOfCauseAndNothingChanges() throws Exception {
        UUID plot7 = plot("7", "600.00");
        Tariff membership = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "60.0000",
                september + "-01");
        tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", "2020-01-01");
        UUID meter = electricityMeter(plot7, current().minusMonths(3).atDay(3).toString(), "8140");
        UUID first = entered(meter, current().minusMonths(2).atDay(5).toString(), "8205");
        entered(meter, current().minusMonths(1).atDay(5).toString(), "8270");
        awaitCharges(2);
        send(chairman, post(RUN.formatted(september, false)), null).andExpect(status().isOk());
        send(chairman, post(RUN.formatted(october, false)), null).andExpect(status().isOk());
        List<String> readingsBefore = approvedReadings(meter);
        int charges = chargeCount();
        String inside = current().minusMonths(2).atDay(20).toString();
        List<Map<String, Object>> bodies = List.of(
                manual(september, october, List.of(), List.of(), REASON, null),
                tariffCause("TARIFF_VERSION_ADD", membership, false, terms(september + "-16", "55"), REASON, null),
                tariffCause("TARIFF_VERSION_UPDATE", membership, true, terms(september + "-01", "50"), REASON, null),
                tariffCause("TARIFF_VERSION_DELETE", membership, true, null, REASON, null),
                readingCause("READING_ENTER", Map.of("meterId", meter, "reading",
                        Map.of("takenOn", inside, "value", "8230")), REASON),
                readingCause("READING_CORRECT", Map.of("readingId", first, "reading",
                        Map.of("takenOn", current().minusMonths(2).atDay(5).toString(), "value", "8215")), REASON),
                readingCause("READING_DELETE", Map.of("readingId", first), REASON));

        for (String bearer : new String[] {treasurer, member, operator()}) {
            for (Map<String, Object> body : bodies) {
                for (String preview : new String[] {"true", "false"}) {
                    send(bearer, post(RECALCULATIONS).param("preview", preview), body)
                            .andExpect(status().isForbidden())
                            .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
                }
            }
        }
        for (Map<String, Object> body : bodies) {
            send(null, post(RECALCULATIONS).param("preview", "true"), body).andExpect(status().isUnauthorized());
        }
        // the plain endpoints stay closed to the same callers: a used version and a charged reading are not changed
        send(treasurer, put(TARIFF_VERSION.formatted(membership.id(), membership.version())),
                terms(september + "-01", "50")).andExpect(status().isForbidden());
        send(treasurer, delete(TARIFF_VERSION.formatted(membership.id(), membership.version())), null)
                .andExpect(status().isForbidden());
        send(member, put(READINGS + "/" + first), Map.of("takenOn", current().minusMonths(2).atDay(5).toString(),
                "value", "8215")).andExpect(status().isForbidden());

        assertThat(chargeCount()).isEqualTo(charges);
        assertThat(recalculationCount()).isZero();
        assertThat(storedRate(membership.version())).isEqualTo("60.0000");
        assertThat(versionCount(membership.id())).isEqualTo(1);
        assertThat(approvedReadings(meter)).isEqualTo(readingsBefore);
        // the chairman may
        assertThat(preview(bodies.get(2)).get("preview").asBoolean()).isTrue();
    }

    // ---- the ledger stays append-only after a recalculation

    @Test
    void afterARecalculationNoChargeAdjustmentRecordOrLineCanBeChangedOrDeleted() throws Exception {
        UUID plot7 = plot("7", "600.00");
        tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "50.0000", "2020-01-01");
        runSeptemberAndOctoberThenCloseSeptember();
        owner().update("update plot set area_m2 = 700 where id = ?", plot7);
        JsonNode booked = book(manual(september, october, List.of(typeId("MEMBERSHIP")), List.of(plot7), REASON,
                null));
        UUID recalculation = UUID.fromString(booked.get("id").asString());
        UUID adjustment = UUID.fromString(booked.get("lines").get(0).get("adjustmentChargeId").asString());
        UUID reversal = UUID.fromString(booked.get("lines").get(1).get("reversalChargeId").asString());
        UUID recharge = UUID.fromString(booked.get("lines").get(1).get("newChargeId").asString());
        UUID line = owner().queryForObject("select id from recalculation_line where recalculation_id = ? "
                + "order by position limit 1", UUID.class, recalculation);
        Map<UUID, String> rows = new LinkedHashMap<>();
        rows.put(adjustment, "charge");
        rows.put(reversal, "charge");
        rows.put(recharge, "charge");
        rows.put(recalculation, "recalculation");
        rows.put(line, "recalculation_line");
        String account = account(chairman, plot7).get("total").asString();

        rows.forEach((id, table) -> {
            // the application is refused by its privileges, the database owner by the trigger
            for (String statement : new String[] {"update " + table + " set tenant_id = tenant_id where id = ?",
                    "delete from " + table + " where id = ?"}) {
                assertThatThrownBy(() -> inTenant(tenant, sql -> sql.update(statement, id)))
                        .isInstanceOf(DataAccessException.class)
                        .hasStackTraceContaining("permission denied for table " + table);
                assertThatThrownBy(() -> owner().update(statement, id)).isInstanceOf(DataAccessException.class)
                        .hasStackTraceContaining("append-only");
            }
        });

        assertThat(owner().queryForObject("select amount::text from charge where id = ?", String.class, adjustment))
                .isEqualTo("4.17");
        assertThat(owner().queryForObject("select amount::text from charge where id = ?", String.class, reversal))
                .isEqualTo("-25.00");
        assertThat(owner().queryForObject("select amount::text from charge where id = ?", String.class, recharge))
                .isEqualTo("29.17");
        assertThat(owner().queryForObject("select count(*) from recalculation_line where recalculation_id = ?",
                Integer.class, recalculation)).isEqualTo(2);
        assertThat(recalculationCount()).isEqualTo(1);
        assertThat(account(chairman, plot7).get("total").asString()).isEqualTo(account);
    }
}
