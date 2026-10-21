package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Registering, correcting, removing and deleting a meter are written to the audit log as codes and parameters: entity
 * type {@code METER}, the name or serial number as the label, enum codes, the plot number, ISO dates and readings with
 * three decimals. A refused change writes nothing.
 */
class MeterAuditTests extends MeterTestSupport {

    private final List<UUID> audited = new ArrayList<>();

    @AfterEach
    void removeAuditEntries() {
        for (UUID meter : audited) {
            ownerJdbc().update("delete from audit_entry where entity_id = ?", meter);
        }
        audited.clear();
    }

    @Test
    void registeringAPlotMeterWritesOneEntryWithEveryField() throws Exception {
        UUID plot = newPlot("MT вул. Садова");
        Map<String, Object> body = plotMeter(plot, "ELECTRICITY", "MT-ЕМ-104523", "2026-06-01", "8000");
        body.put("note", "Seal 77");
        UUID meter = audited(register(body));

        Map<String, Object> entry = awaitEntries(meter, "METER_REGISTERED", 1).getFirst();
        assertThat(entry.get("entity_type")).isEqualTo("METER");
        assertThat(entry.get("entity_label")).isEqualTo("MT-ЕМ-104523");
        assertThat(entry.get("tenant_id")).isEqualTo(DEMO_TENANT);
        assertThat(entry.get("actor_member_id")).isNotNull();
        assertThat(changes(entry)).containsExactly(new Change("kind", null, "PLOT"),
                new Change("resource", null, "ELECTRICITY"), new Change("plot", null, plotNumber(plot)),
                new Change("serial", null, "MT-ЕМ-104523"), new Change("installedOn", null, "2026-06-01"),
                new Change("initialReading", null, "8000.000"), new Change("note", null, "Seal 77"));
    }

    @Test
    void aNamedMeterIsLabelledByItsName() throws Exception {
        UUID meter = audited(register(namedMeter("COMMON_CONSUMER", "MT Свердловина №1", "MT-1", "2026-01-15",
                "100.5")));

        Map<String, Object> entry = awaitEntries(meter, "METER_REGISTERED", 1).getFirst();
        assertThat(entry.get("entity_label")).isEqualTo("MT Свердловина №1");
        assertThat(changes(entry)).containsExactly(new Change("kind", null, "COMMON_CONSUMER"),
                new Change("resource", null, "ELECTRICITY"), new Change("serial", null, "MT-1"),
                new Change("name", null, "MT Свердловина №1"), new Change("installedOn", null, "2026-01-15"),
                new Change("initialReading", null, "100.500"));
    }

    @Test
    void aCorrectionWritesTheChangedFieldsAndANoOpWritesNothing() throws Exception {
        UUID meter = audited(register(namedMeter("QUARTER", "MT Квартал А", "MT-1", "2026-01-15", "0")));

        send(chairman(), put(METERS + "/" + meter), Map.of("serial", "MT-2", "name", "MT Квартал А", "note", "East"))
                .andExpect(status().isOk());
        send(chairman(), put(METERS + "/" + meter), Map.of("serial", "MT-2", "name", "MT Квартал А", "note", "East"))
                .andExpect(status().isOk());
        send(chairman(), put(METERS + "/" + meter), Map.of("serial", "MT-2", "name", "MT Квартал Б", "note", "East"))
                .andExpect(status().isOk());

        List<Map<String, Object>> updates = awaitEntries(meter, "METER_UPDATED", 2);
        assertThat(changes(updates.get(0))).containsExactly(new Change("serial", "MT-1", "MT-2"),
                new Change("note", null, "East"));
        assertThat(changes(updates.get(1))).containsExactly(new Change("name", "MT Квартал А", "MT Квартал Б"));
        assertThat(updates.get(1).get("entity_label")).isEqualTo("MT Квартал Б");
    }

    @Test
    void aRemovalWritesTheDateAndTheFinalReading() throws Exception {
        UUID meter = audited(register(plotMeter(newPlot("MT вул. Садова"), "WATER", "MT-1", "2026-06-01", "12.5")));

        send(chairman(), post(METERS + "/" + meter + "/remove"), Map.of("removedOn", "2026-10-15",
                "finalReading", "20.125")).andExpect(status().isOk());

        Map<String, Object> entry = awaitEntries(meter, "METER_REMOVED", 1).getFirst();
        assertThat(entry.get("entity_label")).isEqualTo("MT-1");
        assertThat(changes(entry)).containsExactly(new Change("removedOn", null, "2026-10-15"),
                new Change("finalReading", null, "20.125"));
    }

    @Test
    void aDeletionWritesTheOldValues() throws Exception {
        UUID plot = newPlot("MT вул. Садова");
        UUID meter = audited(register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "0")));
        awaitEntries(meter, "METER_REGISTERED", 1);

        send(chairman(), delete(METERS + "/" + meter), null).andExpect(status().isNoContent());

        Map<String, Object> entry = awaitEntries(meter, "METER_DELETED", 1).getFirst();
        assertThat(entry.get("entity_label")).isEqualTo("MT-1");
        assertThat(changes(entry)).containsExactly(new Change("kind", "PLOT", null),
                new Change("resource", "ELECTRICITY", null), new Change("plot", plotNumber(plot), null),
                new Change("serial", "MT-1", null), new Change("installedOn", "2026-06-01", null),
                new Change("initialReading", "0.000", null));
    }

    @Test
    void refusedChangesWriteNothing() throws Exception {
        UUID plot = newPlot("MT вул. Садова");
        UUID meter = audited(register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "0")));
        reading(meter, "2026-07-01", "10", "APPROVED");

        String refusedSerial = "MT-refused-" + UUID.randomUUID();
        send(chairman(), post(METERS), plotMeter(plot, "ELECTRICITY", refusedSerial, "2026-06-01", "0"))
                .andExpect(status().isConflict());
        send(chairman(), delete(METERS + "/" + meter), null).andExpect(status().isConflict());
        send(chairman(), post(METERS + "/" + meter + "/remove"), Map.of("removedOn", "2026-08-01",
                "finalReading", "5")).andExpect(status().isConflict());
        // a following change proves the earlier events would have been written by now
        send(chairman(), put(METERS + "/" + meter), Map.of("serial", "MT-3")).andExpect(status().isOk());

        awaitEntries(meter, "METER_UPDATED", 1);
        assertThat(entries(meter, "METER_REGISTERED")).hasSize(1);
        assertThat(entries(meter, "METER_DELETED")).isEmpty();
        assertThat(entries(meter, "METER_REMOVED")).isEmpty();
        assertThat(ownerJdbc().queryForObject("select count(*) from audit_entry where entity_type = 'METER' "
                + "and entity_label = ?", Integer.class, refusedSerial)).isZero();
    }

    // ---- helpers

    private UUID audited(UUID meter) {
        audited.add(meter);
        return meter;
    }

    /** Waits for the asynchronous audit write of {@code expected} entries of that action for the meter. */
    private List<Map<String, Object>> awaitEntries(UUID meter, String action, int expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertThat(entries(meter, action)).hasSize(expected));
        return entries(meter, action);
    }

    private List<Map<String, Object>> entries(UUID meter, String action) {
        return ownerJdbc().queryForList("select tenant_id, action, entity_type, entity_label, actor_member_id, "
                + "cast(changes as text) as changes from audit_entry where entity_id = ? and action = ? "
                + "order by occurred_at, id", meter, action);
    }

    private List<Change> changes(Map<String, Object> entry) {
        JsonNode node = json.readTree((String) entry.get("changes"));
        List<Change> result = new ArrayList<>();
        node.forEach(change -> result.add(new Change(change.get("field").asString(),
                change.has("old") && !change.get("old").isNull() ? change.get("old").asString() : null,
                change.has("new") && !change.get("new").isNull() ? change.get("new").asString() : null)));
        return result;
    }

    private record Change(String field, String oldValue, String newValue) {
    }
}
