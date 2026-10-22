package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.DemoReadingCharges;
import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the meter tests share: tokens of the demo users and of custom roles, throw-away plots, meters registered through
 * the API, and a cleanup that removes every meter of the test (serial numbers start with {@code MT-}), the readings,
 * plots and roles it created. The demo meters (when they exist) are never used. The demo electricity charge type is
 * switched off during a test ({@link DemoReadingCharges}), so the readings it enters are never charged.
 */
abstract class MeterTestSupport extends IntegrationTest {

    static final String METERS = "/api/v1/meters";
    static final String READINGS = "/api/v1/readings";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private final List<UUID> plots = new ArrayList<>();
    private final List<UUID> roles = new ArrayList<>();
    private final List<UUID> ownerships = new ArrayList<>();

    @BeforeEach
    void unlinkDemoUsers() {
        unlinkDemoMembers();
        DemoReadingCharges.pause(ownerJdbc());
    }

    @AfterEach
    void removeMeterFixtures() {
        JdbcTemplate owner = ownerJdbc();
        DemoReadingCharges.resume(owner);
        owner.update("delete from meter_reading where meter_id in (select id from meter where serial like 'MT-%')");
        owner.update("delete from meter_parent where meter_id in (select id from meter where serial like 'MT-%') "
                + "or parent_meter_id in (select id from meter where serial like 'MT-%')");
        // audit entries are written after the commit; one that arrives after this cleanup stays, harmlessly
        owner.update("delete from audit_entry where entity_type in ('METER', 'METER_READING') "
                + "and entity_label like 'MT%'");
        owner.update("delete from meter where serial like 'MT-%' and replaces_meter_id is not null");
        owner.update("delete from meter where serial like 'MT-%'");
        for (UUID ownership : ownerships) {
            owner.update("delete from ownership where id = ?", ownership);
        }
        ownerships.clear();
        for (UUID plot : plots) {
            owner.update("delete from audit_entry where entity_id = ?", plot);
            owner.update("delete from plot_status where plot_id = ?", plot);
            owner.update("delete from plot where id = ?", plot);
        }
        plots.clear();
        for (UUID role : roles) {
            inTenant(DEMO_TENANT, sql -> {
                sql.update("delete from member_role where role_id = ?", role);
                sql.update("delete from role_permission where role_id = ?", role);
                return sql.update("delete from role where id = ?", role);
            });
        }
        roles.clear();
        unlinkDemoMembers();
    }

    /** A first sign-in links the demo members to a subject; other tests expect them unlinked (their own subject). */
    private void unlinkDemoMembers() {
        inTenant(DEMO_TENANT, sql -> sql.update("update member set keycloak_subject = null where email in "
                + "('chairman@demo.local', 'treasurer@demo.local', 'member@demo.local', 'electrician@demo.local')"));
    }

    // ---- tokens

    String chairman() {
        return demo("chairman@demo.local");
    }

    static String demo(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }

    static String operator() {
        return TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();
    }

    /** A token of a chairman of the other cooperative (a fresh member; removed after the test). */
    String otherChairman() {
        String email = "meters-other-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(OTHER_TENANT, email);
        giveBaseRole(OTHER_TENANT, member, "CHAIRMAN");
        return TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), email);
    }

    /** A token of a fresh member of the demo cooperative whose only role is a custom role with the permissions. */
    String customRoleToken(String... permissions) {
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), customRoleMember(permissions).email());
    }

    /** A fresh member of the demo cooperative with a custom role holding the permissions. */
    RoleMember customRoleMember(String... permissions) {
        String email = "meters-custom-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(DEMO_TENANT, email);
        UUID role = UUID.randomUUID();
        roles.add(role);
        inTenant(DEMO_TENANT, sql -> {
            sql.update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, DEMO_TENANT, "Meters " + role);
            for (String permission : permissions) {
                sql.update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)",
                        DEMO_TENANT, role, permission);
            }
            return sql.update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)",
                    DEMO_TENANT, member, role);
        });
        return new RoleMember(member, email);
    }

    record RoleMember(UUID id, String email) {
    }

    // ---- fixtures

    /** A new active plot of the demo cooperative on a street; removed after the test. */
    UUID newPlot(String street) {
        return newPlot(DEMO_TENANT, street, "PLOT", false);
    }

    UUID newPlot(UUID tenant, String street, String kind, boolean archived) {
        UUID id = UUID.randomUUID();
        return newPlot(id, tenant, street, "MT" + id.toString().substring(0, 8), kind, archived);
    }

    /** A new active plot of the demo cooperative with the given number (unique among active plots); removed after. */
    UUID newNumberedPlot(String street, String number) {
        return newPlot(UUID.randomUUID(), DEMO_TENANT, street, number, "PLOT", false);
    }

    private UUID newPlot(UUID id, UUID tenant, String street, String number, String kind, boolean archived) {
        ownerJdbc().update("insert into plot (id, tenant_id, number, street, kind, area_m2, archived_at) "
                + "values (?, ?, ?, ?, ?, ?, case when ? then now() end)", id, tenant, number, street, kind,
                new BigDecimal("COMMON".equals(kind) ? "0.00" : "600.00"), archived);
        ownerJdbc().update("insert into plot_status (plot_id, tenant_id, status) values (?, ?, 'GREY')", id, tenant);
        plots.add(id);
        return id;
    }

    String plotNumber(UUID plot) {
        return ownerJdbc().queryForObject("select number from plot where id = ?", String.class, plot);
    }

    UUID demoPlot(String number) {
        return ownerJdbc().queryForObject("select id from plot where tenant_id = ? and number = ? "
                + "and archived_at is null", UUID.class, DEMO_TENANT, number);
    }

    /** Makes the member the only current owner of the plot since 2020; removed after the test. */
    void own(UUID member, UUID plot) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into ownership (id, tenant_id, plot_id, member_id, share_numerator, "
                + "share_denominator, valid_from) values (?, ?, ?, ?, 1, 1, date '2020-01-01')", id, DEMO_TENANT, plot,
                member);
        ownerships.add(id);
    }

    /** A reading row written as the database owner (the reading commands arrive with a later slice). */
    void reading(UUID meter, String takenOn, String value, String status) {
        ownerJdbc().update("insert into meter_reading (tenant_id, meter_id, taken_on, value, source, status, "
                + "reject_reason) select tenant_id, id, ?::date, ?, 'ENTERED', ?, case when ? = 'REJECTED' "
                + "then 'Wrong' end from meter where id = ?", takenOn, new BigDecimal(value), status, status, meter);
    }

    // ---- requests

    static Map<String, Object> plotMeter(UUID plot, String resource, String serial, String installedOn,
            String initialReading) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "PLOT");
        body.put("resource", resource);
        body.put("plotId", plot.toString());
        body.put("serial", serial);
        body.put("installedOn", installedOn);
        body.put("initialReading", initialReading);
        return body;
    }

    static Map<String, Object> namedMeter(String kind, String name, String serial, String installedOn,
            String initialReading) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("resource", "ELECTRICITY");
        body.put("serial", serial);
        body.put("name", name);
        body.put("installedOn", installedOn);
        body.put("initialReading", initialReading);
        return body;
    }

    /** Registers a meter as the demo chairman and returns its id. */
    UUID register(Map<String, Object> body) throws Exception {
        return UUID.fromString(body(send(chairman(), post(METERS), body).andExpect(status().isCreated()))
                .get("id").asString());
    }

    ResultActions send(String bearer, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(body instanceof String text ? text : json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    // ---- readings

    /** Today in the cooperative's time zone, the day a reading without a date is taken on. */
    static LocalDate kyivToday() {
        return LocalDate.now(ZoneId.of("Europe/Kyiv"));
    }

    /** One row of a batch; a null date is left out (the server takes today). */
    static Map<String, Object> row(UUID meter, String takenOn, String value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("meterId", meter.toString());
        if (takenOn != null) {
            row.put("takenOn", takenOn);
        }
        row.put("value", value);
        return row;
    }

    /** Sends a batch and returns the row results (the answer is 200 whatever the rows' outcome). */
    JsonNode enterRows(String bearer, List<Map<String, Object>> rows) throws Exception {
        return body(send(bearer, post(READINGS), Map.of("rows", rows)).andExpect(status().isOk())).get("rows");
    }

    /** Enters one reading as the demo chairman and returns its row result. */
    JsonNode enter(UUID meter, String takenOn, String value) throws Exception {
        return enterRows(chairman(), List.of(row(meter, takenOn, value))).get(0);
    }

    /** Enters one reading as the demo chairman, expects it saved and returns the id of the new reading. */
    UUID entered(UUID meter, String takenOn, String value) throws Exception {
        JsonNode result = enter(meter, takenOn, value);
        if (!"SAVED".equals(result.get("outcome").asString())) {
            throw new AssertionError("The reading was not saved: " + result);
        }
        return UUID.fromString(result.get("reading").get("id").asString());
    }

    /** The readings and intervals of a meter as the demo chairman sees them. */
    JsonNode history(UUID meter) throws Exception {
        return body(send(chairman(), get(METERS + "/" + meter + "/readings"), null).andExpect(status().isOk()));
    }

    String readingStatus(UUID reading) {
        return ownerJdbc().queryForObject("select status from meter_reading where id = ?", String.class, reading);
    }

    int readingCount(UUID meter) {
        return ownerJdbc().queryForObject("select count(*) from meter_reading where meter_id = ?", Integer.class,
                meter);
    }

    // ---- audit

    /**
     * Waits for the asynchronous audit write of {@code expected} entries of that action about the meter (a change of
     * the meter or of one of its readings).
     */
    List<Map<String, Object>> awaitEntries(UUID meter, String action, int expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertThat(entries(meter, action)).hasSize(expected));
        return entries(meter, action);
    }

    List<Map<String, Object>> entries(UUID meter, String action) {
        return ownerJdbc().queryForList("select tenant_id, action, entity_type, entity_label, actor_member_id, "
                + "cast(changes as text) as changes from audit_entry where entity_id = ? and action = ? "
                + "order by occurred_at, id", meter, action);
    }

    List<Change> changes(Map<String, Object> entry) {
        JsonNode node = json.readTree((String) entry.get("changes"));
        List<Change> result = new ArrayList<>();
        node.forEach(change -> result.add(new Change(change.get("field").asString(),
                change.has("old") && !change.get("old").isNull() ? change.get("old").asString() : null,
                change.has("new") && !change.get("new").isNull() ? change.get("new").asString() : null)));
        return result;
    }

    record Change(String field, String oldValue, String newValue) {
    }

    int meterCount(UUID id) {
        return ownerJdbc().queryForObject("select count(*) from meter where id = ?", Integer.class, id);
    }
}
