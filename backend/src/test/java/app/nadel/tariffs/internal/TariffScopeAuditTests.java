package app.nadel.tariffs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A change of scope is written to the audit log: the kind and the selected plots as one entry, every exclusion added or
 * removed as its own entry with the plot number and the reason; a save that changes nothing writes nothing.
 */
class TariffScopeAuditTests extends TariffScopeSupport {

    private static final List<String> ACTIONS = List.of("TARIFF_SCOPE_CHANGED", "TARIFF_EXCLUSION_ADDED",
            "TARIFF_EXCLUSION_REMOVED");

    private void save(UUID tariff, Map<String, Object> scope) throws Exception {
        send(chairman(), put(API + "/" + tariff + "/scope"), scope).andExpect(status().isOk());
    }

    @Test
    void switchingToSelectedPlotsWritesOneScopeEntryWithTheSortedPlotNumbers() throws Exception {
        UUID tariff = newTariff("For members");
        UUID ten = newPlot("TA-10", "PLOT", false);
        UUID two = newPlot("TA-2", "PLOT", false);

        save(tariff, selected(ten, two));

        List<Map<String, Object>> entries = await(tariff, "TARIFF_SCOPE_CHANGED", 1);
        Map<String, Object> entry = entries.getFirst();
        assertThat(entry.get("entity_type")).isEqualTo("TARIFF");
        assertThat(entry.get("entity_label")).isEqualTo("For members");
        assertThat(entry.get("actor_member_id")).isNotNull();
        assertThat(entry.get("tenant_id")).isEqualTo(DEMO_TENANT);
        assertThat(changes(entry)).containsExactly(new Change("scope", "ALL_PLOTS", "SELECTED_PLOTS"),
                new Change("selectedPlots", null, "TA-2, TA-10"));
        assertThat(entries(tariff, "TARIFF_EXCLUSION_ADDED")).isEmpty();
        assertThat(entries(tariff, "TARIFF_EXCLUSION_REMOVED")).isEmpty();
    }

    @Test
    void changingOnlyTheSelectedPlotsWritesAScopeEntryWithoutTheKind() throws Exception {
        UUID tariff = newTariff("For members");
        UUID three = newPlot("TA-3", "PLOT", false);
        UUID five = newPlot("TA-5", "PLOT", false);
        UUID seven = newPlot("TA-7", "PLOT", false);
        save(tariff, selected(three, five));
        await(tariff, "TARIFF_SCOPE_CHANGED", 1);

        save(tariff, selected(five, seven));

        List<Map<String, Object>> entries = await(tariff, "TARIFF_SCOPE_CHANGED", 2);
        assertThat(changes(entries.get(1))).containsExactly(new Change("selectedPlots", "TA-3, TA-5", "TA-5, TA-7"));
    }

    @Test
    void anExclusionAddedAndRemovedIsAuditedWithItsPlotAndReason() throws Exception {
        UUID tariff = newTariff("For members");
        UUID seventeen = newPlot("TA-17", "PLOT", false);

        save(tariff, allExcept(seventeen, "  Exempted by the general meeting "));

        List<Map<String, Object>> added = await(tariff, "TARIFF_EXCLUSION_ADDED", 1);
        assertThat(added.getFirst().get("entity_type")).isEqualTo("TARIFF");
        assertThat(added.getFirst().get("entity_label")).isEqualTo("For members");
        assertThat(changes(added.getFirst())).containsExactly(new Change("plot", null, "TA-17"),
                new Change("reason", null, "Exempted by the general meeting"));
        assertThat(entries(tariff, "TARIFF_SCOPE_CHANGED")).as("the kind and selected plots are unchanged").isEmpty();

        save(tariff, Map.of("kind", "ALL_PLOTS"));

        List<Map<String, Object>> removed = await(tariff, "TARIFF_EXCLUSION_REMOVED", 1);
        assertThat(changes(removed.getFirst())).containsExactly(new Change("plot", "TA-17", null),
                new Change("reason", "Exempted by the general meeting", null));
        assertThat(entries(tariff, "TARIFF_SCOPE_CHANGED")).isEmpty();
    }

    @Test
    void aNewReasonIsARemovalPlusAnAddition() throws Exception {
        UUID tariff = newTariff("For members");
        UUID plot = newPlot("TA-1", "PLOT", false);
        save(tariff, allExcept(plot, "First"));
        await(tariff, "TARIFF_EXCLUSION_ADDED", 1);

        save(tariff, allExcept(plot, "Second"));

        List<Map<String, Object>> added = await(tariff, "TARIFF_EXCLUSION_ADDED", 2);
        List<Map<String, Object>> removed = await(tariff, "TARIFF_EXCLUSION_REMOVED", 1);
        assertThat(changes(removed.getFirst())).containsExactly(new Change("plot", "TA-1", null),
                new Change("reason", "First", null));
        assertThat(changes(added.get(1))).containsExactly(new Change("plot", null, "TA-1"),
                new Change("reason", null, "Second"));
    }

    @Test
    void switchingToSelectedPlotsAuditsEachRemovedExclusion() throws Exception {
        UUID tariff = newTariff("For members");
        UUID nine = newPlot("TA-9", "PLOT", false);
        UUID eleven = newPlot("TA-11", "PLOT", false);
        UUID picked = newPlot("TA-2", "PLOT", false);
        save(tariff, allExcept(eleven, "Eleven reason", nine, "Nine reason"));
        await(tariff, "TARIFF_EXCLUSION_ADDED", 2);

        save(tariff, selected(picked));

        List<Map<String, Object>> removed = await(tariff, "TARIFF_EXCLUSION_REMOVED", 2);
        assertThat(removed.stream().map(entry -> changes(entry).getFirst().oldValue()).toList())
                .containsExactlyInAnyOrder("TA-9", "TA-11");
        assertThat(removed.stream().map(entry -> changes(entry).get(1).oldValue()).toList())
                .containsExactlyInAnyOrder("Nine reason", "Eleven reason");
        assertThat(changes(await(tariff, "TARIFF_SCOPE_CHANGED", 1).getFirst())).containsExactly(
                new Change("scope", "ALL_PLOTS", "SELECTED_PLOTS"), new Change("selectedPlots", null, "TA-2"));
    }

    @Test
    void switchingBackToAllPlotsAuditsTheClearedSelection() throws Exception {
        UUID tariff = newTariff("For members");
        UUID plot = newPlot("TA-4", "PLOT", false);
        save(tariff, selected(plot));
        await(tariff, "TARIFF_SCOPE_CHANGED", 1);

        save(tariff, Map.of("kind", "ALL_PLOTS"));

        List<Map<String, Object>> entries = await(tariff, "TARIFF_SCOPE_CHANGED", 2);
        assertThat(changes(entries.get(1))).containsExactly(new Change("scope", "SELECTED_PLOTS", "ALL_PLOTS"),
                new Change("selectedPlots", "TA-4", null));
    }

    @Test
    void aSaveThatChangesNothingWritesNothing() throws Exception {
        UUID tariff = newTariff("For members");
        UUID excluded = newPlot("TA-1", "PLOT", false);
        UUID picked = newPlot("TA-2", "PLOT", false);

        save(tariff, Map.of("kind", "ALL_PLOTS")); // the scope a new tariff has
        save(tariff, allExcept(excluded, "Exempt"));
        save(tariff, allExcept(excluded, " Exempt ")); // the same after trimming
        save(tariff, selected(picked));
        save(tariff, selected(picked));

        // the last real change proves the earlier events would have been written by now
        await(tariff, "TARIFF_SCOPE_CHANGED", 1);
        await(tariff, "TARIFF_EXCLUSION_ADDED", 1);
        await(tariff, "TARIFF_EXCLUSION_REMOVED", 1);
        assertThat(allScopeEntries(tariff)).hasSize(3);
    }

    @Test
    void aRefusedSaveWritesNothing() throws Exception {
        UUID tariff = newTariff("For members");
        UUID road = newPlot("TA-ROAD", "COMMON", false);
        UUID plot = newPlot("TA-1", "PLOT", false);

        send(chairman(), put(API + "/" + tariff + "/scope"), selected(road)).andExpect(status().isBadRequest());
        send(chairman(), put(API + "/" + tariff + "/scope"), allExcept(plot, " ")).andExpect(status().isBadRequest());
        save(tariff, selected(plot));

        await(tariff, "TARIFF_SCOPE_CHANGED", 1);
        assertThat(allScopeEntries(tariff)).hasSize(1);
    }

    // ---- helpers

    /** Waits for the asynchronous audit write of {@code expected} entries of that action for the tariff. */
    private List<Map<String, Object>> await(UUID tariff, String action, int expected) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertThat(entries(tariff, action)).hasSize(expected));
        return entries(tariff, action);
    }

    private List<Map<String, Object>> entries(UUID tariff, String action) {
        return ownerJdbc().queryForList("select tenant_id, action, entity_type, entity_label, actor_member_id, "
                + "cast(changes as text) as changes from audit_entry where entity_id = ? and action = ? "
                + "order by occurred_at, id", tariff, action);
    }

    private List<Map<String, Object>> allScopeEntries(UUID tariff) {
        List<Map<String, Object>> all = new ArrayList<>();
        ACTIONS.forEach(action -> all.addAll(entries(tariff, action)));
        return all;
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
