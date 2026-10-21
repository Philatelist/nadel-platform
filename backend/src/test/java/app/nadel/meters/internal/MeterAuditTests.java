package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Registering, correcting, removing, replacing and deleting a meter are written to the audit log as codes and parameters: entity
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
    void aReplacementWritesTheReplacementOfTheOldMeterAndTheRegistrationOfTheNewOne() throws Exception {
        UUID plot = newPlot("MT вул. Садова");
        UUID old = audited(register(plotMeter(plot, "ELECTRICITY", "MT-ЕМ-1", "2026-06-01", "8000")));

        UUID replacement = audited(UUID.fromString(body(send(chairman(), post(METERS + "/" + old + "/replace"),
                MeterReplacementTests.replacement("2026-09-20", "8205", "MT-ЕМ-2", "3"))
                .andExpect(status().isCreated())).get("id").asString()));

        Map<String, Object> replaced = awaitEntries(old, "METER_REPLACED", 1).getFirst();
        assertThat(replaced.get("entity_type")).isEqualTo("METER");
        assertThat(replaced.get("entity_label")).isEqualTo("MT-ЕМ-1");
        assertThat(replaced.get("actor_member_id")).isNotNull();
        assertThat(changes(replaced)).containsExactly(new Change("removedOn", null, "2026-09-20"),
                new Change("finalReading", null, "8205.000"), new Change("replacementMeter", null, "MT-ЕМ-2"));
        Map<String, Object> registered = awaitEntries(replacement, "METER_REGISTERED", 1).getFirst();
        assertThat(registered.get("entity_label")).isEqualTo("MT-ЕМ-2");
        assertThat(changes(registered)).containsExactly(new Change("kind", null, "PLOT"),
                new Change("resource", null, "ELECTRICITY"), new Change("plot", null, plotNumber(plot)),
                new Change("serial", null, "MT-ЕМ-2"), new Change("installedOn", null, "2026-09-20"),
                new Change("initialReading", null, "3.000"));
        assertThat(entries(old, "METER_REMOVED")).isEmpty();
    }

    @Test
    void aRefusedReplacementWritesNothing() throws Exception {
        UUID meter = audited(register(plotMeter(newPlot("MT вул. Садова"), "ELECTRICITY", "MT-1", "2026-06-01",
                "100")));

        send(chairman(), post(METERS + "/" + meter + "/replace"),
                MeterReplacementTests.replacement("2026-09-20", "99", "MT-2", "0")).andExpect(status().isConflict());
        // a following change proves the earlier events would have been written by now
        send(chairman(), put(METERS + "/" + meter), Map.of("serial", "MT-3")).andExpect(status().isOk());

        awaitEntries(meter, "METER_UPDATED", 1);
        assertThat(entries(meter, "METER_REPLACED")).isEmpty();
        assertThat(ownerJdbc().queryForObject("select count(*) from audit_entry where entity_type = 'METER' "
                + "and entity_label = 'MT-2'", Integer.class)).isZero();
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
}
