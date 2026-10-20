package app.nadel.tariffs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** A tariff and each version added to it are written to the audit log: action, entity, label and fields. */
class TariffAuditTests extends IntegrationTest {

    private static final String API = "/api/v1/tariffs";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @BeforeEach
    @AfterEach
    void restoreTheSeededState() {
        JdbcTemplate owner = ownerJdbc();
        owner.update("delete from tariff_version where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
        owner.update("delete from tariff where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
        // a first sign-in links the demo members to a subject; other tests expect them unlinked (their own subject)
        inTenant(DEMO_TENANT, sql -> sql.update("update member set keycloak_subject = null where email in "
                + "('chairman@demo.local', 'treasurer@demo.local', 'member@demo.local', 'electrician@demo.local')"));
    }

    @Test
    void creatingATariffWritesOneEntryWithTheTariffAndItsFirstVersion() throws Exception {
        UUID tariff = create("For members", version("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "50", null,
                "General meeting"));

        Map<String, Object> entry = entries(tariff, "TARIFF_CREATED").getFirst();
        assertThat(entries(tariff, "TARIFF_CREATED")).hasSize(1);
        assertThat(entry.get("entity_type")).isEqualTo("TARIFF");
        assertThat(entry.get("entity_label")).isEqualTo("Membership fee / For members");
        assertThat(entry.get("actor_member_id")).isNotNull();
        assertThat(entry.get("tenant_id")).isEqualTo(DEMO_TENANT);
        assertThat(changes(entry)).containsExactly(new Change("name", null, "For members"),
                new Change("chargeType", null, "Membership fee"), new Change("validFrom", null, "2027-01-01"),
                new Change("basis", null, "PER_AREA_SOTKA"), new Change("frequency", null, "YEARLY"),
                new Change("rate", null, "50.0000"), new Change("note", null, "General meeting"));
    }

    @Test
    void aOneTimeAmountAuditsItsDueDate() throws Exception {
        UUID tariff = create("Road", version("2026-12-01", "FIXED_ONE_TIME", "ONE_OFF", "1200.5", "2026-12-31",
                null));

        assertThat(changes(entries(tariff, "TARIFF_CREATED").getFirst())).contains(
                new Change("rate", null, "1200.5000"), new Change("dueDate", null, "2026-12-31"))
                .extracting(Change::field).doesNotContain("note");
    }

    @Test
    void addingAVersionWritesOneEntryWithTheVersionFields() throws Exception {
        UUID tariff = create("For members", version("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "50", null, null));

        send(post(API + "/" + tariff + "/versions"), version("2027-07-01", "PER_PLOT", "MONTHLY", "4.32", null,
                "Raised")).andExpect(status().isCreated());

        List<Map<String, Object>> added = awaitEntries(tariff, "TARIFF_VERSION_ADDED", 1);
        Map<String, Object> entry = added.getFirst();
        assertThat(entry.get("entity_type")).isEqualTo("TARIFF");
        assertThat(entry.get("entity_label")).isEqualTo("Membership fee / For members");
        assertThat(entry.get("actor_member_id")).isNotNull();
        assertThat(changes(entry)).containsExactly(new Change("validFrom", null, "2027-07-01"),
                new Change("basis", null, "PER_PLOT"), new Change("frequency", null, "MONTHLY"),
                new Change("rate", null, "4.3200"), new Change("note", null, "Raised"));
        assertThat(entries(tariff, "TARIFF_CREATED")).hasSize(1);
    }

    @Test
    void aRefusedVersionWritesNoEntry() throws Exception {
        UUID tariff = create("For members", version("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "50", null, null));

        send(post(API + "/" + tariff + "/versions"), version("2027-01-01", "PER_PLOT", "MONTHLY", "4", null, null))
                .andExpect(status().isConflict());
        send(post(API + "/" + tariff + "/versions"), version("2027-02-01", "PER_PLOT", "MONTHLY", "4.12345", null,
                null)).andExpect(status().isBadRequest());
        // a following version proves the earlier events would have been written by now
        send(post(API + "/" + tariff + "/versions"), version("2027-03-01", "PER_PLOT", "MONTHLY", "4", null, null))
                .andExpect(status().isCreated());

        List<Map<String, Object>> added = awaitEntries(tariff, "TARIFF_VERSION_ADDED", 1);
        assertThat(changes(added.getFirst())).contains(new Change("validFrom", null, "2027-03-01"));
        assertThat(entries(tariff, "TARIFF_VERSION_ADDED")).hasSize(1);
    }

    // ---- helpers

    private UUID create(String name, Map<String, Object> firstVersion) throws Exception {
        UUID type = ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? and code = "
                + "'MEMBERSHIP'", UUID.class, DEMO_TENANT);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("chargeTypeId", type.toString());
        request.put("name", name);
        request.put("firstVersion", firstVersion);
        String response = send(post(API), request).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        UUID id = UUID.fromString(json.readTree(response).get("id").asString());
        awaitEntries(id, "TARIFF_CREATED", 1);
        return id;
    }

    private static Map<String, Object> version(String validFrom, String basis, String frequency, String rate,
            String dueDate, String note) {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("validFrom", validFrom);
        version.put("basis", basis);
        version.put("frequency", frequency);
        version.put("rate", rate);
        if (dueDate != null) {
            version.put("dueDate", dueDate);
        }
        if (note != null) {
            version.put("note", note);
        }
        return version;
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION,
                TestTokens.bearer(DEMO_TENANT, "sub-chairman@demo.local", "chairman@demo.local"));
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    /** Waits for the asynchronous audit write of {@code expected} entries of that action for the tariff. */
    private List<Map<String, Object>> awaitEntries(UUID tariff, String action, int expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertThat(entries(tariff, action)).hasSize(expected));
        return entries(tariff, action);
    }

    private List<Map<String, Object>> entries(UUID tariff, String action) {
        return ownerJdbc().queryForList("select tenant_id, action, entity_type, entity_label, actor_member_id, "
                + "cast(changes as text) as changes from audit_entry where entity_id = ? and action = ? "
                + "order by occurred_at, id", tariff, action);
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
