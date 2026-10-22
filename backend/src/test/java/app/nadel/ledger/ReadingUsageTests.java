package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Feature 011 §2.11: a reading used by a charge that is not reversed can no longer be corrected or deleted (409
 * {@code READING_IN_USE}, the 010 message), and no reading can be put inside a charged interval; after the reversal of
 * the charge the correction is accepted and charges the new interval. A new reading after a charged one is accepted (the
 * span fix: the meters module asks about the days from the day after the previous point).
 */
class ReadingUsageTests extends ReadingChargeSupport {

    @Test
    void aChargedReadingCannotBeCorrectedOrDeleted() throws Exception {
        UUID plot = plot("7", "600");
        electricity();
        UUID meter = electricityMeter(plot, "2026-08-22", "112.4");
        UUID reading = entered(meter, "2026-09-03", "205.7");
        awaitCharges(1);

        send(chairman, put(READINGS + "/" + reading), Map.of("takenOn", "2026-09-03", "value", "205.8"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("READING_IN_USE"));
        send(chairman, delete(READINGS + "/" + reading), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("READING_IN_USE"));
        JsonNode history = body(send(chairman, get(METERS + "/" + meter + "/readings"), null)
                .andExpect(status().isOk()));
        assertThat(history.get("readings").get(0).get("changeable").asBoolean()).isFalse();
    }

    @Test
    void noReadingCanBePutInsideAChargedInterval() throws Exception {
        UUID plot = plot("7", "600");
        electricity();
        UUID meter = electricityMeter(plot, "2026-08-22", "112.4");
        entered(meter, "2026-09-03", "205.7");
        awaitCharges(1);

        JsonNode refused = enter(meter, "2026-08-30", "150");
        assertThat(refused.get("outcome").asString()).isEqualTo("REJECTED");
        assertThat(refused.get("error").get("code").asString()).isEqualTo("READING_IN_USE");
    }

    @Test
    void aNewReadingAfterAChargedOneIsAcceptedAndCharged() throws Exception {
        UUID plot = plot("7", "600");
        electricity();
        UUID meter = electricityMeter(plot, "2026-08-22", "112.4");
        entered(meter, "2026-09-03", "205.7");
        awaitCharges(1);

        UUID next = entered(meter, "2026-09-13", "215.7");
        awaitCharges(2);
        assertThat(readingCharges(plot)).extracting(c -> c.get("amount"))
                .containsExactly("403.06", "43.20");

        // the latest reading is used by its own charge now, the earlier one by both
        send(chairman, put(READINGS + "/" + next), Map.of("takenOn", "2026-09-13", "value", "216"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("READING_IN_USE"));
    }

    @Test
    void afterTheReversalOfItsChargeTheReadingIsCorrectedAndTheNewIntervalCharged() throws Exception {
        UUID plot = plot("7", "600");
        electricity();
        UUID meter = electricityMeter(plot, "2026-08-22", "112.4");
        UUID reading = entered(meter, "2026-09-03", "205.7");
        awaitCharges(1);
        UUID charge = (UUID) readingCharges(plot).getFirst().get("id");

        send(chairman, post(REVERSE.formatted(charge)), Map.of("reason", "Помилковий показ"))
                .andExpect(status().isCreated());
        awaitListeners();
        JsonNode history = body(send(chairman, get(METERS + "/" + meter + "/readings"), null)
                .andExpect(status().isOk()));
        assertThat(history.get("readings").get(0).get("changeable").asBoolean()).isTrue();

        send(chairman, put(READINGS + "/" + reading), Map.of("takenOn", "2026-09-03", "value", "202.4"))
                .andExpect(status().isOk());
        awaitCharges(3);

        // the reversed interval is not re-created; the corrected reading has a new key and is charged: 90 x 4.32
        List<Map<String, Object>> charges = readingCharges(plot);
        assertThat(charges).extracting(c -> c.get("kind"), c -> c.get("amount")).containsExactlyInAnyOrder(
                tuple("REGULAR", "403.06"),
                tuple("REVERSAL", "-403.06"),
                tuple("REGULAR", "388.80"));
    }
}
