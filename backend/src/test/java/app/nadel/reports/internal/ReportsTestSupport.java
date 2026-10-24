package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A throw-away cooperative per test for the reports, with a fixed ledger of worked numbers written as the database
 * owner (the same ledger as {@code ledger.LedgerReadModelTests}), so the totals are exact and the demo cooperative is
 * left untouched:
 * <pre>
 * January 2025 (closed)   P1 membership 30.00, P2 membership 30.00, P1 opening balance 1500.00 (membership, dated
 *                         01.01.2025), P2 electricity 52.50 for 16.12.2024-15.01.2025
 * February 2025 (closed)  P1 membership 30.00, P2 membership 30.00 and its reversal -30.00
 * March 2025 (open)       P1 membership 25.00, P2 membership 25.00, adjustments of P1's January and February
 *                         membership -5.00 each (booked in March, service periods of January and February)
 * </pre>
 * P1 is on "Лісова", P2 on "Садова". It has the seeded roles and charge types and a chairman, a treasurer and a member
 * (tokens). After the test the asynchronous listeners (audit) are given time to finish, then everything of the
 * cooperative is removed; the ledger rows as {@code nadel_owner} with the maintenance setting.
 */
abstract class ReportsTestSupport extends IntegrationTest {

    static final String COOPERATIVE = "СТ «Звіти»";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    UUID tenant;
    String chairman;
    String treasurer;
    String member;
    UUID p1;
    UUID p2;
    UUID membership;
    UUID electricity;

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
        tenant = UUID.randomUUID();
        ownerJdbc().update("insert into tenant (id, name) values (?, ?)", tenant, COOPERATIVE);
        ownerJdbc().queryForList("select seed_base_roles(?)", tenant);
        chairman = person("CHAIRMAN");
        treasurer = person("TREASURER");
        member = person("MEMBER");
    }

    @AfterEach
    void removeCooperative() {
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                    "select count(*) from event_publication where completion_date is null and serialized_event like ?",
                    Integer.class, "%" + tenant + "%") == 0);
        } catch (ConditionTimeoutException e) {
            // a listener that failed stays incomplete; the cleanup goes on
        }
        UUID id = tenant;
        asLedgerOwner(sql -> {
            sql.update("delete from charge where tenant_id = ? and adjusts_charge_id is not null", id);
            sql.update("delete from charge where tenant_id = ? and reverses_charge_id is not null", id);
            sql.update("delete from charge where tenant_id = ?", id);
            sql.update("delete from recalculation where tenant_id = ?", id);
            sql.update("delete from billing_month where tenant_id = ?", id);
        });
        for (String table : List.of("audit_entry", "plot_status", "plot", "member_role", "role_permission", "role",
                "member", "charge_type", "cooperative_settings")) {
            ownerJdbc().update("delete from " + table + " where tenant_id = ?", id);
        }
        ownerJdbc().update("delete from tenant where id = ?", id);
    }

    /** Books the worked ledger of the class comment. */
    void bookTheLedger() {
        p1 = plot("1", "Лісова");
        p2 = plot("2", "Садова");
        membership = typeId("MEMBERSHIP");
        electricity = typeId("ELECTRICITY");

        UUID janP1 = charge(p1, membership, "REGULAR", "TARIFF", "30.00", "2025-01-01", "2025-01-31", "2025-01");
        charge(p2, membership, "REGULAR", "TARIFF", "30.00", "2025-01-01", "2025-01-31", "2025-01");
        charge(p1, membership, "OPENING", "OPENING", "1500.00", "2025-01-01", "2025-01-01", "2025-01");
        charge(p2, electricity, "REGULAR", "CONSUMPTION", "52.50", "2024-12-16", "2025-01-15", "2025-01");

        UUID febP1 = charge(p1, membership, "REGULAR", "TARIFF", "30.00", "2025-02-01", "2025-02-28", "2025-02");
        UUID febP2 = charge(p2, membership, "REGULAR", "TARIFF", "30.00", "2025-02-01", "2025-02-28", "2025-02");
        reversal(febP2);

        charge(p1, membership, "REGULAR", "TARIFF", "25.00", "2025-03-01", "2025-03-31", "2025-03");
        charge(p2, membership, "REGULAR", "TARIFF", "25.00", "2025-03-01", "2025-03-31", "2025-03");
        UUID recalculation = recalculation();
        adjustment(janP1, recalculation, "-5.00", "2025-03");
        adjustment(febP1, recalculation, "-5.00", "2025-03");

        closeMonth("2025-01");
        closeMonth("2025-02");
    }

    // ---- requests

    ResultActions send(String bearer, MockHttpServletRequestBuilder request) throws Exception {
        if (bearer != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        return mvc.perform(request);
    }

    JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    // ---- audit

    /** The {@code REPORT_GENERATED} entries of the cooperative, oldest first, once {@code expected} are written. */
    List<Map<String, Object>> awaitReportEntries(int expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(reportEntries()).hasSize(expected));
        return reportEntries();
    }

    List<Map<String, Object>> reportEntries() {
        return ownerJdbc().queryForList("select entity_type, entity_id, entity_label, actor_member_id, "
                + "cast(changes as text) as changes from audit_entry where tenant_id = ? and action = "
                + "'REPORT_GENERATED' order by occurred_at, id", tenant);
    }

    /** No publication of the cooperative is still owed: every audit entry that will be written is written. */
    void awaitOutbox() {
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from event_publication where completion_date is null and serialized_event like ?",
                Integer.class, "%" + tenant + "%") == 0);
    }

    /** The changes of an entry as {@code field=new} (the entries of a report have new values only). */
    List<String> changes(Map<String, Object> entry) {
        JsonNode node = json.readTree((String) entry.get("changes"));
        List<String> result = new ArrayList<>();
        node.forEach(change -> {
            assertThat(change.has("old") && !change.get("old").isNull()).isFalse();
            result.add(change.get("field").asString() + "=" + change.get("new").asString());
        });
        return result;
    }

    // ---- fixture

    UUID memberId(String bearerRole) {
        return ownerJdbc().queryForObject("select m.id from member m join member_role mr on mr.member_id = m.id "
                + "join role r on r.id = mr.role_id where m.tenant_id = ? and r.base_code = ?", UUID.class, tenant,
                bearerRole);
    }

    UUID plot(String number, String street) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, street, kind, area_m2) "
                + "values (?, ?, ?, ?, 'PLOT', 600)", id, tenant, number, street);
        ownerJdbc().update("insert into plot_status (plot_id, tenant_id, status) values (?, ?, 'GREY')", id, tenant);
        return id;
    }

    UUID typeId(String code) {
        return ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? and code = ?", UUID.class,
                tenant, code);
    }

    /** Closes the month (YYYY-MM) as the database owner, opening it first. */
    void closeMonth(String month) {
        monthRow(month);
        ownerJdbc().update("update billing_month set status = 'CLOSED', closed_at = now() where tenant_id = ? "
                + "and month = ?::date", tenant, month + "-01");
    }

    UUID monthRow(String month) {
        ownerJdbc().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month + "-01");
        return ownerJdbc().queryForObject("select id from billing_month where tenant_id = ? and month = ?::date",
                UUID.class, tenant, month + "-01");
    }

    /** A charge of the kind written as the database owner, accrued on its first service day. */
    UUID charge(UUID plot, UUID type, String kind, String source, String amount, String from, String to,
            String bookedIn) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, ?, ?, ?, "
                + "?::date, ?::date, ?::date, ?)", id, tenant, plot, type, monthRow(bookedIn), kind, source,
                new BigDecimal(amount), from, to, from, "TEST:" + id);
        return id;
    }

    /** A reversal by hand in the original's month: the opposite amount, the same service period. */
    void reversal(UUID original) {
        ownerJdbc().update("insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, reverses_charge_id, reason) select tenant_id, "
                + "plot_id, charge_type_id, billing_month_id, 'REVERSAL', 'MANUAL_REVERSAL', -amount, service_from, "
                + "service_to, accrued_on, id, 'Помилкове нарахування' from charge where id = ?", original);
    }

    UUID recalculation() {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into recalculation (id, tenant_id, cause, reason, total, line_count, plot_count, "
                + "digest) values (?, ?, 'MANUAL', 'Виправлено ставку', -10.00, 2, 1, 'test-digest')", id, tenant);
        return id;
    }

    /** An adjustment of the charge booked in {@code bookedIn}, with the original's service period and debt date. */
    void adjustment(UUID original, UUID recalculation, String amount, String bookedIn) {
        ownerJdbc().update("insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key, adjusts_charge_id, recalculation_id, "
                + "reason) select tenant_id, plot_id, charge_type_id, ?, 'ADJUSTMENT', 'RECALCULATION', ?, "
                + "service_from, service_to, accrued_on, ?, id, ?, 'Виправлено ставку' from charge where id = ?",
                monthRow(bookedIn), new BigDecimal(amount), "ADJUSTMENT:" + recalculation + ":" + original,
                recalculation, original);
    }

    /** A member of the cooperative with one base role; returns a token. */
    private String person(String baseRole) {
        UUID id = UUID.randomUUID();
        String email = baseRole.toLowerCase() + "-" + id + "@reports.test";
        ownerJdbc().update("insert into member (id, tenant_id, last_name, first_name, email) "
                + "values (?, ?, 'Звітний', ?, ?)", id, tenant, baseRole, email);
        ownerJdbc().update("insert into member_role (tenant_id, member_id, role_id) "
                + "select ?, ?, id from role where tenant_id = ? and base_code = ?", tenant, id, tenant, baseRole);
        return TestTokens.bearer(tenant, "sub-" + email, email);
    }

    /** Runs the work as nadel_owner with app.ledger_maintenance = 'on', in one transaction. */
    private static void asLedgerOwner(Consumer<JdbcTemplate> work) {
        JdbcTemplate sql = ownerJdbc();
        new TransactionTemplate(new DataSourceTransactionManager(sql.getDataSource())).executeWithoutResult(status -> {
            sql.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO nadel_owner");
            sql.execute("SET LOCAL ROLE nadel_owner");
            sql.execute("SET LOCAL app.ledger_maintenance = 'on'");
            work.accept(sql);
        });
    }
}
