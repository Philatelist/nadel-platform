package app.nadel.documents;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.awaitility.core.ConditionTimeoutException;
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
 * A throw-away cooperative per test for the documents (feature 016): the seeded roles and charge types, requisites,
 * a chairman, a treasurer and a member with Ukrainian names (tokens), plots and charges written as the database owner
 * (no event) and cash or bank payments entered through the API by the treasurer, so a receipt has real rows behind it.
 * After the test the asynchronous listeners are given time to finish, then everything of the cooperative is removed;
 * the ledger rows as {@code nadel_owner} with the maintenance setting (the ledger refuses DELETE otherwise).
 */
abstract class DocumentsTestSupport extends IntegrationTest {

    static final String PAYMENTS = "/api/v1/payments";
    static final String RECEIPT = "/api/v1/payments/%s/receipt.pdf";

    static final String TREASURER_NAME = "Коваленко Олена Петрівна";
    static final String MEMBER_NAME = "Шевченко Тарас Григорович";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    UUID tenant;
    String chairman;
    String treasurer;
    String member;
    UUID memberId;

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
        owner().update("insert into tenant (id, name, edrpou) values (?, 'СТ «Документи»', '12345678')", tenant);
        owner().queryForList("select seed_base_roles(?)", tenant);
        owner().update("insert into cooperative_settings (tenant_id, official_name, legal_address, bank_name, iban) "
                + "values (?, 'Садівниче товариство «Документи»', 'с. Тестове, вул. Садова, 1', 'АТ «Тестбанк»', "
                + "'UA850000000000000000000123456')", tenant);
        chairman = person("CHAIRMAN", "Бондаренко", "Іван", "Іванович");
        treasurer = person("TREASURER", "Коваленко", "Олена", "Петрівна");
        member = person("MEMBER", "Шевченко", "Тарас", "Григорович");
        memberId = lastPerson;
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
            sql.update("delete from allocation where tenant_id = ? and releases_allocation_id is not null", id);
            sql.update("delete from allocation where tenant_id = ?", id);
            sql.update("delete from payment where tenant_id = ? and reverses_payment_id is not null", id);
            sql.update("delete from payment where tenant_id = ?", id);
            sql.update("delete from receipt_counter where tenant_id = ?", id);
            sql.update("delete from charge where tenant_id = ? and reverses_charge_id is not null", id);
            sql.update("delete from charge where tenant_id = ?", id);
            sql.update("delete from billing_month where tenant_id = ?", id);
        });
        for (String table : List.of("audit_entry", "plot_status", "ownership", "plot", "member_role",
                "role_permission", "role", "member", "charge_type", "cooperative_settings")) {
            owner().update("delete from " + table + " where tenant_id = ?", id);
        }
        deleteDataKeys(id);
        owner().update("delete from tenant where id = ?", id);
    }

    static JdbcTemplate owner() {
        return ownerJdbc();
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

    private UUID lastPerson;

    /** A member of the cooperative with one base role (its e-mail sealed); returns a token. */
    String person(String baseRole, String lastName, String firstName, String middleName) {
        UUID id = UUID.randomUUID();
        lastPerson = id;
        String email = baseRole.toLowerCase() + "-" + id + "@documents.test";
        owner().update("insert into member (id, tenant_id, last_name, first_name, middle_name) values (?, ?, ?, ?, ?)",
                id, tenant, lastName, firstName, middleName);
        insertContacts(tenant, id, email);
        if (baseRole != null) {
            owner().update("insert into member_role (tenant_id, member_id, role_id) "
                    + "select ?, ?, id from role where tenant_id = ? and base_code = ?", tenant, id, tenant, baseRole);
        }
        return TestTokens.bearer(tenant, "sub-" + email, email);
    }

    /** A member whose only role is a custom role with the permissions; returns a token. */
    String customRole(String... permissions) {
        String token = person("MEMBER", "Мельник", "Петро", "Андрійович");
        UUID role = UUID.randomUUID();
        owner().update("delete from member_role where member_id = ?", lastPerson);
        owner().update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, tenant, "Role " + role);
        for (String permission : permissions) {
            owner().update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)", tenant,
                    role, permission);
        }
        owner().update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)", tenant,
                lastPerson, role);
        return token;
    }

    /** The member behind the last {@link #person} or {@link #customRole}. */
    UUID lastPerson() {
        return lastPerson;
    }

    static LocalDate today() {
        return LocalDate.now(ZoneId.of("Europe/Kyiv"));
    }

    // ---- plots, owners and charges

    UUID plot(String number, String street) {
        UUID id = UUID.randomUUID();
        owner().update("insert into plot (id, tenant_id, number, street, kind, area_m2) values (?, ?, ?, ?, 'PLOT', "
                + "600)", id, tenant, number, street);
        owner().update("insert into plot_status (plot_id, tenant_id, status) values (?, ?, 'GREY')", id, tenant);
        return id;
    }

    void own(UUID owner, UUID plot) {
        owner().update("insert into ownership (id, tenant_id, plot_id, member_id, share_numerator, share_denominator, "
                + "valid_from) values (?, ?, ?, ?, 1, 1, date '2020-01-01')", UUID.randomUUID(), tenant, plot, owner);
    }

    UUID typeId(String code) {
        return owner().queryForObject("select id from charge_type where tenant_id = ? and code = ?", UUID.class,
                tenant, code);
    }

    /** A REGULAR tariff charge of the plot for the whole month (YYYY-MM), accrued on its first day; no event. */
    UUID charge(UUID plot, String typeCode, String amount, String month) {
        owner().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month + "-01");
        UUID monthId = owner().queryForObject("select id from billing_month where tenant_id = ? and month = ?::date",
                UUID.class, tenant, month + "-01");
        UUID id = UUID.randomUUID();
        LocalDate first = LocalDate.parse(month + "-01");
        owner().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, 'REGULAR', "
                + "'TARIFF', ?, ?, ?, ?, ?)", id, tenant, plot, typeId(typeCode), monthId, new BigDecimal(amount),
                first, first.withDayOfMonth(first.lengthOfMonth()), first, "TEST:" + id);
        return id;
    }

    // ---- payments

    /** A payment of one plot received today, saving the proposal; returns the saved result. */
    JsonNode pay(UUID plot, String source, String amount, String payerNote) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plotId", plot.toString());
        body.put("source", source);
        body.put("amount", amount);
        body.put("receivedOn", today().toString());
        body.put("payerMemberId", memberId.toString());
        if (payerNote != null) {
            body.put("payerNote", payerNote);
        }
        return save(body);
    }

    /** A cash payment split over the payer's plots: {@code parts} alternates plot ids and amounts. */
    JsonNode paySplit(String amount, Object... parts) throws Exception {
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (int i = 0; i < parts.length; i += 2) {
            list.add(Map.of("plotId", parts[i].toString(), "amount", parts[i + 1]));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "CASH");
        body.put("amount", amount);
        body.put("receivedOn", today().toString());
        body.put("split", Map.of("payerMemberId", memberId.toString(), "parts", list));
        return save(body);
    }

    private JsonNode save(Map<String, Object> body) throws Exception {
        return body(send(treasurer, post(PAYMENTS + "?preview=false"), body).andExpect(status().isCreated()));
    }

    static UUID firstPayment(JsonNode saved) {
        return UUID.fromString(saved.get("payments").get(0).get("id").asString());
    }

    void reverse(UUID payment) throws Exception {
        send(chairman, post(PAYMENTS + "/" + payment + "/reverse"), Map.of("reason", "Помилковий платіж"))
                .andExpect(status().isCreated());
    }

    // ---- requests

    ResultActions receipt(String bearer, UUID payment) throws Exception {
        return send(bearer, get(RECEIPT.formatted(payment)), null);
    }

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

    /** The text of every page of a PDF. */
    static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
