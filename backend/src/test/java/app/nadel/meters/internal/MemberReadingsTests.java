package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * The member flow through the API (spec 010, sections 2.4 to 2.6): a member submits the reading of a meter of their own
 * plot, it waits for approval and is ignored for consumption, the board approves it (a new interval appears) or rejects
 * it with a reason the member sees; only the approve permission decides; another plot, another cooperative and the
 * platform operator see and change nothing.
 */
class MemberReadingsTests extends MeterTestSupport {

    private static final String STREET = "MT вул. Лісова";

    private UUID plot;
    private UUID meter;
    private RoleMember owner;
    private String member;

    @BeforeEach
    void ownPlotWithAMeter() throws Exception {
        plot = newPlot(STREET);
        owner = customRoleMember(); // a member without permissions
        own(owner.id(), plot);
        member = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), owner.email());
        Map<String, Object> body = plotMeter(plot, "ELECTRICITY", "MT-ЕМ-1", "2026-06-01", "8000");
        body.put("note", "Board only");
        meter = register(body);
        entered(meter, "2026-10-05", "8205");
    }

    // ---- submission

    @Test
    void aSubmittedReadingWaitsForApprovalAndDoesNotCount() throws Exception {
        JsonNode saved = body(submit(member, plot, submission(meter, "2026-10-15", "8210"))
                .andExpect(status().isCreated()));

        JsonNode reading = saved.get("reading");
        assertThat(reading.get("status").asString()).isEqualTo("PENDING");
        assertThat(reading.get("source").asString()).isEqualTo("MEMBER");
        assertThat(reading.get("takenOn").asString()).isEqualTo("2026-10-15");
        assertThat(reading.get("value").asString()).isEqualTo("8210.000");
        assertThat(reading.get("changeable").asBoolean()).isFalse();
        assertThat(reading.has("interval")).isFalse();
        assertThat(saved.get("supersededReadingIds")).isEmpty();
        assertThat(saved.get("warnings")).isEmpty();

        // the member sees it as pending; the consumption does not change
        JsonNode history = body(send(member, get(METERS + "/" + meter + "/readings"), null)
                .andExpect(status().isOk()));
        assertThat(history.get("readings").get(0).get("status").asString()).isEqualTo("PENDING");
        assertThat(intervals(history)).containsExactly("2026-06-01..2026-10-05=205.000");
        send(member, get("/api/v1/plots/" + plot + "/meters"), null)
                .andExpect(jsonPath("$[0].lastReading.takenOn").value("2026-10-05"));
        JsonNode range = consumption("2026-10-05", "2026-10-15");
        assertThat(range.get("known").asBoolean()).isFalse();
        assertThat(range.get("lastReadingOn").asString()).isEqualTo("2026-10-05");
    }

    @Test
    void aSubmissionWithoutADateIsDatedToday() throws Exception {
        Map<String, Object> body = submission(meter, null, "8210");

        submit(member, plot, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reading.takenOn").value(kyivToday().toString()));
    }

    @Test
    void aSecondSubmissionOfTheSameDateReplacesThePendingOne() throws Exception {
        UUID first = id(submit(member, plot, submission(meter, "2026-10-15", "8210")));

        JsonNode second = body(submit(member, plot, submission(meter, "2026-10-15", "8212"))
                .andExpect(status().isCreated()));

        assertThat(second.get("supersededReadingIds").get(0).asString()).isEqualTo(first.toString());
        assertThat(readingStatus(first)).isEqualTo("SUPERSEDED");
        UUID secondId = UUID.fromString(second.get("reading").get("id").asString());
        assertThat(readingStatus(secondId)).isEqualTo("PENDING");
        // the same value again changes nothing
        assertThat(id(submit(member, plot, submission(meter, "2026-10-15", "8212")))).isEqualTo(secondId);
        assertThat(readingCount(meter)).isEqualTo(3);
    }

    @Test
    void aPendingReadingNeverReplacesACountedOneUntilItIsApproved() throws Exception {
        UUID counted = entered(meter, "2026-10-15", "8210");

        UUID pending = id(submit(member, plot, submission(meter, "2026-10-15", "8211")));
        assertThat(readingStatus(counted)).isEqualTo("APPROVED");

        JsonNode approved = body(approve(chairman(), pending).andExpect(status().isOk()));
        assertThat(approved.get("supersededReadingIds").get(0).asString()).isEqualTo(counted.toString());
        assertThat(readingStatus(counted)).isEqualTo("SUPERSEDED");
        assertThat(readingStatus(pending)).isEqualTo("APPROVED");
        assertThat(intervals(history(meter))).containsExactly("2026-10-05..2026-10-15=6.000",
                "2026-06-01..2026-10-05=205.000");
    }

    @Test
    void aSubmissionIsCheckedLikeAnEntry() throws Exception {
        expectConflict(submission(meter, kyivToday().plusDays(1).toString(), "8300"), "READING_DATE_IN_FUTURE",
                "takenOn");
        expectConflict(submission(meter, "2026-05-31", "8000"), "READING_DATE_OUTSIDE_METER", "takenOn");
        expectConflict(submission(meter, "2026-10-15", "8100"), "READING_OUT_OF_ORDER", "value");
        submit(member, plot, submission(meter, "2026-10-15", "8.1234")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("value"))
                .andExpect(jsonPath("$.errors[0].code").value("METER_VALUE_INVALID"));
        assertThat(readingCount(meter)).isEqualTo(1);
    }

    @Test
    void aMemberSubmitsOnlyForTheActiveMetersOfTheirOwnPlots() throws Exception {
        UUID other = newPlot(STREET);
        UUID otherMeter = register(plotMeter(other, "ELECTRICITY", "MT-other", "2026-06-01", "0"));

        submit(member, other, submission(otherMeter, "2026-10-15", "10")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        submit(member, plot, submission(otherMeter, "2026-10-15", "10")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].field").value("meterId"));
        submit(member, UUID.randomUUID(), submission(meter, "2026-10-15", "8210")).andExpect(status().isNotFound());
        // the board does not own the plot: the member flow is not theirs
        submit(chairman(), plot, submission(meter, "2026-10-15", "8210")).andExpect(status().isNotFound());

        // after a replacement the old meter takes no readings from members, the new one does
        UUID replacement = UUID.fromString(body(send(chairman(), post(METERS + "/" + meter + "/replace"),
                MeterReplacementTests.replacement("2026-10-10", "8209", "MT-ЕМ-2", "0"))
                .andExpect(status().isCreated())).get("id").asString());
        submit(member, plot, submission(meter, "2026-10-08", "8206")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_REMOVED"));
        submit(member, plot, submission(replacement, "2026-10-15", "5")).andExpect(status().isCreated());
        assertThat(readingCount(otherMeter)).isZero();
        assertThat(readingCount(meter)).isEqualTo(1);
    }

    // ---- approval

    @Test
    void approvingMakesTheReadingCountAndANewIntervalAppears() throws Exception {
        UUID pending = id(submit(member, plot, submission(meter, "2026-10-15", "8210")));

        JsonNode approved = body(approve(chairman(), pending).andExpect(status().isOk()));

        JsonNode reading = approved.get("reading");
        assertThat(reading.get("id").asString()).isEqualTo(pending.toString());
        assertThat(reading.get("status").asString()).isEqualTo("APPROVED");
        assertThat(reading.get("source").asString()).isEqualTo("MEMBER");
        assertThat(reading.get("interval").get("from").asString()).isEqualTo("2026-10-05");
        assertThat(reading.get("interval").get("consumption").asString()).isEqualTo("5.000");
        assertThat(reading.get("changeable").asBoolean()).isTrue();
        assertThat(intervals(history(meter))).containsExactly("2026-10-05..2026-10-15=5.000",
                "2026-06-01..2026-10-05=205.000");
        assertThat(consumption("2026-10-05", "2026-10-15").get("consumption").asString()).isEqualTo("5.000");
        assertThat(pendingIds(chairman())).doesNotContain(pending);
        assertThat(ownerJdbc().queryForObject("select decided_by_member_id is not null and decided_at is not null "
                + "from meter_reading where id = ?", Boolean.class, pending)).isTrue();
    }

    @Test
    void approvalChecksTheReadingAgainstTheCountedReadingsAsTheyAreNow() throws Exception {
        UUID pending = id(submit(member, plot, submission(meter, "2026-10-15", "8210")));
        entered(meter, "2026-10-10", "8215"); // entered by the board meanwhile

        approve(chairman(), pending).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_OUT_OF_ORDER"))
                .andExpect(jsonPath("$.errors[0].field").value("value"));
        assertThat(readingStatus(pending)).isEqualTo("PENDING");
        // the approvals list shows it without an interval
        JsonNode item = pendingItem(chairman(), pending);
        assertThat(item.has("interval")).isFalse();
        assertThat(item.get("warnings")).isEmpty();
    }

    @Test
    void onlyAPendingReadingIsApprovedOrRejected() throws Exception {
        UUID counted = entered(meter, "2026-10-15", "8210");

        approve(chairman(), counted).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_STATUS_INVALID"));
        reject(chairman(), counted, "Wrong").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_STATUS_INVALID"));
        approve(chairman(), UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(readingStatus(counted)).isEqualTo("APPROVED");
    }

    // ---- rejection

    @Test
    void aRejectedReadingNeverCountsAndTheMemberSeesTheReason() throws Exception {
        UUID pending = id(submit(member, plot, submission(meter, "2026-10-15", "8210")));

        reject(chairman(), pending, "  Фото нечітке, передайте ще раз  ").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("Фото нечітке, передайте ще раз"));

        JsonNode history = body(send(member, get(METERS + "/" + meter + "/readings"), null)
                .andExpect(status().isOk()));
        JsonNode rejected = history.get("readings").get(0);
        assertThat(rejected.get("id").asString()).isEqualTo(pending.toString());
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejectReason").asString()).isEqualTo("Фото нечітке, передайте ще раз");
        assertThat(intervals(history)).containsExactly("2026-06-01..2026-10-05=205.000");
        approve(chairman(), pending).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_STATUS_INVALID"));
        assertThat(pendingIds(chairman())).doesNotContain(pending);
    }

    @Test
    void aRejectionNeedsAReason() throws Exception {
        UUID pending = id(submit(member, plot, submission(meter, "2026-10-15", "8210")));

        expectReasonProblem(pending, Map.of("reason", "   "), "REQUIRED");
        expectReasonProblem(pending, Map.of("reason", ""), "REQUIRED");
        expectReasonProblem(pending, Map.of(), "REQUIRED");
        expectReasonProblem(pending, Map.of("reason", "x".repeat(501)), "TOO_LONG");
        assertThat(readingStatus(pending)).isEqualTo("PENDING");
    }

    // ---- the approvals list

    @Test
    void theApprovalsListShowsEachPendingReadingWithItsMeterAndWhatApprovingWouldGive() throws Exception {
        UUID pending = id(submit(member, plot, submission(meter, "2026-10-15", "8210")));
        UUID counted = entered(meter, "2026-10-01", "8200");
        assertThat(pendingIds(chairman())).doesNotContain(counted);

        JsonNode item = pendingItem(chairman(), pending);
        assertThat(item.get("reading").get("status").asString()).isEqualTo("PENDING");
        assertThat(item.get("meter").get("id").asString()).isEqualTo(meter.toString());
        assertThat(item.get("meter").get("plot").get("number").asString()).isEqualTo(plotNumber(plot));
        assertThat(item.get("meter").get("note").asString()).isEqualTo("Board only");
        assertThat(item.get("meter").get("deletable").asBoolean()).isFalse();
        assertThat(item.get("interval").get("from").asString()).isEqualTo("2026-10-05");
        assertThat(item.get("interval").get("consumption").asString()).isEqualTo("5.000");
        assertThat(item.get("warnings")).isEmpty();

        send(chairman(), get(READINGS).param("status", "APPROVED"), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        send(chairman(), get(READINGS), null).andExpect(status().isBadRequest());
    }

    // ---- who may do what

    @Test
    void onlyTheApprovePermissionDecides() throws Exception {
        UUID pending = id(submit(member, plot, submission(meter, "2026-10-15", "8210")));

        for (String bearer : List.of(demo("treasurer@demo.local"), customRoleToken("METERS_VIEW", "READINGS_ENTER"),
                customRoleToken("METERS_VIEW", "METERS_MANAGE"), member)) {
            expectDecisionsDenied(bearer, pending);
        }
        assertThat(readingStatus(pending)).isEqualTo("PENDING");

        // the approve permission alone is enough; without METERS_VIEW the board's note is not shown
        String approver = customRoleToken("READINGS_APPROVE");
        JsonNode item = pendingItem(approver, pending);
        assertThat(item.get("meter").has("note")).isFalse();
        approve(approver, pending).andExpect(status().isOk())
                .andExpect(jsonPath("$.reading.status").value("APPROVED"))
                .andExpect(jsonPath("$.reading.changeable").value(false));
    }

    @Test
    void aCustomRoleRejectsWithTheApprovePermission() throws Exception {
        UUID pending = id(submit(member, plot, submission(meter, "2026-10-15", "8210")));

        reject(customRoleToken("READINGS_APPROVE"), pending, "Wrong meter").andExpect(status().isOk());

        assertThat(readingStatus(pending)).isEqualTo("REJECTED");
    }

    @Test
    void anotherCooperativeSeesAndDecidesNothing() throws Exception {
        UUID pending = id(submit(member, plot, submission(meter, "2026-10-15", "8210")));
        String other = otherChairman();

        assertThat(pendingIds(other)).doesNotContain(pending);
        approve(other, pending).andExpect(status().isNotFound());
        reject(other, pending, "Wrong").andExpect(status().isNotFound());
        submit(other, plot, submission(meter, "2026-10-16", "8220")).andExpect(status().isNotFound());
        assertThat(readingStatus(pending)).isEqualTo("PENDING");
        assertThat(readingCount(meter)).isEqualTo(2);
    }

    @Test
    void thePlatformOperatorHasNoAccess() throws Exception {
        UUID pending = id(submit(member, plot, submission(meter, "2026-10-15", "8210")));
        String operator = operator();

        submit(operator, plot, submission(meter, "2026-10-16", "8220")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        expectDecisionsDenied(operator, pending);
        assertThat(readingCount(meter)).isEqualTo(2);
    }

    @Test
    void withoutATokenTheAnswerIsUnauthorized() throws Exception {
        mvc.perform(get(READINGS).param("status", "PENDING")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/plots/" + plot + "/readings")).andExpect(status().isUnauthorized());
        mvc.perform(post(READINGS + "/" + UUID.randomUUID() + "/approve")).andExpect(status().isUnauthorized());
    }

    // ---- helpers

    private static Map<String, Object> submission(UUID meter, String takenOn, String value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("meterId", meter.toString());
        if (takenOn != null) {
            body.put("takenOn", takenOn);
        }
        body.put("value", value);
        return body;
    }

    private ResultActions submit(String bearer, UUID plot, Map<String, Object> body) throws Exception {
        return send(bearer, post("/api/v1/plots/" + plot + "/readings"), body);
    }

    private ResultActions approve(String bearer, UUID reading) throws Exception {
        return send(bearer, post(READINGS + "/" + reading + "/approve"), null);
    }

    private ResultActions reject(String bearer, UUID reading, String reason) throws Exception {
        return send(bearer, post(READINGS + "/" + reading + "/reject"), Map.of("reason", reason));
    }

    /** The id of the reading a submission saved (201 expected). */
    private UUID id(ResultActions submission) throws Exception {
        return UUID.fromString(body(submission.andExpect(status().isCreated())).get("reading").get("id").asString());
    }

    private JsonNode consumption(String from, String to) throws Exception {
        return body(send(chairman(), get(METERS + "/" + meter + "/consumption").param("from", from).param("to", to),
                null).andExpect(status().isOk()));
    }

    private List<UUID> pendingIds(String bearer) throws Exception {
        List<UUID> ids = new ArrayList<>();
        pendingList(bearer).forEach(item -> ids.add(UUID.fromString(item.get("reading").get("id").asString())));
        return ids;
    }

    private JsonNode pendingItem(String bearer, UUID reading) throws Exception {
        for (JsonNode item : pendingList(bearer)) {
            if (item.get("reading").get("id").asString().equals(reading.toString())) {
                return item;
            }
        }
        throw new AssertionError("The reading is not in the approvals list: " + reading);
    }

    private JsonNode pendingList(String bearer) throws Exception {
        return body(send(bearer, get(READINGS).param("status", "PENDING"), null).andExpect(status().isOk()));
    }

    private void expectConflict(Map<String, Object> body, String code, String field) throws Exception {
        submit(member, plot, body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }

    private void expectReasonProblem(UUID reading, Map<String, Object> body, String code) throws Exception {
        send(chairman(), post(READINGS + "/" + reading + "/reject"), body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }

    private void expectDecisionsDenied(String bearer, UUID reading) throws Exception {
        send(bearer, get(READINGS).param("status", "PENDING"), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        approve(bearer, reading).andExpect(status().isForbidden());
        reject(bearer, reading, "Wrong").andExpect(status().isForbidden());
    }

    /** The intervals of a history as {@code from..to=consumption}, newest first. */
    private static List<String> intervals(JsonNode history) {
        List<String> result = new ArrayList<>();
        history.get("intervals").forEach(interval -> result.add(interval.get("from").asString() + ".."
                + interval.get("to").asString() + "=" + interval.get("consumption").asString()));
        return result;
    }
}
