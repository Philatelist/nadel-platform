package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Entering, replacing, correcting and deleting a reading are written to the audit log as codes and parameters: entity
 * type {@code METER_READING} about the meter (its id, its serial number or name as the label), {@code takenOn} as an
 * ISO date and {@code value} with three decimals. A refused change writes nothing.
 */
class ReadingAuditTests extends MeterTestSupport {

    private UUID meter;

    @AfterEach
    void removeAuditEntries() {
        if (meter != null) {
            ownerJdbc().update("delete from audit_entry where entity_id = ?", meter);
        }
    }

    @Test
    void enteringAReadingWritesItsDateAndValue() throws Exception {
        meter = register(plotMeter(newPlot("MT вул. Садова"), "ELECTRICITY", "MT-ЕМ-1", "2026-06-01", "8000"));

        entered(meter, "2026-09-03", "8140.5");

        Map<String, Object> entry = awaitEntries(meter, "READING_ENTERED", 1).getFirst();
        assertThat(entry.get("entity_type")).isEqualTo("METER_READING");
        assertThat(entry.get("entity_label")).isEqualTo("MT-ЕМ-1");
        assertThat(entry.get("tenant_id")).isEqualTo(DEMO_TENANT);
        assertThat(entry.get("actor_member_id")).isNotNull();
        assertThat(changes(entry)).containsExactly(new Change("takenOn", null, "2026-09-03"),
                new Change("value", null, "8140.500"));
    }

    @Test
    void aReadingOfTheSameDateWritesTheReplacedValue() throws Exception {
        meter = register(namedMeter("COMMON_CONSUMER", "MT Свердловина №1", "MT-1", "2026-06-01", "0"));
        entered(meter, "2026-10-05", "8205");

        entered(meter, "2026-10-05", "8250");

        Map<String, Object> entry = awaitEntries(meter, "READING_REPLACED", 1).getFirst();
        assertThat(entry.get("entity_label")).isEqualTo("MT Свердловина №1");
        assertThat(changes(entry)).containsExactly(new Change("takenOn", "2026-10-05", "2026-10-05"),
                new Change("value", "8205.000", "8250.000"));
        assertThat(entries(meter, "READING_ENTERED")).hasSize(1);
    }

    @Test
    void aCorrectionWritesTheOldAndTheNewDateAndValue() throws Exception {
        meter = register(plotMeter(newPlot("MT вул. Садова"), "WATER", "MT-1", "2026-06-01", "1"));
        UUID reading = entered(meter, "2026-09-03", "12.125");

        send(chairman(), put(READINGS + "/" + reading), Map.of("takenOn", "2026-09-04", "value", "12.5"))
                .andExpect(status().isOk());

        Map<String, Object> entry = awaitEntries(meter, "READING_CORRECTED", 1).getFirst();
        assertThat(entry.get("entity_type")).isEqualTo("METER_READING");
        assertThat(changes(entry)).containsExactly(new Change("takenOn", "2026-09-03", "2026-09-04"),
                new Change("value", "12.125", "12.500"));
    }

    @Test
    void aDeletionWritesTheOldDateAndValue() throws Exception {
        meter = register(plotMeter(newPlot("MT вул. Садова"), "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        UUID reading = entered(meter, "2026-09-03", "140");

        send(chairman(), delete(READINGS + "/" + reading), null).andExpect(status().isNoContent());

        Map<String, Object> entry = awaitEntries(meter, "READING_DELETED", 1).getFirst();
        assertThat(changes(entry)).containsExactly(new Change("takenOn", "2026-09-03", null),
                new Change("value", "140.000", null));
    }

    @Test
    void refusedAndUnchangedReadingsWriteNothing() throws Exception {
        meter = register(plotMeter(newPlot("MT вул. Садова"), "ELECTRICITY", "MT-1", "2026-06-01", "100"));
        UUID reading = entered(meter, "2026-09-03", "140");

        assertThat(enter(meter, "2026-10-05", "90").get("outcome").asString()).isEqualTo("REJECTED");
        assertThat(enter(meter, kyivToday().plusDays(1).toString(), "200").get("outcome").asString())
                .isEqualTo("REJECTED");
        entered(meter, "2026-09-03", "140"); // the same reading again
        send(chairman(), put(READINGS + "/" + reading), Map.of("takenOn", "2026-09-03", "value", "140"))
                .andExpect(status().isOk());
        send(chairman(), put(READINGS + "/" + reading), Map.of("takenOn", "2026-09-03", "value", "90"))
                .andExpect(status().isConflict());
        // a following change proves the earlier events would have been written by now
        entered(meter, "2026-10-05", "150");

        List<Map<String, Object>> entered = awaitEntries(meter, "READING_ENTERED", 2);
        assertThat(changes(entered.get(1))).containsExactly(new Change("takenOn", null, "2026-10-05"),
                new Change("value", null, "150.000"));
        assertThat(entries(meter, "READING_REPLACED")).isEmpty();
        assertThat(entries(meter, "READING_CORRECTED")).isEmpty();
    }
}
