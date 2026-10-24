package app.nadel.ledger;

import static org.awaitility.Awaitility.await;

import app.nadel.TestTokens;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.core.ConditionTimeoutException;
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
 * A throw-away cooperative per test for the month run and the "not charged" queue: the run charges every plot of a
 * cooperative, so a cooperative of its own keeps the totals exact and leaves the demo cooperative untouched. It has the
 * seeded roles and charge types, a chairman, a treasurer and a member (tokens), and the plots and tariffs the test
 * creates (as the database owner). After the test the asynchronous listeners (audit, plot status) are given time to
 * finish, then everything of the cooperative is removed; the ledger rows as {@code nadel_owner} with the maintenance
 * setting.
 */
abstract class FreshCooperativeSupport extends LedgerTestSupport {

    static final String RUN = "/api/v1/billing-months/%s/run?preview=%s";
    static final String MONTH_PAGE = "/api/v1/billing-months/%s";
    static final String NOT_CHARGED = "/api/v1/not-charged";
    static final String RETRY = "/api/v1/not-charged/%s/retry";
    static final String ACCOUNT = "/api/v1/plots/%s/account";
    static final String REVERSE = "/api/v1/charges/%s/reverse";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    UUID tenant;
    String chairman;
    String treasurer;
    String member;

    /** A tariff and its first version. */
    record Tariff(UUID id, UUID version) {
    }

    /** SQL as the database owner (superuser, bypasses RLS). */
    static JdbcTemplate owner() {
        return ownerJdbc();
    }

    @BeforeEach
    void createCooperative() {
        tenant = UUID.randomUUID();
        owner().update("insert into tenant (id, name) values (?, 'Run test cooperative')", tenant);
        owner().queryForList("select seed_base_roles(?)", tenant);
        chairman = person("CHAIRMAN");
        treasurer = person("TREASURER");
        member = person("MEMBER");
    }

    @AfterEach
    void removeCooperative() {
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> owner().queryForObject(
                    "select count(*) from event_publication where completion_date is null and serialized_event like ?",
                    Integer.class, "%" + tenant + "%") == 0);
        } catch (ConditionTimeoutException e) {
            // a listener that failed stays incomplete; the cleanup goes on
        }
        UUID id = tenant;
        asLedgerOwner(sql -> {
            sql.update("delete from meter_value_use where tenant_id = ?", id);
            // the lines point at the charges, the adjustments and reversals at their originals, the charges of a
            // recalculation at the recalculation
            sql.update("delete from recalculation_line where tenant_id = ?", id);
            sql.update("delete from charge_part where tenant_id = ?", id);
            sql.update("delete from charge where tenant_id = ? and adjusts_charge_id is not null", id);
            sql.update("delete from charge where tenant_id = ? and reverses_charge_id is not null", id);
            sql.update("delete from charge where tenant_id = ?", id);
            sql.update("delete from recalculation where tenant_id = ?", id);
            sql.update("delete from losses_run_node where tenant_id = ?", id);
            sql.update("delete from losses_run where tenant_id = ?", id);
            sql.update("delete from common_consumer_run_line where tenant_id = ?", id);
            sql.update("delete from common_consumer_run where tenant_id = ?", id);
            sql.update("delete from not_charged_item where tenant_id = ?", id);
            sql.update("delete from billing_month where tenant_id = ?", id);
        });
        // a replacement points at the meter it replaced
        owner().update("delete from meter_reading where tenant_id = ?", id);
        owner().update("delete from meter_parent where tenant_id = ?", id);
        owner().update("delete from meter where tenant_id = ? and replaces_meter_id is not null", id);
        for (String table : List.of("common_consumer_user", "common_consumer_billing", "audit_entry", "plot_status",
                "tariff_scope_plot", "tariff_version", "tariff", "ownership", "plot_water_connection", "meter_reading",
                "meter", "plot", "member_role", "role_permission", "role", "member", "charge_type",
                "cooperative_settings")) {
            owner().update("delete from " + table + " where tenant_id = ?", id);
        }
        owner().update("delete from tenant where id = ?", id);
    }

    /** A member of the cooperative with one base role; returns a token. */
    private String person(String baseRole) {
        UUID id = UUID.randomUUID();
        String email = baseRole.toLowerCase() + "-" + id + "@run.test";
        owner().update("insert into member (id, tenant_id, last_name, first_name, email) values (?, ?, 'Test', ?, ?)",
                id, tenant, baseRole, email);
        owner().update("insert into member_role (tenant_id, member_id, role_id) "
                + "select ?, ?, id from role where tenant_id = ? and base_code = ?", tenant, id, tenant, baseRole);
        return TestTokens.bearer(tenant, "sub-" + email, email);
    }

    // ---- months

    static YearMonth current() {
        return YearMonth.now(ZoneId.of("Europe/Kyiv"));
    }

    static String ym(int monthsAgo) {
        return current().minusMonths(monthsAgo).toString();
    }

    /** Closes the month (YYYY-MM) as the database owner, opening it first. */
    void closeMonth(String month) {
        owner().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month + "-01");
        owner().update("update billing_month set status = 'CLOSED', closed_at = now() where tenant_id = ? "
                + "and month = ?::date", tenant, month + "-01");
    }

    // ---- plots and tariffs

    UUID plot(String number, String areaM2) {
        return plot(number, areaM2, "PLOT", false);
    }

    UUID plot(String number, String areaM2, String kind, boolean archived) {
        UUID id = UUID.randomUUID();
        owner().update("insert into plot (id, tenant_id, number, kind, area_m2, archived_at) "
                + "values (?, ?, ?, ?, ?, case when ? then now() end)", id, tenant, number, kind,
                new BigDecimal(areaM2), archived);
        owner().update("insert into plot_status (plot_id, tenant_id, status) values (?, ?, 'GREY')", id, tenant);
        return id;
    }

    UUID typeId(String code) {
        return owner().queryForObject("select id from charge_type where tenant_id = ? and code = ?", UUID.class,
                tenant, code);
    }

    /** A recurring tariff of the seeded charge type for all plots, with one version. */
    Tariff tariff(String typeCode, String name, String basis, String frequency, String rate, String validFrom) {
        return tariff(typeCode, name, basis, frequency, rate, validFrom, null);
    }

    Tariff tariff(String typeCode, String name, String basis, String frequency, String rate, String validFrom,
            String dueDate) {
        UUID id = UUID.randomUUID();
        owner().update("insert into tariff (id, tenant_id, charge_type_id, name) values (?, ?, ?, ?)", id, tenant,
                typeId(typeCode), name);
        return new Tariff(id, version(id, basis, frequency, rate, validFrom, dueDate));
    }

    UUID version(UUID tariff, String basis, String frequency, String rate, String validFrom, String dueDate) {
        UUID version = UUID.randomUUID();
        owner().update("insert into tariff_version (id, tenant_id, tariff_id, valid_from, basis, frequency, rate, "
                + "due_date) values (?, ?, ?, ?::date, ?, ?, ?, ?::date)", version, tenant, tariff, validFrom, basis,
                frequency, new BigDecimal(rate), dueDate);
        return version;
    }

    /** Makes the tariff apply to the selected plots only. */
    void select(Tariff tariff, UUID... plots) {
        owner().update("update tariff set scope_kind = 'SELECTED_PLOTS' where id = ?", tariff.id());
        for (UUID plot : plots) {
            owner().update("insert into tariff_scope_plot (tenant_id, tariff_id, plot_id, kind) "
                    + "values (?, ?, ?, 'SELECTED')", tenant, tariff.id(), plot);
        }
    }

    void exclude(Tariff tariff, UUID plot) {
        owner().update("insert into tariff_scope_plot (tenant_id, tariff_id, plot_id, kind, reason) "
                + "values (?, ?, ?, 'EXCLUDED', 'Звільнено')", tenant, tariff.id(), plot);
    }

    /** Connects the plot to the cooperative's water supply from {@code from} until {@code until} (null: lasting). */
    void connectToWater(UUID plot, String from, String until) {
        owner().update("insert into plot_water_connection (tenant_id, plot_id, valid_from, valid_to) "
                + "values (?, ?, ?::date, ?::date)", tenant, plot, from, until);
    }

    /** Switches the seeded water charge type on (it starts inactive). */
    void activateWater() {
        owner().update("update charge_type set active = true where id = ?", typeId("WATER"));
    }

    void unselect(Tariff tariff, UUID plot) {
        owner().update("delete from tariff_scope_plot where tariff_id = ? and plot_id = ?", tariff.id(), plot);
    }

    // ---- the ledger as stored

    int chargeCount() {
        return owner().queryForObject("select count(*) from charge where tenant_id = ?", Integer.class, tenant);
    }

    List<Map<String, Object>> charges(UUID plot) {
        return owner().queryForList("select c.id, c.amount::text as amount, c.kind, c.source, c.service_from, "
                + "c.service_to, c.accrued_on, c.source_key, c.tariff_id, c.created_by, m.month as billing_month "
                + "from charge c join billing_month m on m.id = c.billing_month_id where c.plot_id = ? "
                + "order by c.created_at, c.id", plot);
    }

    static LocalDate date(Object value) {
        return value instanceof java.sql.Date sql ? sql.toLocalDate() : LocalDate.parse(value.toString());
    }

    // ---- requests

    ResultActions send(String bearer, MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (bearer != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(body instanceof String text ? text : json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    static Map<String, Object> digest(String digest) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("digest", digest);
        return body;
    }

    /** The total of the charge type (by code) in the totals of a run or a month; null when absent. */
    static String total(JsonNode result, String code) {
        for (JsonNode t : result.get("totals")) {
            if (t.has("chargeTypeCode") && t.get("chargeTypeCode").asString().equals(code)) {
                return t.get("amount").asString();
            }
        }
        return null;
    }
}
