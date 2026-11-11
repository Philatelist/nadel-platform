package app.nadel.notifications.internal.push;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import app.nadel.notifications.FakePushService;
import app.nadel.notifications.TestPushDevice;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.awaitility.core.ConditionTimeoutException;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.states.ScheduledState;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A throw-away cooperative per test for push (feature 021 §2.2): the seeded roles and charge types, requisites, a
 * chairman and a treasurer (tokens), members with tokens, plots with owners and charges of the current month written
 * as the database owner, invoices built through the API, synthetic devices on the in-JVM {@link FakePushService}
 * registered through the API. The JobRunr server is off in these tests: {@link #runQueuedPushes} runs the queued
 * {@link SendPush} jobs of the cooperative as JobRunr would (read back from its storage) and marks them succeeded.
 * After the test everything of the cooperative is removed.
 */
abstract class PushTestSupport extends IntegrationTest {

    static final String DEVICES = "/api/v1/me/push/devices";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    StorageProvider storage;

    @Autowired
    SendPushHandler handler;

    @Autowired
    PushSender sender;

    final FakePushService pushService = FakePushService.shared();

    UUID tenant;
    String chairman;
    String treasurer;
    UUID lastPerson;

    @BeforeAll
    static void createOwnerRole() {
        ownerJdbc().execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nadel_owner') THEN
                        CREATE ROLE nadel_owner NOLOGIN BYPASSRLS;
                    END IF;
                END
                $$""");
        ownerJdbc().execute("GRANT USAGE ON SCHEMA public TO nadel_owner");
    }

    @BeforeEach
    void createCooperative() {
        pushService.reset();
        tenant = UUID.randomUUID();
        ownerJdbc().update("insert into tenant (id, name, edrpou) values (?, 'СТ «Сповіщення»', '12345678')", tenant);
        ownerJdbc().queryForList("select seed_base_roles(?)", tenant);
        ownerJdbc().update("insert into cooperative_settings (tenant_id, official_name, legal_address, bank_name, iban) "
                + "values (?, 'Садівниче товариство «Сповіщення»', 'с. Тестове, вул. Садова, 1', 'АТ «Тестбанк»', "
                + "'UA850000000000000000000123456')", tenant);
        chairman = person("CHAIRMAN", "Бондаренко", "Іван", "board-" + UUID.randomUUID() + "@push.test");
        treasurer = person("TREASURER", "Коваленко", "Олена", "cash-" + UUID.randomUUID() + "@push.test");
    }

    @AfterEach
    void removeCooperative() {
        pushService.reset();
        awaitListeners();
        UUID id = tenant;
        ownerJdbc().update("delete from jobrunr_jobs where jobasjson like ?", "%" + id + "%");
        asLedgerOwner(sql -> {
            sql.update("delete from push_subscription where tenant_id = ?", id);
            sql.update("delete from push_throttle where tenant_id = ?", id);
            sql.update("delete from delivery where tenant_id = ?", id);
            sql.update("delete from allocation where tenant_id = ?", id);
            sql.update("delete from account_movement where tenant_id = ? and reverses_movement_id is not null", id);
            sql.update("delete from account_movement where tenant_id = ?", id);
            sql.update("delete from payment where tenant_id = ? and reverses_payment_id is not null", id);
            sql.update("delete from payment where tenant_id = ?", id);
            sql.update("delete from receipt_counter where tenant_id = ?", id);
            sql.update("delete from charge where tenant_id = ?", id);
            sql.update("delete from invoice where tenant_id = ?", id);
            sql.update("delete from document_counter where tenant_id = ?", id);
            sql.update("delete from billing_month where tenant_id = ?", id);
            sql.update("delete from money_account where tenant_id = ?", id);
            sql.update("delete from expense_category where tenant_id = ?", id);
        });
        for (String table : List.of("audit_entry", "plot_status", "ownership", "plot", "member_role",
                "role_permission", "role", "member", "charge_type", "cooperative_settings")) {
            ownerJdbc().update("delete from " + table + " where tenant_id = ?", id);
        }
        deleteDataKeys(id);
        ownerJdbc().update("delete from tenant where id = ?", id);
    }

    /** Waits until the outbox listeners of the cooperative's events have finished. */
    void awaitListeners() {
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                    "select count(*) from event_publication where completion_date is null and serialized_event like ?",
                    Integer.class, "%" + tenant + "%") == 0);
        } catch (ConditionTimeoutException e) {
            // a listener that failed stays incomplete; the test's own checks tell what is missing
        }
    }

    /** Runs the work as nadel_owner with app.ledger_maintenance = 'on', in one transaction. */
    static void asLedgerOwner(Consumer<JdbcTemplate> work) {
        JdbcTemplate sql = ownerJdbc();
        new TransactionTemplate(new DataSourceTransactionManager(sql.getDataSource())).executeWithoutResult(status -> {
            sql.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO nadel_owner");
            sql.execute("SET LOCAL ROLE nadel_owner");
            sql.execute("SET LOCAL app.ledger_maintenance = 'on'");
            work.accept(sql);
        });
    }

    // ---- people, plots, charges and invoices

    /** A member with one base role and an e-mail (sealed); returns a token. */
    String person(String baseRole, String lastName, String firstName, String email) {
        UUID id = UUID.randomUUID();
        lastPerson = id;
        ownerJdbc().update("insert into member (id, tenant_id, last_name, first_name) values (?, ?, ?, ?)", id, tenant,
                lastName, firstName);
        insertContacts(tenant, id, email);
        ownerJdbc().update("insert into member_role (tenant_id, member_id, role_id) "
                + "select ?, ?, id from role where tenant_id = ? and base_code = ?", tenant, id, tenant, baseRole);
        return TestTokens.bearer(tenant, "sub-" + email, email);
    }

    /** A member (base role MEMBER) with a token; {@link #lastPerson} is its id. */
    String member(String lastName) {
        return person("MEMBER", lastName, "Тест", lastName.toLowerCase() + "-" + UUID.randomUUID() + "@push.test");
    }

    /** A plot on the street Садова with its owners (equal shares). */
    UUID plot(String number, UUID... owners) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, street, kind, area_m2) values (?, ?, ?, 'Садова', "
                + "'PLOT', 600)", id, tenant, number);
        ownerJdbc().update("insert into plot_status (plot_id, tenant_id, status) values (?, ?, 'GREY')", id, tenant);
        for (UUID owner : owners) {
            ownerJdbc().update("insert into ownership (id, tenant_id, plot_id, member_id, share_numerator, "
                    + "share_denominator, valid_from) values (?, ?, ?, ?, 1, ?, date '2020-01-01')", UUID.randomUUID(),
                    tenant, id, owner, owners.length);
        }
        return id;
    }

    /** A membership fee of the plot booked in the current month. */
    void charge(UUID plot, String amount) {
        String month = month().toString();
        ownerJdbc().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month + "-01");
        UUID monthId = ownerJdbc().queryForObject("select id from billing_month where tenant_id = ? and month = "
                + "?::date", UUID.class, tenant, month + "-01");
        UUID type = ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? and code = "
                + "'MEMBERSHIP'", UUID.class, tenant);
        UUID id = UUID.randomUUID();
        LocalDate first = LocalDate.parse(month + "-01");
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, 'REGULAR', "
                + "'TARIFF', ?, ?, ?, ?, ?)", id, tenant, plot, type, monthId, new BigDecimal(amount), first,
                first.withDayOfMonth(first.lengthOfMonth()), first, "TEST:" + id);
    }

    /** The current month in Kyiv. */
    static YearMonth month() {
        return YearMonth.now(ZoneId.of("Europe/Kyiv"));
    }

    /** Builds the month's invoices (only those with something due) as the treasurer; returns the counts. */
    JsonNode buildInvoices() throws Exception {
        ownerJdbc().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month() + "-01");
        return body(send(treasurer, post("/api/v1/billing-months/" + month() + "/invoices?preview=false"),
                Map.of("includeNothingDue", false)).andExpect(status().isOk()));
    }

    /** Issues the plot's invoice of the current month on request as the treasurer. */
    ResultActions issue(UUID plot) throws Exception {
        return send(treasurer, post("/api/v1/plots/" + plot + "/invoices"), Map.of("month", month().toString()));
    }

    // ---- devices and pushes

    /** Registers the device for the member behind the token (201); returns the device's id. */
    UUID register(String bearer, TestPushDevice device, String language) throws Exception {
        JsonNode saved = body(send(bearer, post(DEVICES), device.registration(language, "Телефон"))
                .andExpect(status().isCreated()));
        return UUID.fromString(saved.get("id").asString());
    }

    /** The queued (not scheduled) push jobs of the cooperative, as JobRunr stores them. */
    List<SendPush> queuedPushes() {
        return pushJobs("ENQUEUED").stream().map(PushTestSupport::request).toList();
    }

    /** The scheduled push jobs of the cooperative with the time each is due. */
    List<Map.Entry<SendPush, Instant>> scheduledPushes() {
        return pushJobs("SCHEDULED").stream()
                .map(job -> Map.entry(request(job), ((ScheduledState) job.getJobState()).getScheduledAt()))
                .toList();
    }

    /** Runs every queued push job of the cooperative once, as JobRunr's server would, and marks it succeeded. */
    List<String> runQueuedPushes() {
        return run(pushJobs("ENQUEUED"));
    }

    /** Runs every scheduled push job of the cooperative now (as if its time had come), and marks it succeeded. */
    List<String> runScheduledPushes() {
        return run(pushJobs("SCHEDULED"));
    }

    private List<String> run(List<Job> jobs) {
        List<String> results = new ArrayList<>();
        for (Job job : jobs) {
            // kept as JobRunr keeps a finished job for a while, so its id is still taken
            ownerJdbc().update("update jobrunr_jobs set state = 'SUCCEEDED' where id = ?", job.getId().toString());
            results.add(sender.send(request(job)));
        }
        return results;
    }

    private List<Job> pushJobs(String state) {
        awaitListeners();
        return ownerJdbc().queryForList("select id from jobrunr_jobs where state = ? and jobasjson like ? "
                + "and jobasjson like ? order by createdat, id", String.class, state, "%SendPush%", "%" + tenant + "%")
                .stream().map(id -> storage.getJobById(UUID.fromString(id))).toList();
    }

    private static SendPush request(Job job) {
        return (SendPush) job.getJobDetails().getJobParameters().getFirst().getObject();
    }

    /** The JSON of the cooperative's push jobs as stored. */
    List<String> storedJobs() {
        return ownerJdbc().queryForList("select jobasjson from jobrunr_jobs where jobasjson like ? "
                + "and jobasjson like ?", String.class, "%SendPush%", "%" + tenant + "%");
    }

    /** The cooperative's devices: id, member, failures, last sent. */
    List<Map<String, Object>> devices() {
        return ownerJdbc().queryForList("select id, member_id, consecutive_failures, last_sent_at, server_key_id "
                + "from push_subscription where tenant_id = ? order by created_at, id", tenant);
    }

    // ---- requests

    ResultActions send(String bearer, MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (bearer != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
