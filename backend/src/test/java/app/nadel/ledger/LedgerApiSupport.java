package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.nadel.TestTokens;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * What the ledger API tests share: tokens of the demo users, of custom roles and of the other cooperative, throw-away
 * plots, charges written as the database owner (the month run and reading charges arrive with later slices), booked
 * recalculations with their lines, adjustments and reversals written the same way (the booking arrives with feature
 * 012 slice 3), billing months, and a cleanup that removes the recalculations, the charges of the test's plots (as
 * {@code nadel_owner} with the maintenance setting), the billing months the test (or the application) created, the
 * audit entries about the plots, the plots, the ownerships, roles and charge types it created.
 */
abstract class LedgerApiSupport extends LedgerTestSupport {

    static final String ACCOUNT = "/api/v1/plots/%s/account";
    static final String OPENING = "/api/v1/plots/%s/opening-balances";
    static final String REVERSE = "/api/v1/charges/%s/reverse";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private final List<UUID> testPlots = new ArrayList<>();
    private final List<UUID> ownerships = new ArrayList<>();
    private final List<UUID> roles = new ArrayList<>();
    private final List<UUID> chargeTypes = new ArrayList<>();
    private final List<UUID> recalculations = new ArrayList<>();
    private final Set<UUID> monthsBefore = new HashSet<>();

    @BeforeEach
    void rememberMonthsAndUnlinkDemoUsers() {
        monthsBefore.addAll(ownerJdbc().queryForList("select id from billing_month", UUID.class));
        unlinkDemoMembers();
    }

    @AfterEach
    void removeLedgerApiFixtures() {
        String booked = "{" + String.join(",", recalculations.stream().map(UUID::toString).toList()) + "}";
        if (!recalculations.isEmpty()) {
            // the lines point at the charges and the recalculation, the adjustments at their originals
            asLedgerOwner(sql -> sql.update("delete from recalculation_line where recalculation_id = any(?::uuid[])",
                    booked));
        }
        if (!testPlots.isEmpty()) {
            String plots = "{" + String.join(",", testPlots.stream().map(UUID::toString).toList()) + "}";
            asLedgerOwner(sql -> {
                sql.update("delete from charge where plot_id = any(?::uuid[]) and adjusts_charge_id is not null",
                        plots);
                sql.update("delete from charge where plot_id = any(?::uuid[]) and reverses_charge_id is not null",
                        plots);
                sql.update("delete from charge where plot_id = any(?::uuid[])", plots);
            });
        }
        if (!recalculations.isEmpty()) {
            asLedgerOwner(sql -> sql.update("delete from recalculation where id = any(?::uuid[])", booked));
            recalculations.clear();
        }
        JdbcTemplate owner = ownerJdbc();
        List<UUID> created = owner.queryForList("select id from billing_month", UUID.class).stream()
                .filter(month -> !monthsBefore.contains(month)).toList();
        // billing_month refuses DELETE (trigger billing_month_guard, V14) except to the owner in maintenance
        asLedgerOwner(sql -> created.forEach(month -> sql.update("delete from billing_month where id = ?", month)));
        monthsBefore.clear();
        for (UUID ownership : ownerships) {
            owner.update("delete from ownership where id = ?", ownership);
        }
        ownerships.clear();
        for (UUID plot : testPlots) {
            // audit entries are written after the commit; one that arrives after this cleanup stays, harmlessly
            owner.update("delete from audit_entry where entity_id = ?", plot);
            owner.update("delete from plot_status where plot_id = ?", plot);
            owner.update("delete from plot where id = ?", plot);
        }
        testPlots.clear();
        for (UUID type : chargeTypes) {
            owner.update("delete from charge_type where id = ?", type);
        }
        chargeTypes.clear();
        for (UUID role : roles) {
            owner.update("delete from member_role where role_id = ?", role);
            owner.update("delete from role_permission where role_id = ?", role);
            owner.update("delete from role where id = ?", role);
        }
        roles.clear();
        unlinkDemoMembers();
    }

    /** A first sign-in links the demo members to a subject; other tests expect them unlinked (their own subject). */
    private void unlinkDemoMembers() {
        unlinkByEmail(DEMO_TENANT, "chairman@demo.local", "treasurer@demo.local", "member@demo.local",
                "electrician@demo.local");
    }

    // ---- tokens

    static String demo(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }

    static String chairman() {
        return demo("chairman@demo.local");
    }

    static String treasurer() {
        return demo("treasurer@demo.local");
    }

    static String member() {
        return demo("member@demo.local");
    }

    static String operator() {
        return TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();
    }

    UUID demoMember(String email) {
        return memberIdByEmail(DEMO_TENANT, email);
    }

    /** A token of a chairman of the other cooperative (a fresh member; removed after the test). */
    String otherChairman() {
        String email = "ledger-other-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(OTHER_TENANT, email);
        giveBaseRole(OTHER_TENANT, member, "CHAIRMAN");
        return TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), email);
    }

    /** A token of a fresh member of the demo cooperative whose only role is a custom role with the permissions. */
    String customRoleToken(String... permissions) {
        String email = "ledger-custom-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(DEMO_TENANT, email);
        UUID role = UUID.randomUUID();
        roles.add(role);
        inTenant(DEMO_TENANT, sql -> {
            sql.update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, DEMO_TENANT, "Ledger " + role);
            for (String permission : permissions) {
                sql.update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)",
                        DEMO_TENANT, role, permission);
            }
            return sql.update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)",
                    DEMO_TENANT, member, role);
        });
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    // ---- fixtures

    /** A new active plot of the demo cooperative; removed after the test. */
    UUID newPlot() {
        return newPlot(DEMO_TENANT, "PLOT", false);
    }

    UUID newPlot(UUID tenant, String kind, boolean archived) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2, archived_at) "
                + "values (?, ?, ?, ?, ?, case when ? then now() end)", id, tenant, "LA" + id.toString().substring(0, 8),
                kind, new BigDecimal("COMMON".equals(kind) ? "0.00" : "600.00"), archived);
        ownerJdbc().update("insert into plot_status (plot_id, tenant_id, status) values (?, ?, 'GREY')", id, tenant);
        testPlots.add(id);
        return id;
    }

    String plotNumber(UUID plot) {
        return ownerJdbc().queryForObject("select number from plot where id = ?", String.class, plot);
    }

    /** Makes the member an owner of the plot since 2020 (open record); removed after the test. */
    void own(UUID member, UUID plot) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into ownership (id, tenant_id, plot_id, member_id, share_numerator, "
                + "share_denominator, valid_from) values (?, ?, ?, ?, 1, 1, date '2020-01-01')", id, DEMO_TENANT, plot,
                member);
        ownerships.add(id);
    }

    /** The id of a seeded charge type of the cooperative. */
    static UUID typeId(UUID tenant, String code) {
        return ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? and code = ?", UUID.class,
                tenant, code);
    }

    /** A new custom charge type of the demo cooperative, archived or not; removed after the test. */
    UUID customChargeType(boolean archived) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into charge_type (id, tenant_id, name, name_customized, position, archived_at) "
                + "values (?, ?, ?, true, 99, case when ? then now() end)", id, DEMO_TENANT,
                "Ledger " + id.toString().substring(0, 8), archived);
        chargeTypes.add(id);
        return id;
    }

    /** The billing month starting on the first day of {@code month} (YYYY-MM), created OPEN when missing. */
    static UUID month(UUID tenant, String month) {
        ownerJdbc().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month + "-01");
        return ownerJdbc().queryForObject("select id from billing_month where tenant_id = ? and month = ?::date",
                UUID.class, tenant, month + "-01");
    }

    /** Closes the billing month (YYYY-MM) of the cooperative, creating it when missing. */
    static UUID closedMonth(UUID tenant, String month) {
        UUID id = month(tenant, month);
        ownerJdbc().update("update billing_month set status = 'CLOSED', closed_at = now() where id = ?", id);
        return id;
    }

    /**
     * A REGULAR tariff charge of the plot written as the database owner (no calculation stored, as a charge of an older
     * shape would be), for the whole month, accrued on its first day.
     */
    UUID tariffCharge(UUID plot, String typeCode, String amount, String month) {
        UUID id = UUID.randomUUID();
        LocalDate first = LocalDate.parse(month + "-01");
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, 'REGULAR', "
                + "'TARIFF', ?, ?, ?, ?, ?)", id, DEMO_TENANT, plot, typeId(DEMO_TENANT, typeCode),
                month(DEMO_TENANT, month), new BigDecimal(amount), first, first.withDayOfMonth(first.lengthOfMonth()),
                first, "TEST:" + id);
        return id;
    }

    /**
     * A booked recalculation written as the database owner (the booking arrives with feature 012 slice 3); removed
     * after the test. {@code scope} is the stored JSON (ids and codes, with {@code causeType}).
     */
    UUID recalculation(UUID tenant, String cause, String reason, String total, int lines, int plots, UUID createdBy,
            String createdAt, String scope) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into recalculation (id, tenant_id, cause, reason, total, line_count, plot_count, "
                + "digest, created_by, created_at, scope) values (?, ?, ?, ?, ?, ?, ?, 'test-digest', ?, "
                + "?::timestamptz, ?::jsonb)", id, tenant, cause, reason, new BigDecimal(total), lines, plots,
                createdBy, createdAt, scope);
        recalculations.add(id);
        return id;
    }

    /**
     * An adjustment of the charge booked by the recalculation in {@code month} (YYYY-MM): the original's plot, charge
     * type, service period and debt age, source {@code RECALCULATION}, the reason; written as the database owner.
     */
    UUID adjustment(UUID original, UUID recalculation, String amount, String month, String reason) {
        UUID id = UUID.randomUUID();
        UUID tenant = ownerJdbc().queryForObject("select tenant_id from charge where id = ?", UUID.class, original);
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key, adjusts_charge_id, recalculation_id, "
                + "reason) select ?, tenant_id, plot_id, charge_type_id, ?, 'ADJUSTMENT', 'RECALCULATION', ?, "
                + "service_from, service_to, accrued_on, ?, id, ?, ? from charge where id = ?", id,
                month(tenant, month), new BigDecimal(amount), "ADJUSTMENT:" + recalculation + ":" + original,
                recalculation, reason, original);
        return id;
    }

    /** The reversal of the charge booked by the recalculation in the charge's month; written as the database owner. */
    UUID recalculationReversal(UUID original, UUID recalculation, String reason) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, reverses_charge_id, recalculation_id, reason) "
                + "select ?, tenant_id, plot_id, charge_type_id, billing_month_id, 'REVERSAL', 'RECALCULATION', "
                + "-amount, service_from, service_to, accrued_on, id, ?, ? from charge where id = ?", id,
                recalculation, reason, original);
        return id;
    }

    /** A line of a recalculation about one charge; the charge ids it booked may be null. */
    record LineRow(UUID plot, UUID chargeType, String serviceFrom, String serviceTo, UUID original, String booked,
            String adjustedSoFar, String expected, String difference, String action, String reasons, UUID reversal,
            UUID created, UUID adjustment) {
    }

    void recalculationLine(UUID tenant, UUID recalculation, int position, LineRow line) {
        ownerJdbc().update("insert into recalculation_line (tenant_id, recalculation_id, plot_id, charge_type_id, "
                + "service_from, service_to, original_charge_id, booked_amount, adjusted_so_far, expected_amount, "
                + "difference, action, reasons, reversal_charge_id, new_charge_id, adjustment_charge_id, position) "
                + "values (?, ?, ?, ?, ?::date, ?::date, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?)", tenant,
                recalculation, line.plot(), line.chargeType(), line.serviceFrom(), line.serviceTo(), line.original(),
                new BigDecimal(line.booked()), new BigDecimal(line.adjustedSoFar()), new BigDecimal(line.expected()),
                new BigDecimal(line.difference()), line.action(), line.reasons(), line.reversal(), line.created(),
                line.adjustment(), position);
    }

    int chargeCount(UUID plot) {
        return ownerJdbc().queryForObject("select count(*) from charge where plot_id = ?", Integer.class, plot);
    }

    static LocalDate kyivToday() {
        return LocalDate.now(ZoneId.of("Europe/Kyiv"));
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

    static Map<String, Object> opening(UUID chargeType, String amount, String date, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chargeTypeId", chargeType.toString());
        body.put("amount", amount);
        body.put("date", date);
        if (reason != null) {
            body.put("reason", reason);
        }
        return body;
    }

    // ---- audit

    List<Map<String, Object>> awaitEntries(UUID plot, String action, int expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(entries(plot, action)).hasSize(expected));
        return entries(plot, action);
    }

    List<Map<String, Object>> entries(UUID plot, String action) {
        return ownerJdbc().queryForList("select tenant_id, action, entity_type, entity_label, actor_member_id, "
                + "cast(changes as text) as changes from audit_entry where entity_id = ? and action = ? "
                + "order by occurred_at, id", plot, action);
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
}
