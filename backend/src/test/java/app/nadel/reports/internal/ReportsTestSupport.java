package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
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
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
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
    static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

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
    LocalDate today;
    UUID b1;
    UUID b2;
    UUID b3;
    UUID b10;
    UUID b11;

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
        removeCooperative(tenant);
    }

    /** Removes everything of the cooperative, once its owed audit entries are written. */
    static void removeCooperative(UUID id) {
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                    "select count(*) from event_publication where completion_date is null and serialized_event like ?",
                    Integer.class, "%" + id + "%") == 0);
        } catch (ConditionTimeoutException e) {
            // a listener that failed stays incomplete; the cleanup goes on
        }
        asLedgerOwner(sql -> {
            sql.update("delete from report_template_version where tenant_id = ?", id);
            // payments (feature 015): releases point at their allocation, reversals at their payment
            sql.update("delete from allocation where tenant_id = ? and releases_allocation_id is not null", id);
            sql.update("delete from allocation where tenant_id = ?", id);
            sql.update("delete from payment where tenant_id = ? and reverses_payment_id is not null", id);
            sql.update("delete from payment where tenant_id = ?", id);
            sql.update("delete from meter_value_use where tenant_id = ?", id);
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
        ownerJdbc().update("delete from meter_reading where tenant_id = ?", id);
        ownerJdbc().update("delete from plot_water_connection where tenant_id = ?", id);
        ownerJdbc().update("delete from meter_parent where tenant_id = ?", id);
        ownerJdbc().update("delete from meter where tenant_id = ? and replaces_meter_id is not null", id);
        for (String table : List.of("common_consumer_user", "common_consumer_billing", "meter", "audit_entry",
                "ownership", "plot_status", "tariff_scope_plot", "tariff_version", "tariff", "plot", "member_role",
                "role_permission", "role", "report_template", "member", "charge_type", "cooperative_settings")) {
            ownerJdbc().update("delete from " + table + " where tenant_id = ?", id);
        }
        deleteDataKeys(id);
        ownerJdbc().update("delete from tenant where id = ?", id);
    }

    /**
     * The worked balances of plot balances and debtors, every day counted back from today in Kyiv ({@code T}):
     * <pre>
     * plot  street  current owners                       charges (debt date)                    today: debt, oldest, status
     * 1     Лісова  Шевченко Тарас Григорович (2 phones,  membership 600.00 (T-120),             652.50, T-120, 120 days, RED
     *               e-mail), Коваль Марія (1 phone);      electricity 52.50 (T-30)               (older than 90 days)
     *               Колишній Петро owned it until 2019
     * 2     Садова  Бондар Олена (phone, e-mail)          membership 1500.00 (T-10)              1500.00, T-10, 10 days, RED
     * 3     Садова  Мельник Іван (phone)                  membership 300.00 (T-20)               300.00, T-20, 20 days, YELLOW
     * 10    Лісова  Ткач Ганна (e-mail)                   membership 200.00 (T-200), reversed    0.00, none, GREEN
     * 11    Лісова  none                                  membership 100.00 (T-5)                100.00, T-5, 5 days, GREY
     * Д-1   Лісова  common land                           membership 999.00 (T-100)              not listed
     * 4     Лісова  archived                              membership 5000.00 (T-100)             not listed
     * </pre>
     * The {@code plot_status} cache holds today's debts and oldest dates, as the ledger would have rebuilt it. The
     * thresholds are the defaults, 1000.00 and 90 days.
     */
    void bookTheBalances() {
        today = LocalDate.now(KYIV);
        b1 = plot("1", "Лісова");
        b2 = plot("2", "Садова");
        b3 = plot("3", "Садова");
        b10 = plot("10", "Лісова");
        b11 = plot("11", "Лісова");
        UUID common = plot("Д-1", "Лісова");
        ownerJdbc().update("update plot set kind = 'COMMON' where id = ?", common);
        UUID archived = plot("4", "Лісова");
        ownerJdbc().update("update plot set archived_at = now() where id = ?", archived);
        membership = typeId("MEMBERSHIP");
        electricity = typeId("ELECTRICITY");

        owner(b1, "Шевченко", "Тарас", "Григорович", "{+380501112233,+380671112233}", "taras@reports.test",
                "2020-01-01", null);
        owner(b1, "Коваль", "Марія", null, "{+380631112233}", null, "2021-05-01", null);
        owner(b1, "Колишній", "Петро", null, "{+380991112233}", "former@reports.test", "2015-01-01", "2019-12-31");
        owner(b2, "Бондар", "Олена", null, "{+380441112233}", "olena@reports.test", "2019-01-01", null);
        owner(b3, "Мельник", "Іван", null, "{+380931112233}", null, "2019-01-01", null);
        owner(b10, "Ткач", "Ганна", null, "{}", "hanna@reports.test", "2019-01-01", null);

        dayCharge(b1, membership, "600.00", 120);
        dayCharge(b1, electricity, "52.50", 30);
        dayCharge(b2, membership, "1500.00", 10);
        dayCharge(b3, membership, "300.00", 20);
        reversal(dayCharge(b10, membership, "200.00", 200));
        dayCharge(b11, membership, "100.00", 5);
        dayCharge(common, membership, "999.00", 100);
        dayCharge(archived, membership, "5000.00", 100);

        cacheDebt(b1, "652.50", today.minusDays(120));
        cacheDebt(b2, "1500.00", today.minusDays(10));
        cacheDebt(b3, "300.00", today.minusDays(20));
        cacheDebt(b10, "0.00", null);
        cacheDebt(b11, "100.00", today.minusDays(5));
        cacheDebt(common, "999.00", today.minusDays(100));
        cacheDebt(archived, "5000.00", today.minusDays(100));
    }

    /** A one-day charge dated {@code daysAgo} days before today, booked in the month of that day. */
    UUID dayCharge(UUID plot, UUID type, String amount, int daysAgo) {
        String day = today.minusDays(daysAgo).toString();
        return charge(plot, type, "REGULAR", "TARIFF", amount, day, day, YearMonth.from(today.minusDays(daysAgo))
                .toString());
    }

    /** A member who owns the whole plot from {@code from} to {@code to} (null: still owns it). */
    UUID owner(UUID plot, String lastName, String firstName, String middleName, String phones, String email,
            String from, String to) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into member (id, tenant_id, last_name, first_name, middle_name) "
                + "values (?, ?, ?, ?, ?)", id, tenant, lastName, firstName, middleName);
        insertContacts(tenant, id, email, phoneList(phones));
        ownerJdbc().update("insert into ownership (tenant_id, plot_id, member_id, share_numerator, "
                + "share_denominator, valid_from, valid_to) values (?, ?, ?, 1, 1, ?::date, ?::date)", tenant, plot,
                id, from, to);
        return id;
    }

    /** The phones of a PostgreSQL array literal ({@code {+380...,+380...}}, or {@code {}}). */
    private static String[] phoneList(String literal) {
        String inner = literal == null ? "" : literal.replace("{", "").replace("}", "").strip();
        return inner.isEmpty() ? new String[0] : inner.split(",");
    }

    /** The debt and oldest unpaid date of the plot in the {@code plot_status} cache. */
    void cacheDebt(UUID plot, String debt, LocalDate oldest) {
        ownerJdbc().update("update plot_status set debt = ?, oldest_debt_date = ? where plot_id = ?",
                new BigDecimal(debt), oldest, plot);
    }

    /** The red threshold of the cooperative. */
    void thresholds(String redAmount, int redDays) {
        ownerJdbc().update("insert into cooperative_settings (tenant_id, red_amount, red_days) values (?, ?, ?) "
                + "on conflict (tenant_id) do update set red_amount = excluded.red_amount, "
                + "red_days = excluded.red_days", tenant, new BigDecimal(redAmount), redDays);
    }

    /** A member with a custom role of only these permissions; returns a token. */
    String personWithPermissions(String roleName, String... permissions) {
        UUID role = UUID.randomUUID();
        ownerJdbc().update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, tenant, roleName);
        for (String permission : permissions) {
            ownerJdbc().update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)",
                    tenant, role, permission);
        }
        UUID id = UUID.randomUUID();
        String email = "custom-" + id + "@reports.test";
        ownerJdbc().update("insert into member (id, tenant_id, last_name, first_name) "
                + "values (?, ?, 'Звітний', ?)", id, tenant, roleName);
        insertContacts(tenant, id, email);
        ownerJdbc().update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)", tenant, id,
                role);
        return TestTokens.bearer(tenant, "sub-" + email, email);
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

    ResultActions send(String bearer, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
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
        return plot(number, street, "600.00");
    }

    /** An active plot of the area in square metres. */
    UUID plot(String number, String street, String areaM2) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, street, kind, area_m2) "
                + "values (?, ?, ?, ?, 'PLOT', ?)", id, tenant, number, street, new BigDecimal(areaM2));
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

    // ---- payments (feature 015), written as the database owner

    /**
     * A payment of the plot received on the day and booked in the month of that day, its own group; a cash payment
     * carries the receipt number, a bank one the bank reference (either may be null); {@code payer} may be null.
     */
    UUID payment(UUID plot, String source, String amount, LocalDate receivedOn, Long receipt, String bankReference,
            UUID payer) {
        return payment(plot, source, amount, receivedOn, receipt, bankReference, payer, null);
    }

    /** A payment as {@link #payment(UUID, String, String, LocalDate, Long, String, UUID)} with its sealed note. */
    UUID payment(UUID plot, String source, String amount, LocalDate receivedOn, Long receipt, String bankReference,
            UUID payer, byte[] sealedNote) {
        return payment(UUID.randomUUID(), plot, source, amount, receivedOn, receipt, bankReference, payer,
                sealedNote);
    }

    UUID payment(UUID id, UUID plot, String source, String amount, LocalDate receivedOn, Long receipt,
            String bankReference, UUID payer, byte[] sealedNote) {
        ownerJdbc().update("insert into payment (id, tenant_id, plot_id, kind, source, amount, received_on, "
                + "billing_month_id, group_id, receipt_number, bank_reference, payer_member_id, payer_note_enc) "
                + "values (?, ?, ?, 'PAYMENT', ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, tenant, plot, source,
                new BigDecimal(amount), receivedOn, monthRow(YearMonth.from(receivedOn).toString()),
                UUID.randomUUID(), receipt, bankReference, payer, sealedNote);
        return id;
    }

    /** An allocation of the payment to a charge of its plot. */
    UUID allocate(UUID payment, UUID charge, String amount) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into allocation (id, tenant_id, payment_id, charge_id, plot_id, amount, origin) "
                + "select ?, tenant_id, id, ?, plot_id, ?, 'ENTRY' from payment where id = ?", id, charge,
                new BigDecimal(amount), payment);
        return id;
    }

    /** The reversal of a payment that has no allocation left, in the original's month and on its day. */
    void reversePayment(UUID original) {
        ownerJdbc().update("insert into payment (tenant_id, plot_id, kind, source, amount, received_on, "
                + "billing_month_id, group_id, reverses_payment_id, reason) select tenant_id, plot_id, "
                + "'PAYMENT_REVERSAL', source, -amount, received_on, billing_month_id, group_id, id, "
                + "'Помилковий платіж' from payment where id = ?", original);
    }

    // ---- tariffs (land tax)

    /** A tariff of the charge type for every plot of the cooperative. */
    UUID tariff(UUID chargeType, String name) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into tariff (id, tenant_id, charge_type_id, name) values (?, ?, ?, ?)", id, tenant,
                chargeType, name);
        return id;
    }

    /** A yearly per-sotka version of the tariff. */
    UUID perSotkaYearly(UUID tariff, String rate, String validFrom) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into tariff_version (id, tenant_id, tariff_id, valid_from, basis, frequency, rate) "
                + "values (?, ?, ?, ?::date, 'PER_AREA_SOTKA', 'YEARLY', ?)", id, tenant, tariff, validFrom,
                new BigDecimal(rate));
        return id;
    }

    /** Leaves the plot out of the tariff. */
    void exclude(UUID tariff, UUID plot) {
        ownerJdbc().update("insert into tariff_scope_plot (tenant_id, tariff_id, plot_id, kind, reason) "
                + "values (?, ?, ?, 'EXCLUDED', 'Звільнено')", tenant, tariff, plot);
    }

    // ---- corrections with their author and time

    /**
     * A reversal of the charge in its own month, booked at {@code at} (an ISO timestamp with offset) by {@code by}
     * (null: no author), with the source, recalculation and reason given.
     */
    UUID reversal(UUID original, String source, UUID recalculation, String reason, UUID by, String at) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, reverses_charge_id, run_id, recalculation_id, "
                + "reason, created_by, created_at) select ?, tenant_id, plot_id, charge_type_id, billing_month_id, "
                + "'REVERSAL', ?, -amount, service_from, service_to, accrued_on, id, run_id, ?, ?, ?, ?::timestamptz "
                + "from charge where id = ?", id, source, recalculation, reason, by, at, original);
        return id;
    }

    /** An adjustment of the charge booked in {@code bookedIn} by the recalculation, at {@code at} by {@code by}. */
    void adjustment(UUID original, UUID recalculation, String amount, String bookedIn, UUID by, String at) {
        ownerJdbc().update("insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key, adjusts_charge_id, recalculation_id, "
                + "reason, created_by, created_at) select tenant_id, plot_id, charge_type_id, ?, 'ADJUSTMENT', "
                + "'RECALCULATION', ?, service_from, service_to, accrued_on, ?, id, ?, 'Виправлено ставку', ?, "
                + "?::timestamptz from charge where id = ?", monthRow(bookedIn), new BigDecimal(amount),
                "ADJUSTMENT:" + recalculation + ":" + original, recalculation, by, at, original);
    }

    /** A recalculation of the cause with the reason "Виправлено ставку", booked by {@code by}. */
    UUID recalculation(String cause, UUID by) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into recalculation (id, tenant_id, cause, reason, total, line_count, plot_count, "
                + "digest, created_by) values (?, ?, ?, 'Виправлено ставку', 0.00, 2, 1, 'test-digest', ?)", id,
                tenant, cause, by);
        return id;
    }

    /** A losses run booked in the month and reversed as a whole at {@code reversedAt} by {@code by}. */
    UUID reversedLossesRun(String month, UUID by, String reversedAt) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into losses_run (id, tenant_id, from_date, to_date, status, billing_month_id, "
                + "total, created_by, reversed_at, reversed_by, reverse_reason) values (?, ?, ?::date, ?::date, "
                + "'REVERSED', ?, 12.40, ?, ?::timestamptz, ?, 'Помилковий розрахунок')", id, tenant, month + "-01",
                month + "-14", monthRow(month), by, reversedAt, by);
        return id;
    }

    /** A regular charge of a run, written as the database owner. */
    UUID runCharge(UUID plot, UUID type, UUID run, String amount, String from, String to, String bookedIn) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, source_key, run_id) values (?, ?, ?, ?, ?, "
                + "'REGULAR', 'LOSSES', ?, ?::date, ?::date, ?::date, ?, ?)", id, tenant, plot, type,
                monthRow(bookedIn), new BigDecimal(amount), from, to, from, "TEST:" + id, run);
        return id;
    }

    // ---- meters and water connections (water, missing readings)

    /**
     * A meter installed on {@code installedOn} with the initial value, serial {@code serial}; a plot meter names its
     * plot, the other kinds a name.
     */
    UUID meter(String kind, String resource, UUID plot, String name, String serial, String installedOn,
            String initial) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into meter (id, tenant_id, kind, resource, serial, name, plot_id, installed_on, "
                + "initial_value) values (?, ?, ?, ?, ?, ?, ?, ?::date, ?)", id, tenant, kind, resource, serial, name,
                plot, installedOn, new BigDecimal(initial));
        return id;
    }

    /** A reading of the meter on the day, {@code APPROVED} (entered) or {@code PENDING} (a member's). */
    void reading(UUID meter, String on, String value, String status) {
        ownerJdbc().update("insert into meter_reading (tenant_id, meter_id, taken_on, value, source, status) "
                + "values (?, ?, ?::date, ?, ?, ?)", tenant, meter, on, new BigDecimal(value),
                "PENDING".equals(status) ? "MEMBER" : "ENTERED", status);
    }

    /** The plot's water connection to the well's meter (null: the cooperative's supply) from a day until a day. */
    void connectWater(UUID plot, UUID source, String from, String until) {
        ownerJdbc().update("insert into plot_water_connection (tenant_id, plot_id, source_meter_id, valid_from, "
                + "valid_to) values (?, ?, ?, ?::date, ?::date)", tenant, plot, source, from, until);
    }

    /** A member of the cooperative with one base role; returns a token. */
    private String person(String baseRole) {
        return person(tenant, baseRole);
    }

    /** A member of that cooperative, named "Звітний {baseRole}", with one base role; returns a token. */
    static String person(UUID cooperative, String baseRole) {
        UUID id = UUID.randomUUID();
        String email = baseRole.toLowerCase() + "-" + id + "@reports.test";
        ownerJdbc().update("insert into member (id, tenant_id, last_name, first_name) "
                + "values (?, ?, 'Звітний', ?)", id, cooperative, baseRole);
        sealContacts(cooperative, id, email);
        ownerJdbc().update("insert into member_role (tenant_id, member_id, role_id) "
                + "select ?, ?, id from role where tenant_id = ? and base_code = ?", cooperative, id, cooperative,
                baseRole);
        return TestTokens.bearer(cooperative, "sub-" + email, email);
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
}
