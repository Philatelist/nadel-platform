package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** POST /ownerships/{id}/corrections: the correction end to end, every refusal, permissions, status and audit. */
class OwnershipCorrectionTests extends OwnershipTestSupport {

    /** An existing demo member that is archived (Honcharenko, sold plot 9 in 2025). */
    private static final UUID ARCHIVED_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000020");

    private static final String REASON = "Wrong shares in the contract copy";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    // ---- the correction end to end

    @Test
    void halfAndHalfBecomeTwoThirdsAndOneThirdInOneStepAndTheAuditKeepsTheOldValues() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID boris = demoMember("Beta", "Boris");
        UUID annaRow = seedOwner(plot, anna, "1/2", "2020-01-01", null);
        UUID borisRow = seedOwner(plot, boris, "1/2", "2020-01-01", null);

        correct(chairman(), annaRow, anna, "2/3", "2020-01-01", null, "seeded", REASON,
                List.of(coOwner(borisRow, "1/3")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.plotId").value(plot.toString()))
                .andExpect(jsonPath("$.current[*].memberName", contains("Alpha Anna", "Beta Boris")))
                .andExpect(jsonPath("$.current[*].share", contains("2/3", "1/3")))
                .andExpect(jsonPath("$.history", empty()));

        assertThat(ownershipRows(plot)).extracting(row -> row.get("share")).containsExactlyInAnyOrder("2/3", "1/3");
        assertThat(statusOf(plot)).isEqualTo("GREEN");
        // the card shows the corrected shares to a reader as well
        ownerships(treasurer(), plot).andExpect(jsonPath("$.current[*].share", contains("2/3", "1/3")));

        Map<String, Object> entry = awaitAudit(plot, 1).getFirst();
        assertThat(entry.get("action")).isEqualTo("OWNERSHIP_CORRECTED");
        assertThat(entry.get("entity_type")).isEqualTo("OWNERSHIP");
        assertThat(entry.get("entity_label")).isEqualTo(numberOf(plot));
        assertThat(entry.get("actor_member_id")).isNotNull();
        JsonNode changes = json.readTree((String) entry.get("changes"));
        assertThat(changes).hasSize(2);
        // structured parameters, not a display phrase: the client builds the text in the user's language
        assertThat(field(changes, "owners").get("old").asString()).isEqualTo("[{\"member\":\"Alpha Anna\","
                + "\"share\":\"1/2\",\"from\":\"2020-01-01\"},{\"member\":\"Beta Boris\",\"share\":\"1/2\","
                + "\"from\":\"2020-01-01\"}]");
        assertThat(field(changes, "owners").get("new").asString()).isEqualTo("[{\"member\":\"Alpha Anna\","
                + "\"share\":\"2/3\",\"from\":\"2020-01-01\"},{\"member\":\"Beta Boris\",\"share\":\"1/3\","
                + "\"from\":\"2020-01-01\"}]");
        assertThat(isNull(field(changes, "reason"), "old")).isTrue();
        assertThat(field(changes, "reason").get("new").asString()).isEqualTo(REASON);
        // and the audit log lists it under the ownership entity type
        mvc.perform(get("/api/v1/audit?entityType=OWNERSHIP&entityId=" + plot)
                        .header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].action").value("OWNERSHIP_CORRECTED"));
    }

    @Test
    void aShareIsSavedInLowestTermsAndTheDatesAndBasisAreReplaced() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID annaRow = seedOwner(plot, anna, "1/1", "2020-01-01", "2022-12-31");

        correct(chairman(), annaRow, anna, "4/4", "2020-02-01", "2023-01-31", "Court decision", REASON, List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].share").value("1/1"))
                .andExpect(jsonPath("$.history[0].validFrom").value("2020-02-01"))
                .andExpect(jsonPath("$.history[0].validTo").value("2023-01-31"))
                .andExpect(jsonPath("$.history[0].basis").value("Court decision"));

        JsonNode changes = json.readTree((String) awaitAudit(plot, 1).getFirst().get("changes"));
        assertThat(field(changes, "owners").get("old").asString()).isEqualTo("[{\"member\":\"Alpha Anna\","
                + "\"share\":\"1/1\",\"from\":\"2020-01-01\",\"to\":\"2022-12-31\"}]");
        assertThat(field(changes, "owners").get("new").asString()).isEqualTo("[{\"member\":\"Alpha Anna\","
                + "\"share\":\"1/1\",\"from\":\"2020-02-01\",\"to\":\"2023-01-31\"}]");
        assertThat(field(changes, "basis").get("old").asString()).isEqualTo("seeded");
        assertThat(field(changes, "basis").get("new").asString()).isEqualTo("Court decision");
    }

    @Test
    void aBlankBasisClearsIt() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID annaRow = seedOwner(plot, anna, "1/1", "2020-01-01", null);

        correct(chairman(), annaRow, anna, "1/1", "2020-01-01", null, "  ", REASON, List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current[0].basis").doesNotExist());

        JsonNode changes = json.readTree((String) awaitAudit(plot, 1).getFirst().get("changes"));
        assertThat(changes).hasSize(2); // basis and reason: the owners did not change
        assertThat(field(changes, "basis").get("old").asString()).isEqualTo("seeded");
        assertThat(isNull(field(changes, "basis"), "new")).isTrue();
    }

    @Test
    void theMemberOfARecordCanBeChangedToAnotherActiveMember() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID boris = demoMember("Beta", "Boris");
        UUID annaRow = seedOwner(plot, anna, "1/1", "2020-01-01", null);

        correct(chairman(), annaRow, boris, "1/1", "2020-01-01", null, "seeded", "Wrong person recorded", List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current", hasSize(1)))
                .andExpect(jsonPath("$.current[0].memberName").value("Beta Boris"));

        assertThat(ownershipRows(plot)).extracting(row -> row.get("member_id")).containsExactly(boris);
        JsonNode changes = json.readTree((String) awaitAudit(plot, 1).getFirst().get("changes"));
        assertThat(field(changes, "owners").get("old").asString())
                .isEqualTo("[{\"member\":\"Alpha Anna\",\"share\":\"1/1\",\"from\":\"2020-01-01\"}]");
        assertThat(field(changes, "owners").get("new").asString())
                .isEqualTo("[{\"member\":\"Beta Boris\",\"share\":\"1/1\",\"from\":\"2020-01-01\"}]");
    }

    @Test
    void aRecordOfAnArchivedMemberCannotBeMadeCurrentByClearingTheEndDate() throws Exception {
        UUID plot = newPlot();
        UUID record = seedOwner(plot, ARCHIVED_MEMBER, "1/1", "2020-01-01", "2022-12-31");
        List<Map<String, Object>> before = ownershipRows(plot);

        correct(chairman(), record, ARCHIVED_MEMBER, "1/1", "2020-01-01", null, "seeded", REASON, List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_ARCHIVED"))
                .andExpect(jsonPath("$.errors[0].field").value("validTo"))
                .andExpect(jsonPath("$.errors[0].code").value("MEMBER_ARCHIVED"));
        assertThat(ownershipRows(plot)).isEqualTo(before);
        assertThat(statusOf(plot)).isEqualTo("GREY");
    }

    @Test
    void aHistoricalRecordOfAMemberArchivedLaterCanStillBeCorrected() throws Exception {
        UUID plot = demoPlot("9");
        Map<String, Object> row = ownerJdbc().queryForMap("select id, share_numerator || '/' || share_denominator "
                + "as share, valid_from::text as valid_from, valid_to::text as valid_to, basis, updated_at "
                + "from ownership where plot_id = ? and member_id = ?", plot, ARCHIVED_MEMBER);
        UUID id = (UUID) row.get("id");
        try {
            correct(chairman(), id, ARCHIVED_MEMBER, (String) row.get("share"), (String) row.get("valid_from"),
                    (String) row.get("valid_to"), "Sale contract of 2025-05-20", "Basis was missing", List.of())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.history[?(@.id == '" + id + "')].basis").value("Sale contract of 2025-05-20"))
                    .andExpect(jsonPath("$.history[?(@.id == '" + id + "')].memberArchived").value(true));
            assertThat(statusOf(plot)).isEqualTo("GREEN");
            assertThat(awaitAudit(plot, 1).getFirst().get("action")).isEqualTo("OWNERSHIP_CORRECTED");
        } finally {
            ownerJdbc().update("update ownership set basis = ?, updated_at = ? where id = ?", row.get("basis"),
                    row.get("updated_at"), id);
        }
    }

    @Test
    void aCorrectionThatChangesNothingIsAcceptedWithoutAnAuditEntry() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID boris = demoMember("Beta", "Boris");
        UUID annaRow = seedOwner(plot, anna, "1/2", "2020-01-01", null);
        UUID borisRow = seedOwner(plot, boris, "1/2", "2020-01-01", null);
        List<Map<String, Object>> before = ownershipRows(plot);

        // "2/4" is the same share as 1/2, and the co-owner is listed with his current share
        correct(chairman(), annaRow, anna, "2/4", "2020-01-01", null, "seeded", REASON,
                List.of(coOwner(borisRow, "1/2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current[*].share", contains("1/2", "1/2")));

        assertThat(ownershipRows(plot)).isEqualTo(before);
        Thread.sleep(500); // an event, if one were published, would be written within this time
        assertThat(auditEntries(plot)).isEmpty();
    }

    // ---- status

    @Test
    void endingTheOnlyCurrentRecordLeavesThePlotWithoutAnOwnerAndReopeningItGivesTheOwnerBack() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID annaRow = seedOwner(plot, anna, "1/1", "2020-01-01", null);
        assertThat(statusOf(plot)).isEqualTo("GREEN");
        String yesterday = today().minusDays(1).toString();
        String chairman = chairman();

        correct(chairman, annaRow, anna, "1/1", "2020-01-01", yesterday, "seeded", "The sale was not recorded",
                List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current", empty()))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].validTo").value(yesterday));
        assertThat(statusOf(plot)).isEqualTo("GREY");
        card(treasurer(), plot).andExpect(jsonPath("$.status").value("GREY"));

        correct(chairman, annaRow, anna, "1/1", "2020-01-01", null, "seeded", "The sale was recorded by mistake",
                List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current", hasSize(1)));
        assertThat(statusOf(plot)).isEqualTo("GREEN");
        card(treasurer(), plot).andExpect(jsonPath("$.status").value("GREEN"));
    }

    @Test
    void endingOneOfTwoCoOwnersBreaksTheSumAndIsRefused() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID annaRow = seedOwner(plot, anna, "1/2", "2020-01-01", null);
        seedOwner(plot, demoMember("Beta", "Boris"), "1/2", "2020-01-01", null);
        List<Map<String, Object>> before = ownershipRows(plot);

        correct(chairman(), annaRow, anna, "1/2", "2020-01-01", today().minusDays(1).toString(), "seeded", REASON,
                List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHARES_NOT_ONE"));
        assertThat(ownershipRows(plot)).isEqualTo(before);
        assertThat(statusOf(plot)).isEqualTo("GREEN");
    }

    // ---- refusals: the code, the field, and nothing changes

    @Test
    void correctingOnlyOneCoOwnersShareSoThatTheTotalIsNotOneIsRefused() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID annaRow = seedOwner(plot, anna, "1/2", "2020-01-01", null);
        seedOwner(plot, demoMember("Beta", "Boris"), "1/2", "2020-01-01", null);
        List<Map<String, Object>> before = ownershipRows(plot);

        correct(chairman(), annaRow, anna, "2/3", "2020-01-01", null, "seeded", REASON, List.of())
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHARES_NOT_ONE"))
                .andExpect(jsonPath("$.errors[0].field").value("share"))
                .andExpect(jsonPath("$.errors[0].code").value("SHARES_NOT_ONE"));

        assertThat(ownershipRows(plot)).isEqualTo(before);
        Thread.sleep(500);
        assertThat(auditEntries(plot)).isEmpty();
    }

    @Test
    void aMissingOrBlankReasonIsABadRequestOnTheReasonField() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID annaRow = seedOwner(plot, anna, "1/1", "2020-01-01", null);
        String chairman = chairman();
        List<Map<String, Object>> before = ownershipRows(plot);

        Map<String, Object> noReason = body(anna, "1/1", "2020-01-01", null, "changed", null, List.of());
        noReason.remove("reason");
        raw(chairman, annaRow, json.writeValueAsString(noReason)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        for (String reason : List.of("", "   ")) {
            correct(chairman, annaRow, anna, "1/1", "2020-01-01", null, "changed", reason, List.of())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("reason"))
                    .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        }
        correct(chairman, annaRow, anna, "1/1", "2020-01-01", null, "changed", "x".repeat(501), List.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        assertThat(ownershipRows(plot)).isEqualTo(before);
    }

    @Test
    void aStartDateThatOverlapsAnEarlierPeriodOfTheSameMemberIsRefusedOnTheStartDate() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        seedOwner(plot, anna, "1/1", "2020-01-01", "2022-12-31");
        UUID second = seedOwner(plot, anna, "1/1", "2023-01-01", null);
        List<Map<String, Object>> before = ownershipRows(plot);

        correct(chairman(), second, anna, "1/1", "2022-06-01", null, "seeded", REASON, List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNERSHIP_OVERLAP"))
                .andExpect(jsonPath("$.errors[0].field").value("validFrom"))
                .andExpect(jsonPath("$.errors[0].code").value("OWNERSHIP_OVERLAP"));
        assertThat(ownershipRows(plot)).isEqualTo(before);
    }

    @Test
    void anEndDateThatOverlapsALaterPeriodOfTheSameMemberIsRefusedOnTheEndDate() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID first = seedOwner(plot, anna, "1/1", "2020-01-01", "2022-12-31");
        seedOwner(plot, anna, "1/1", "2023-01-01", null);

        correct(chairman(), first, anna, "1/1", "2020-01-01", "2023-06-30", "seeded", REASON, List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNERSHIP_OVERLAP"))
                .andExpect(jsonPath("$.errors[0].field").value("validTo"));
    }

    @Test
    void aStartDateThatOverlapsThePreviousOwnersPeriodIsRefusedOnTheStartDate() throws Exception {
        UUID plot = newPlot();
        UUID boris = demoMember("Beta", "Boris");
        UUID anna = demoMember("Alpha", "Anna");
        seedOwner(plot, boris, "1/1", "2020-01-01", "2022-12-31");
        UUID current = seedOwner(plot, anna, "1/1", "2023-01-01", null);
        List<Map<String, Object>> before = ownershipRows(plot);

        correct(chairman(), current, anna, "1/1", "2022-06-01", null, "seeded", REASON, List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("validFrom"))
                .andExpect(jsonPath("$.errors[0].code").value("OWNERSHIP_OVERLAP"));
        assertThat(ownershipRows(plot)).isEqualTo(before);
    }

    @Test
    void anEndDateMovedForwardIntoTheNextOwnersPeriodIsRefusedOnTheEndDate() throws Exception {
        UUID plot = newPlot();
        UUID boris = demoMember("Beta", "Boris");
        UUID anna = demoMember("Alpha", "Anna");
        UUID previous = seedOwner(plot, boris, "1/1", "2020-01-01", "2022-12-31");
        seedOwner(plot, anna, "1/1", "2023-01-01", null);

        correct(chairman(), previous, boris, "1/1", "2020-01-01", "2023-06-30", "seeded", REASON, List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("validTo"))
                .andExpect(jsonPath("$.errors[0].code").value("OWNERSHIP_OVERLAP"));
    }

    @Test
    void aWrongShareWithUnchangedDatesStaysOnTheShareField() throws Exception {
        UUID plot = newPlot();
        UUID boris = demoMember("Beta", "Boris");
        UUID anna = demoMember("Alpha", "Anna");
        seedOwner(plot, boris, "1/1", "2020-01-01", "2022-12-31");
        UUID current = seedOwner(plot, anna, "1/1", "2023-01-01", null);

        correct(chairman(), current, anna, "1/2", "2023-01-01", null, "seeded", REASON, List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("share"))
                .andExpect(jsonPath("$.errors[0].code").value("SHARES_NOT_ONE"));
    }

    @Test
    void changingTheMemberToOneWhoOwnsInTheSamePeriodIsAnOverlap() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID boris = demoMember("Beta", "Boris");
        seedOwner(plot, anna, "1/2", "2020-01-01", null);
        UUID borisRow = seedOwner(plot, boris, "1/2", "2020-01-01", null);

        correct(chairman(), borisRow, anna, "1/2", "2020-01-01", null, "seeded", REASON, List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNERSHIP_OVERLAP"))
                .andExpect(jsonPath("$.errors[0].field").value("validFrom"));
    }

    @Test
    void datesInTheFutureAreRefusedOnTheirOwnField() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID annaRow = seedOwner(plot, anna, "1/1", "2020-01-01", null);
        String tomorrow = today().plusDays(1).toString();
        String chairman = chairman();
        List<Map<String, Object>> before = ownershipRows(plot);

        correct(chairman, annaRow, anna, "1/1", tomorrow, null, "seeded", REASON, List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNERSHIP_DATE_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("validFrom"))
                .andExpect(jsonPath("$.errors[0].code").value("OWNERSHIP_DATE_INVALID"));
        correct(chairman, annaRow, anna, "1/1", "2020-01-01", tomorrow, "seeded", REASON, List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNERSHIP_DATE_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("validTo"));
        assertThat(ownershipRows(plot)).isEqualTo(before);
    }

    @Test
    void todayIsAllowedAndAnEndBeforeTheStartIsRefused() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID annaRow = seedOwner(plot, anna, "1/1", "2020-01-01", null);
        String chairman = chairman();

        correct(chairman, annaRow, anna, "1/1", "2020-01-01", "2019-12-31", "seeded", REASON, List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNERSHIP_DATE_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("validTo"));
        correct(chairman, annaRow, anna, "1/1", "2020-01-01", today().toString(), "seeded", REASON, List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history[0].validTo").value(today().toString()));
    }

    @Test
    void anArchivedMemberCannotBecomeTheMemberOfARecordButAnUnknownOneIsABadRequest() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID annaRow = seedOwner(plot, anna, "1/1", "2020-01-01", null);
        UUID foreign = newMember(OTHER_TENANT, "Foreign", "Person", null, "foreign-" + UUID.randomUUID() + "@other.local");
        String chairman = chairman();
        List<Map<String, Object>> before = ownershipRows(plot);

        correct(chairman, annaRow, ARCHIVED_MEMBER, "1/1", "2020-01-01", null, "seeded", REASON, List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_ARCHIVED"))
                .andExpect(jsonPath("$.errors[0].field").value("memberId"))
                .andExpect(content().string(not(containsString("Honcharenko"))));
        for (UUID unknown : List.of(UUID.randomUUID(), foreign)) {
            correct(chairman, annaRow, unknown, "1/1", "2020-01-01", null, "seeded", REASON, List.of())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("memberId"))
                    .andExpect(jsonPath("$.errors[0].code").value("MEMBER_NOT_FOUND"))
                    .andExpect(content().string(not(containsString("Foreign"))));
        }
        assertThat(ownershipRows(plot)).isEqualTo(before);
    }

    @Test
    void aCoOwnerOfAnotherPlotTheRecordItselfOrARepeatedOneIsABadRequestOnItsRow() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID boris = demoMember("Beta", "Boris");
        UUID annaRow = seedOwner(plot, anna, "1/2", "2020-01-01", null);
        UUID borisRow = seedOwner(plot, boris, "1/2", "2020-01-01", null);
        UUID otherPlot = newPlot();
        UUID foreignRow = seedOwner(otherPlot, demoMember("Gamma", "Hanna"), "1/1", "2020-01-01", null);
        UUID tenantForeignRow = ownerJdbc().queryForObject(
                "select id from ownership where tenant_id = ? limit 1", UUID.class, OTHER_TENANT);
        String chairman = chairman();
        List<Map<String, Object>> before = ownershipRows(plot);

        correct(chairman, annaRow, anna, "1/2", "2020-01-01", null, "seeded", REASON,
                List.of(coOwner(borisRow, "1/2"), coOwner(foreignRow, "1/2"), coOwner(annaRow, "1/2"),
                        coOwner(tenantForeignRow, "1/2"), coOwner(UUID.randomUUID(), "1/2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(4)))
                .andExpect(jsonPath("$.errors[0].field").value("coOwnerShares[1].ownershipId"))
                .andExpect(jsonPath("$.errors[0].code").value("OWNERSHIP_NOT_FOUND"))
                .andExpect(jsonPath("$.errors[1].field").value("coOwnerShares[2].ownershipId"))
                .andExpect(jsonPath("$.errors[1].code").value("OWNERSHIP_NOT_FOUND"))
                .andExpect(jsonPath("$.errors[2].field").value("coOwnerShares[3].ownershipId"))
                .andExpect(jsonPath("$.errors[3].field").value("coOwnerShares[4].ownershipId"));
        correct(chairman, annaRow, anna, "1/2", "2020-01-01", null, "seeded", REASON,
                List.of(coOwner(borisRow, "1/2"), coOwner(borisRow, "1/2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("coOwnerShares[1].ownershipId"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        assertThat(ownershipRows(plot)).isEqualTo(before);
        assertThat(ownershipRows(otherPlot)).hasSize(1);
    }

    @Test
    void invalidSharesAreABadRequestWithTheFieldOfEachOne() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID annaRow = seedOwner(plot, anna, "1/2", "2020-01-01", null);
        UUID borisRow = seedOwner(plot, demoMember("Beta", "Boris"), "1/2", "2020-01-01", null);
        String chairman = chairman();

        // the shape fits n/d but the bounds do not
        correct(chairman, annaRow, anna, "3/2", "2020-01-01", null, "seeded", REASON,
                List.of(coOwner(borisRow, "1/1001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("share"))
                .andExpect(jsonPath("$.errors[0].code").value("SHARE_INVALID"))
                .andExpect(jsonPath("$.errors[1].field").value("coOwnerShares[0].share"))
                .andExpect(jsonPath("$.errors[1].code").value("SHARE_INVALID"));
        // not a fraction at all: the contract's pattern, the same code
        for (String share : List.of("abc", "0.5", "1/0", "0/2", "-1/2", " 1/2")) {
            correct(chairman, annaRow, anna, share, "2020-01-01", null, "seeded", REASON, List.of())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("share"))
                    .andExpect(jsonPath("$.errors[0].code").value("SHARE_INVALID"));
            correct(chairman, annaRow, anna, "1/2", "2020-01-01", null, "seeded", REASON,
                    List.of(coOwner(borisRow, share)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("coOwnerShares[0].share"))
                    .andExpect(jsonPath("$.errors[0].code").value("SHARE_INVALID"));
        }
    }

    @Test
    void theRequestIsValidatedAtTheBoundary() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID annaRow = seedOwner(plot, anna, "1/1", "2020-01-01", null);
        String chairman = chairman();

        raw(chairman, annaRow, "{\"reason\":\"r\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItems("memberId", "share",
                        "validFrom")));
        raw(chairman, annaRow, "{\"memberId\":\"" + anna + "\",\"share\":\"1/1\",\"validFrom\":\"01.01.2020\","
                + "\"reason\":\"r\"}").andExpect(status().isBadRequest());
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(coOwner(UUID.randomUUID(), "1/21"));
        }
        correct(chairman, annaRow, anna, "1/1", "2020-01-01", null, "seeded", REASON, tooMany)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("coOwnerShares"));
        correct(chairman, annaRow, anna, "1/1", "2020-01-01", null, "x".repeat(501), REASON, List.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("basis"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        raw(chairman, annaRow, "not json").andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/ownerships/not-a-uuid/corrections").header(HttpHeaders.AUTHORIZATION, chairman)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(ownershipRows(plot)).extracting(row -> row.get("share")).containsExactly("1/1");
    }

    @Test
    void anArchivedPlotCannotBeCorrected() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID annaRow = seedOwner(plot, anna, "1/1", "2020-01-01", "2021-12-31");
        archivePlot(plot);

        correct(chairman(), annaRow, anna, "1/1", "2020-01-01", "2022-12-31", "seeded", REASON, List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_ARCHIVED"));
        assertThat(ownershipRows(plot)).extracting(row -> row.get("valid_to")).containsExactly("2021-12-31");
    }

    @Test
    void aRecordThatDoesNotExistOrBelongsToAnotherCooperativeIsNotFound() throws Exception {
        UUID anna = demoMember("Alpha", "Anna");
        UUID foreignRow = ownerJdbc().queryForObject("select id from ownership where tenant_id = ? limit 1", UUID.class,
                OTHER_TENANT);
        Map<String, Object> before = ownerJdbc().queryForMap("select share_numerator, basis, updated_at "
                + "from ownership where id = ?", foreignRow);

        for (UUID id : List.of(foreignRow, UUID.randomUUID())) {
            correct(chairman(), id, anna, "1/1", "2020-01-01", null, "seeded", REASON, List.of())
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        assertThat(ownerJdbc().queryForMap("select share_numerator, basis, updated_at from ownership where id = ?",
                foreignRow)).isEqualTo(before);
    }

    // ---- permissions

    @Test
    void onlyThoseWhoMayViewPlotsAndEditOwnershipCanCorrect() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID annaRow = seedOwner(plot, anna, "1/1", "2020-01-01", null);

        // the treasurer sees plots but has no OWNERSHIP_EDIT; a member has neither; a custom role may have one only
        for (String bearer : List.of(treasurer(), member(), customRoleToken("OWNERSHIP_EDIT"),
                customRoleToken("PLOTS_VIEW", "PLOTS_EDIT", "MEMBERS_VIEW"))) {
            correct(bearer, annaRow, anna, "1/1", "2020-01-01", null, "changed", REASON, List.of())
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        assertThat(ownershipRows(plot)).hasSize(1);
        assertThat(ownerJdbc().queryForObject("select basis from ownership where id = ?", String.class, annaRow))
                .isEqualTo("seeded");
    }

    @Test
    void permissionsDecideNotRoleNames() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID annaRow = seedOwner(plot, anna, "1/1", "2020-01-01", null);

        correct(customRoleToken("PLOTS_VIEW", "OWNERSHIP_EDIT"), annaRow, anna, "1/1", "2020-01-01", null, "changed",
                REASON, List.of())
                .andExpect(status().isOk());
    }

    @Test
    void thePlatformOperatorAndAnAnonymousCallerAreRefused() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID annaRow = seedOwner(plot, anna, "1/1", "2020-01-01", null);
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();
        String body = json.writeValueAsString(body(anna, "1/1", "2020-01-01", null, "changed", REASON, List.of()));

        raw(operator, annaRow, body).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mvc.perform(post("/api/v1/ownerships/" + annaRow + "/corrections").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---- helpers

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Europe/Kyiv"));
    }

    private static Map<String, Object> coOwner(UUID ownershipId, String share) {
        Map<String, Object> coOwner = new LinkedHashMap<>();
        coOwner.put("ownershipId", ownershipId);
        coOwner.put("share", share);
        return coOwner;
    }

    private static Map<String, Object> body(UUID member, String share, String validFrom, String validTo,
            String basis, String reason, List<Map<String, Object>> coOwners) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memberId", member);
        body.put("share", share);
        body.put("validFrom", validFrom);
        if (validTo != null) {
            body.put("validTo", validTo);
        }
        if (basis != null) {
            body.put("basis", basis);
        }
        if (reason != null) {
            body.put("reason", reason);
        }
        body.put("coOwnerShares", coOwners);
        return body;
    }

    private ResultActions correct(String bearer, UUID ownershipId, UUID member, String share, String validFrom,
            String validTo, String basis, String reason, List<Map<String, Object>> coOwners) throws Exception {
        return raw(bearer, ownershipId,
                json.writeValueAsString(body(member, share, validFrom, validTo, basis, reason, coOwners)));
    }

    private ResultActions raw(String bearer, UUID ownershipId, String body) throws Exception {
        return mvc.perform(post("/api/v1/ownerships/" + ownershipId + "/corrections")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions ownerships(String bearer, UUID plot) throws Exception {
        return mvc.perform(get(PLOTS + "/" + plot + "/ownerships").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions card(String bearer, UUID plot) throws Exception {
        return mvc.perform(get(PLOTS + "/" + plot).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private String numberOf(UUID plot) {
        return ownerJdbc().queryForObject("select number from plot where id = ?", String.class, plot);
    }

    /** Waits until the plot has {@code count} audit entries (written asynchronously) and returns them, oldest first. */
    private List<Map<String, Object>> awaitAudit(UUID plot, int count) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(auditEntries(plot)).hasSize(count));
        return auditEntries(plot);
    }

    private List<Map<String, Object>> auditEntries(UUID plot) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForList(
                "select action, actor_member_id, entity_type, entity_label, cast(changes as text) as changes "
                        + "from audit_entry where entity_id = ? order by occurred_at, id", plot));
    }

    /** A value that is absent or null: the stored change keeps an explicit null for an empty side. */
    private static boolean isNull(JsonNode change, String side) {
        return change.get(side) == null || change.get(side).isNull();
    }

    private static JsonNode field(JsonNode changes, String name) {
        for (JsonNode change : changes) {
            if (name.equals(change.get("field").asString())) {
                return change;
            }
        }
        throw new AssertionError("no change for " + name);
    }
}
