package app.nadel.tariffs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.DemoTariffData;
import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every change to a charge type is written to the audit log: action, entity, label and the changed fields. */
class ChargeTypeAuditTests extends IntegrationTest {

    private static final String API = "/api/v1/charge-types";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @BeforeEach
    void startFromTheSeededState() {
        restoreTheSeededState();
    }

    @AfterEach
    void putTheDemoTariffsBack() {
        restoreTheSeededState();
        DemoTariffData.restore(ownerJdbc());
    }

    private void restoreTheSeededState() {
        // no tariffs and no Road repair type here: the demo tariffs are removed and restored after the test
        DemoTariffData.clear(ownerJdbc());
        // a first sign-in links the demo members to a subject; other tests expect them unlinked (their own subject)
        unlinkByEmail(DEMO_TENANT, "chairman@demo.local", "treasurer@demo.local", "member@demo.local",
                "electrician@demo.local");
    }

    @Test
    void creatingATypeWritesOneEntryWithItsFields() throws Exception {
        UUID id = create("Road repair", "Fund for the road");

        Map<String, Object> entry = newEntry(id, "CHARGE_TYPE_CREATED", 0);
        assertThat(entry.get("entity_type")).isEqualTo("CHARGE_TYPE");
        assertThat(entry.get("entity_label")).isEqualTo("Road repair");
        assertThat(entry.get("actor_member_id")).isNotNull();
        assertThat(entry.get("tenant_id")).isEqualTo(DEMO_TENANT);
        List<Change> changes = changes(entry);
        assertThat(changes).containsExactly(new Change("name", null, "Road repair"),
                new Change("description", null, "Fund for the road"), new Change("active", null, "true"));
    }

    @Test
    void changingATypeWritesOnlyTheChangedFields() throws Exception {
        String chairman = chairman();
        UUID water = idOf("WATER");
        int base = entries(water, "CHARGE_TYPE_UPDATED").size();

        update(chairman, water, "Water supply", "For the meters", true).andExpect(status().isOk());

        Map<String, Object> entry = newEntry(water, "CHARGE_TYPE_UPDATED", base);
        assertThat(entry.get("entity_label")).isEqualTo("Water supply");
        assertThat(changes(entry)).containsExactly(new Change("name", "base:WATER", "Water supply"),
                new Change("description", null, "For the meters"), new Change("active", "false", "true"));

        update(chairman, water, "Water supply", null, true).andExpect(status().isOk());
        assertThat(changes(newEntry(water, "CHARGE_TYPE_UPDATED", base + 1)))
                .containsExactly(new Change("description", "For the meters", null));
    }

    @Test
    void anUnrenamedSeededTypeIsAuditedAsATokenNotAsItsEnglishDefaultName() throws Exception {
        String chairman = chairman();
        UUID land = idOf("LAND_TAX");
        int base = entries(land, "CHARGE_TYPE_UPDATED").size();

        // only the description changes: the name stays the standard one and is written as a code
        update(chairman, land, "Land tax", "Paid yearly", true).andExpect(status().isOk());

        Map<String, Object> entry = newEntry(land, "CHARGE_TYPE_UPDATED", base);
        assertThat(entry.get("entity_label")).isEqualTo("base:LAND_TAX");
        assertThat(changes(entry)).containsExactly(new Change("description", null, "Paid yearly"));
    }

    @Test
    void aChangeThatChangesNothingWritesNoEntry() throws Exception {
        String chairman = chairman();
        UUID membership = idOf("MEMBERSHIP");
        // audit_entry is append-only, so earlier tests leave entries for the same (deterministic) ids behind
        int before = auditCount();

        update(chairman, membership, "Membership fee", null, true).andExpect(status().isOk());
        // sending the displayed default name back is not a rename either
        update(chairman, membership, "Членський внесок", null, true).andExpect(status().isOk());
        // a reorder with the current order changes nothing
        List<String> ids = ownerJdbc().queryForList("select id::text from charge_type where tenant_id = ? "
                + "order by position", String.class, DEMO_TENANT);
        send(chairman, put(API + "/order"), Map.of("ids", ids)).andExpect(status().isNoContent());

        Thread.sleep(500); // an event, if one were published, would be written within this time
        assertThat(auditCount()).isEqualTo(before);
    }

    @Test
    void reorderingWritesOneEntryPerTypeWhosePositionChanged() throws Exception {
        String chairman = chairman();
        List<UUID> ids = ownerJdbc().queryForList("select id from charge_type where tenant_id = ? order by position",
                UUID.class, DEMO_TENANT);

        int[] base = ids.stream().mapToInt(id -> entries(id, "CHARGE_TYPE_REORDERED").size()).toArray();

        // land tax and electricity swap places; membership fee, water and common consumers stay where they are
        send(chairman, put(API + "/order"), Map.of("ids", List.of(ids.get(0).toString(), ids.get(2).toString(),
                ids.get(1).toString(), ids.get(3).toString(), ids.get(4).toString())))
                .andExpect(status().isNoContent());

        Map<String, Object> electricity = newEntry(ids.get(1), "CHARGE_TYPE_REORDERED", base[1]);
        assertThat(changes(electricity)).containsExactly(new Change("position", "2", "3"));
        assertThat(electricity.get("entity_label")).isEqualTo("base:ELECTRICITY");
        Map<String, Object> landTax = newEntry(ids.get(2), "CHARGE_TYPE_REORDERED", base[2]);
        assertThat(changes(landTax)).containsExactly(new Change("position", "3", "2"));
        Thread.sleep(500);
        assertThat(entries(ids.get(0), "CHARGE_TYPE_REORDERED")).hasSize(base[0]);
        assertThat(entries(ids.get(3), "CHARGE_TYPE_REORDERED")).hasSize(base[3]);
    }

    @Test
    void archivingWritesAnEntryWithTheArchiveFlag() throws Exception {
        UUID water = idOf("WATER");
        int base = entries(water, "CHARGE_TYPE_ARCHIVED").size();

        send(chairman(), post(API + "/" + water + "/archive"), null).andExpect(status().isNoContent());

        Map<String, Object> entry = newEntry(water, "CHARGE_TYPE_ARCHIVED", base);
        assertThat(changes(entry)).containsExactly(new Change("archived", "false", "true"));
        assertThat(entry.get("entity_label")).isEqualTo("base:WATER");
    }

    @Test
    void theEntriesAreReadableThroughTheAuditEndpoint() throws Exception {
        UUID id = create("Road repair", null);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertThat(entries(id, "CHARGE_TYPE_CREATED")).hasSize(1));

        send(chairman(), get("/api/v1/audit?entityType=CHARGE_TYPE&entityId=" + id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("CHARGE_TYPE_CREATED"))
                .andExpect(jsonPath("$.items[0].entityType").value("CHARGE_TYPE"))
                .andExpect(jsonPath("$.items[0].entityLabel").value("Road repair"))
                .andExpect(jsonPath("$.items[0].changes[0].field").value("name"))
                .andExpect(jsonPath("$.items[0].changes[0].newValue").value("Road repair"));
    }

    @Test
    void aRefusedChangeWritesNoEntry() throws Exception {
        String chairman = chairman();
        UUID road = create("Road repair", null);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertThat(entries(road, "CHARGE_TYPE_CREATED")).hasSize(1));

        int before = auditCount();

        send(chairman, post(API), Map.of("name", "road repair")).andExpect(status().isConflict());
        update(chairman, road, "Вода", null, true).andExpect(status().isConflict());
        update(demo("treasurer@demo.local"), road, "Fence", null, true).andExpect(status().isForbidden());

        Thread.sleep(500); // an event, if one were published, would be written within this time
        assertThat(auditCount()).isEqualTo(before);
        assertThat(entries(road, "CHARGE_TYPE_UPDATED")).isEmpty();
    }

    // ---- helpers

    private record Change(String field, String oldValue, String newValue) {
    }

    private int auditCount() {
        return ownerJdbc().queryForObject("select count(*) from audit_entry where entity_type = 'CHARGE_TYPE' "
                + "and tenant_id = ?", Integer.class, DEMO_TENANT);
    }

    private String chairman() {
        return demo("chairman@demo.local");
    }

    private static String demo(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }

    private UUID idOf(String code) {
        return ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? and code = ?", UUID.class,
                DEMO_TENANT, code);
    }

    private UUID create(String name, String description) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (description != null) {
            body.put("description", description);
        }
        String response = send(chairman(), post(API), body).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asString());
    }

    private ResultActions update(String bearer, UUID id, String name, String description, boolean active)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (description != null) {
            body.put("description", description);
        }
        body.put("active", active);
        return send(bearer, put(API + "/" + id), body);
    }

    private ResultActions send(String bearer, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    /**
     * Waits for the asynchronous audit write and returns the entry of that action for the entity that comes after the
     * {@code base} entries already there: the log is append-only, and the ids of the seeded types are the same in
     * every test, so earlier tests leave entries behind.
     */
    private Map<String, Object> newEntry(UUID entity, String action, int base) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertThat(entries(entity, action)).hasSize(base + 1));
        return entries(entity, action).get(base);
    }

    private List<Map<String, Object>> entries(UUID entity, String action) {
        return ownerJdbc().queryForList("select tenant_id, action, entity_type, entity_label, actor_member_id, "
                + "cast(changes as text) as changes from audit_entry where entity_id = ? and action = ? "
                + "order by occurred_at, id", entity, action);
    }

    private List<Change> changes(Map<String, Object> entry) {
        JsonNode node = json.readTree((String) entry.get("changes"));
        List<Change> result = new ArrayList<>();
        node.forEach(change -> result.add(new Change(change.get("field").asString(),
                change.has("old") && !change.get("old").isNull() ? change.get("old").asString() : null,
                change.has("new") && !change.get("new").isNull() ? change.get("new").asString() : null)));
        return result;
    }
}
