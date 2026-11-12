package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * «Передати показ» checks a reading before it is submitted (feature 021 follow-up): the server says what the
 * submission would meet, with the counted point it is compared with, the reading still waiting for approval, the
 * interval it would close and the warning {@code UNUSUAL_CONSUMPTION} of the submission's own rule, or the rule it
 * would break; nothing is saved or audited; the access is the submission's (a current owner only, the same 404s, 403
 * for the platform operator, 409 for a removed meter, 400 for a malformed value).
 */
class ReadingCheckTests extends MeterTestSupport {

    private static final String STREET = "RC вул. Перевірки";

    private final LocalDate today = kyivToday();
    private UUID plot;
    private UUID meter;
    private String member;

    @BeforeEach
    void ownPlotWithAMeterAndItsHistory() throws Exception {
        plot = newPlot(STREET);
        RoleMember owner = customRoleMember(); // a member without permissions
        own(owner.id(), plot);
        member = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), owner.email());
        meter = register(plotMeter(plot, "ELECTRICITY", "MT-RC-1", day(-270), "2000"));
        // about 2.2 kWh a day for months
        entered(meter, day(-180), "2200");
        entered(meter, day(-90), "2400");
        entered(meter, day(-30), "2553.9");
    }

    @Test
    void aJumpIsWarnedAboutAndNothingIsSaved() throws Exception {
        int readings = readingCount(meter);

        // the member's sample: the previous reading 2553.9, a digit too many typed
        JsonNode check = body(check(member, plot, submission(meter, null, "12331")).andExpect(status().isOk()));

        assertThat(check.get("meterId").asString()).isEqualTo(meter.toString());
        assertThat(check.get("takenOn").asString()).isEqualTo(today.toString());
        assertThat(check.get("previous").get("takenOn").asString()).isEqualTo(day(-30));
        assertThat(check.get("previous").get("value").asString()).isEqualTo("2553.900");
        assertThat(check.get("interval").get("from").asString()).isEqualTo(day(-30));
        assertThat(check.get("interval").get("days").asInt()).isEqualTo(30);
        assertThat(check.get("interval").get("consumption").asString()).isEqualTo("9777.100");
        assertThat(check.get("warnings")).extracting(JsonNode::asString).containsExactly("UNUSUAL_CONSUMPTION");
        assertThat(check.has("problem")).isFalse();
        assertThat(check.has("pending")).isFalse();

        // the submission of the same reading carries the same warning: one rule
        JsonNode saved = body(send(member, post("/api/v1/plots/" + plot + "/readings"), submission(meter, null, "12331"))
                .andExpect(status().isCreated()));
        assertThat(saved.get("warnings")).extracting(JsonNode::asString).containsExactly("UNUSUAL_CONSUMPTION");
        assertThat(readingCount(meter)).isEqualTo(readings + 1);
    }

    @Test
    void anOrdinaryReadingHasNoWarningAndShowsTheReadingWaitingForApproval() throws Exception {
        send(member, post("/api/v1/plots/" + plot + "/readings"), submission(meter, day(-5), "2560"))
                .andExpect(status().isCreated());
        awaitEntries(meter, "READING_SUBMITTED", 1);
        int readings = readingCount(meter);

        JsonNode check = body(check(member, plot, submission(meter, null, "2600")).andExpect(status().isOk()));

        assertThat(check.get("warnings")).isEmpty();
        assertThat(check.get("interval").get("consumption").asString()).isEqualTo("46.100");
        assertThat(check.get("pending").get("takenOn").asString()).isEqualTo(day(-5));
        assertThat(check.get("pending").get("value").asString()).isEqualTo("2560.000");
        // a pending reading is not counted: the value is still compared with the approved one
        assertThat(check.get("previous").get("value").asString()).isEqualTo("2553.900");
        assertThat(readingCount(meter)).isEqualTo(readings);
        assertThat(entries(meter, "READING_SUBMITTED")).hasSize(1);
    }

    @Test
    void aBrokenRuleIsTheProblemTheSubmissionWouldAnswerWithItsParameters() throws Exception {
        JsonNode lower = body(check(member, plot, submission(meter, null, "2500")).andExpect(status().isOk()));
        assertThat(lower.get("problem").get("code").asString()).isEqualTo("READING_OUT_OF_ORDER");
        assertThat(lower.get("problem").get("field").asString()).isEqualTo("value");
        assertThat(lower.get("problem").get("params").get("previousTakenOn").asString()).isEqualTo(day(-30));
        assertThat(lower.get("problem").get("params").get("previousValue").asString()).isEqualTo("2553.900");
        assertThat(lower.get("warnings")).isEmpty();
        assertThat(lower.has("interval")).isFalse();

        JsonNode future = body(check(member, plot, submission(meter, day(1), "2600")).andExpect(status().isOk()));
        assertThat(future.get("problem").get("code").asString()).isEqualTo("READING_DATE_IN_FUTURE");
        assertThat(future.get("problem").get("field").asString()).isEqualTo("takenOn");
        assertThat(future.get("problem").get("params").get("today").asString()).isEqualTo(today.toString());

        JsonNode beforeInstallation = body(check(member, plot, submission(meter, day(-300), "1000"))
                .andExpect(status().isOk()));
        assertThat(beforeInstallation.get("problem").get("code").asString()).isEqualTo("READING_DATE_OUTSIDE_METER");
        assertThat(beforeInstallation.has("previous")).isFalse();
    }

    @Test
    void onlyACurrentOwnerChecksAndTheAnswersAreThoseOfTheSubmission() throws Exception {
        UUID other = newPlot(STREET);
        UUID otherMeter = register(plotMeter(other, "ELECTRICITY", "MT-RC-other", day(-30), "0"));

        check(member, other, submission(otherMeter, null, "10")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        check(member, UUID.randomUUID(), submission(meter, null, "2600")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        check(member, plot, submission(otherMeter, null, "10")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.errors[0].field").value("meterId"));
        check(member, plot, submission(meter, null, "8.1234")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("value"))
                .andExpect(jsonPath("$.errors[0].code").value("METER_VALUE_INVALID"));
        // the board does not own the plot; another cooperative and the platform operator see nothing
        check(chairman(), plot, submission(meter, null, "2600")).andExpect(status().isNotFound());
        check(otherChairman(), plot, submission(meter, null, "2600")).andExpect(status().isNotFound());
        check(operator(), plot, submission(meter, null, "2600")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mvc.perform(post("/api/v1/plots/" + plot + "/reading-submissions/check"))
                .andExpect(status().isUnauthorized());

        // a replaced meter takes no more readings from members
        UUID replacement = UUID.fromString(body(send(chairman(), post(METERS + "/" + meter + "/replace"),
                MeterReplacementTests.replacement(day(-1), "2590", "MT-RC-2", "0"))
                .andExpect(status().isCreated())).get("id").asString());
        check(member, plot, submission(meter, null, "2600")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_REMOVED"));
        JsonNode fresh = body(check(member, plot, submission(replacement, null, "3")).andExpect(status().isOk()));
        assertThat(fresh.get("previous").get("value").asString()).isEqualTo("0.000");
        assertThat(fresh.get("warnings")).isEmpty();
    }

    // ---- helpers

    private String day(int offset) {
        return today.plusDays(offset).toString();
    }

    private static Map<String, Object> submission(UUID meter, String takenOn, String value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("meterId", meter.toString());
        if (takenOn != null) {
            body.put("takenOn", takenOn);
        }
        body.put("value", value);
        return body;
    }

    private ResultActions check(String bearer, UUID plot, Map<String, Object> body) throws Exception {
        return send(bearer, post("/api/v1/plots/" + plot + "/reading-submissions/check"), body);
    }

}
