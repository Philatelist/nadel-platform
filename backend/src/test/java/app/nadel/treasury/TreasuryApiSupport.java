package app.nadel.treasury;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the treasury API tests share on top of {@link TreasuryTestSupport}'s two throw-away cooperatives: people with a
 * base or a custom role (tokens; their e-mails sealed so the sign-in links them), plots that take payments, payments and
 * reversals entered through the payments API (so the real ledger paths publish {@code PaymentMoneyMoved}), the
 * treasury requests and the journal as stored.
 */
abstract class TreasuryApiSupport extends TreasuryTestSupport {

    static final String ACCOUNTS = "/api/v1/money-accounts";
    static final String PAYMENTS = "/api/v1/payments";
    static final String EXPENSES = "/api/v1/expenses";
    static final String CATEGORIES = "/api/v1/expense-categories";
    static final String HANDOVERS = "/api/v1/handovers";
    static final String OTHER_INCOME = "/api/v1/other-income";
    static final String COUNTS = "/api/v1/cash-counts";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    /** The member behind the last token {@link #person} or {@link #customRole} made. */
    UUID lastPerson;

    static LocalDate today() {
        return LocalDate.now(ZoneId.of("Europe/Kyiv"));
    }

    // ---- people

    /** A member of the cooperative with one base role; returns a token. */
    String person(UUID tenant, String baseRole) {
        UUID id = signedUpMember(tenant);
        ownerJdbc().update("insert into member_role (tenant_id, member_id, role_id) "
                + "select ?, ?, id from role where tenant_id = ? and base_code = ?", tenant, id, tenant, baseRole);
        return token(tenant, id);
    }

    /** A member whose only role is a custom role with the permissions; returns a token. */
    String customRole(UUID tenant, String... permissions) {
        UUID id = signedUpMember(tenant);
        UUID role = UUID.randomUUID();
        ownerJdbc().update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, tenant, "Role " + role);
        for (String permission : permissions) {
            ownerJdbc().update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)",
                    tenant, role, permission);
        }
        ownerJdbc().update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)", tenant, id,
                role);
        return token(tenant, id);
    }

    /** The platform operator: a realm role, no cooperative, no permission. */
    static String operator() {
        return TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();
    }

    private UUID signedUpMember(UUID tenant) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into member (id, tenant_id, last_name, first_name) values (?, ?, 'Скарбник', ?)",
                id, tenant, "Тест" + id.toString().substring(0, 4));
        insertContacts(tenant, id, emailOf(id));
        lastPerson = id;
        return id;
    }

    private static String token(UUID tenant, UUID member) {
        return TestTokens.bearer(tenant, "sub-" + member, emailOf(member));
    }

    private static String emailOf(UUID member) {
        return "treasury-" + member + "@treasury.test";
    }

    // ---- plots and payments

    /** A plot that takes payments (with its status row, which the plot status listener updates). */
    UUID paymentPlot(UUID tenant) {
        UUID id = plot(tenant);
        ownerJdbc().update("insert into plot_status (plot_id, tenant_id, status) values (?, ?, 'GREY')", id, tenant);
        return id;
    }

    void own(UUID tenant, UUID member, UUID plot) {
        ownerJdbc().update("insert into ownership (id, tenant_id, plot_id, member_id, share_numerator, "
                + "share_denominator, valid_from) values (?, ?, ?, ?, 1, 1, date '2020-01-01')", UUID.randomUUID(),
                tenant, plot, member);
    }

    /** A payment of one plot saving the proposal (nothing to allocate: it stays prepayment). */
    static Map<String, Object> payment(UUID plot, String source, String amount, LocalDate receivedOn) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plotId", plot.toString());
        body.put("source", source);
        body.put("amount", amount);
        body.put("receivedOn", receivedOn.toString());
        return body;
    }

    ResultActions save(String bearer, Map<String, Object> body) throws Exception {
        return send(bearer, post(PAYMENTS + "?preview=false"), body);
    }

    /** Saves a payment of one plot and returns its id. */
    UUID pay(String bearer, UUID plot, String source, String amount, LocalDate receivedOn) throws Exception {
        return UUID.fromString(body(save(bearer, payment(plot, source, amount, receivedOn))
                .andExpect(status().isCreated())).get("payments").get(0).get("id").asString());
    }

    /** Reverses the payment with the reason (needs {@code PAYMENTS_REVERSE}: the chairman). */
    void reverse(String bearer, UUID payment, String reason) throws Exception {
        send(bearer, post(PAYMENTS + "/" + payment + "/reverse"), Map.of("reason", reason))
                .andExpect(status().isCreated());
    }

    /** The reversal row of the payment. */
    UUID reversalRow(UUID payment) {
        return ownerJdbc().queryForObject("select id from payment where reverses_payment_id = ?", UUID.class, payment);
    }

    // ---- treasury requests

    ResultActions accounts(String bearer) throws Exception {
        return send(bearer, get(ACCOUNTS), null);
    }

    ResultActions accounts(String bearer, LocalDate asOf) throws Exception {
        return send(bearer, get(ACCOUNTS).param("asOf", asOf.toString()), null);
    }

    ResultActions setOpening(String bearer, UUID account, String amount, LocalDate date) throws Exception {
        return send(bearer, post(ACCOUNTS + "/" + account + "/opening-balance"),
                Map.of("amount", amount, "date", date.toString()));
    }

    ResultActions movements(String bearer, UUID account, LocalDate from, LocalDate to) throws Exception {
        return send(bearer, get(ACCOUNTS + "/" + account + "/movements").param("from", from.toString())
                .param("to", to.toString()), null);
    }

    // ---- expenses and categories

    /** The fields of a new expense. */
    static Map<String, Object> expense(String account, LocalDate date, String amount, UUID category) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("account", account);
        body.put("date", date.toString());
        body.put("amount", amount);
        body.put("categoryId", category.toString());
        return body;
    }

    static Map<String, Object> with(Map<String, Object> body, String field, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.put(field, value);
        return copy;
    }

    /** A receipt file part. */
    static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("files", name, contentType, content);
    }

    /**
     * {@code POST /expenses} as the generated web client sends it: the {@code expense} part a plain form field (JSON
     * text, no content type) and the files.
     */
    ResultActions createExpense(String bearer, Map<String, Object> expense, MockMultipartFile... files)
            throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(EXPENSES);
        request.part(new MockPart("expense", json.writeValueAsBytes(expense)));
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        return send(bearer, request, null);
    }

    /** Records an expense and returns its id. */
    UUID recordExpense(String bearer, Map<String, Object> expense, MockMultipartFile... files) throws Exception {
        return UUID.fromString(body(createExpense(bearer, expense, files).andExpect(status().isCreated())).get("id")
                .asString());
    }

    ResultActions reverseExpense(String bearer, UUID expense, String reason) throws Exception {
        return send(bearer, post(EXPENSES + "/" + expense + "/reverse"), Map.of("reason", reason));
    }

    ResultActions expenseCard(String bearer, UUID expense) throws Exception {
        return send(bearer, get(EXPENSES + "/" + expense), null);
    }

    ResultActions download(String bearer, UUID expense, int n) throws Exception {
        return send(bearer, get(EXPENSES + "/" + expense + "/attachments/" + n), null);
    }

    ResultActions categories(String bearer, String kind, boolean includeArchived) throws Exception {
        return send(bearer, get(CATEGORIES).param("kind", kind).param("includeArchived",
                Boolean.toString(includeArchived)), null);
    }

    ResultActions addCategory(String bearer, String kind, String name) throws Exception {
        return send(bearer, post(CATEGORIES), Map.of("kind", kind, "name", name));
    }

    ResultActions updateCategory(String bearer, UUID category, Map<String, Object> body) throws Exception {
        return send(bearer, put(CATEGORIES + "/" + category), body);
    }

    ResultActions reorderCategories(String bearer, String kind, List<UUID> ids) throws Exception {
        return send(bearer, put(CATEGORIES + "/order"), Map.of("kind", kind, "ids", ids));
    }

    // ---- handovers and other income

    /** The fields of a new handover. */
    static Map<String, Object> handover(LocalDate date, String amount, String documentNumber) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", date.toString());
        body.put("amount", amount);
        body.put("documentNumber", documentNumber);
        return body;
    }

    ResultActions createHandover(String bearer, Map<String, Object> handover) throws Exception {
        return send(bearer, post(HANDOVERS), handover);
    }

    /** Records a handover and returns its id. */
    UUID recordHandover(String bearer, Map<String, Object> handover) throws Exception {
        return UUID.fromString(body(createHandover(bearer, handover).andExpect(status().isCreated())).get("id")
                .asString());
    }

    ResultActions reverseHandover(String bearer, UUID handover, String reason) throws Exception {
        return send(bearer, post(HANDOVERS + "/" + handover + "/reverse"), Map.of("reason", reason));
    }

    /** The fields of a new other income. */
    static Map<String, Object> otherIncome(String account, LocalDate date, String amount, UUID category) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("account", account);
        body.put("date", date.toString());
        body.put("amount", amount);
        body.put("categoryId", category.toString());
        return body;
    }

    ResultActions createOtherIncome(String bearer, Map<String, Object> income) throws Exception {
        return send(bearer, post(OTHER_INCOME), income);
    }

    /** Records an other income and returns its id. */
    UUID recordOtherIncome(String bearer, Map<String, Object> income) throws Exception {
        return UUID.fromString(body(createOtherIncome(bearer, income).andExpect(status().isCreated())).get("id")
                .asString());
    }

    ResultActions reverseOtherIncome(String bearer, UUID income, String reason) throws Exception {
        return send(bearer, post(OTHER_INCOME + "/" + income + "/reverse"), Map.of("reason", reason));
    }

    // ---- cash counts

    /** The fields of a cash count. */
    static Map<String, Object> count(Instant countedAt, String counted, String explanation) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("countedAt", countedAt.toString());
        body.put("counted", counted);
        if (explanation != null) {
            body.put("explanation", explanation);
        }
        return body;
    }

    /** {@code day} at {@code hour}:30 in Kyiv. */
    static Instant kyiv(LocalDate day, int hour) {
        return day.atTime(hour, 30).atZone(ZoneId.of("Europe/Kyiv")).toInstant();
    }

    ResultActions previewCount(String bearer, Map<String, Object> count) throws Exception {
        return send(bearer, post(COUNTS).param("preview", "true"), count);
    }

    ResultActions enterCount(String bearer, Map<String, Object> count) throws Exception {
        return send(bearer, post(COUNTS), count);
    }

    /** Saves a count and returns its id. */
    UUID saveCount(String bearer, Map<String, Object> count) throws Exception {
        return UUID.fromString(body(enterCount(bearer, count).andExpect(status().isCreated())).get("id").asString());
    }

    ResultActions countCard(String bearer, UUID count) throws Exception {
        return send(bearer, get(COUNTS + "/" + count), null);
    }

    ResultActions counts(String bearer) throws Exception {
        return send(bearer, get(COUNTS), null);
    }

    ResultActions bookDifference(String bearer, UUID count, String reason) throws Exception {
        return send(bearer, post(COUNTS + "/" + count + "/book-difference"), Map.of("reason", reason));
    }

    ResultActions reverseMovement(String bearer, UUID account, UUID movement, String reason) throws Exception {
        return send(bearer, post(ACCOUNTS + "/" + account + "/movements/" + movement + "/reverse"),
                Map.of("reason", reason));
    }

    ResultActions countAct(String bearer, UUID count) throws Exception {
        return send(bearer, get(COUNTS + "/" + count + "/act.pdf"), null);
    }

    /** The lines of the account's journal from {@code from} to today. */
    JsonNode journal(String bearer, UUID account, LocalDate from) throws Exception {
        return body(movements(bearer, account, from, today()).andExpect(status().isOk())).get("items");
    }

    /** The audit entries about the entity with the action, once {@code count} of them are written. */
    List<Map<String, Object>> awaitAudit(UUID entity, String action, int count) {
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc()
                .queryForObject("select count(*) from audit_entry where entity_id = ? and action = ?", Integer.class,
                        entity, action) >= count);
        return ownerJdbc().queryForList("select action, entity_type, entity_label, actor_member_id, "
                + "cast(changes as text) as changes from audit_entry where entity_id = ? and action = ? "
                + "order by occurred_at", entity, action);
    }

    ResultActions send(String bearer, AbstractMockHttpServletRequestBuilder<?> request, Object body)
            throws Exception {
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

    /** The account of the kind in a {@code GET /money-accounts} answer. */
    static JsonNode accountOf(JsonNode accounts, String kind) {
        for (JsonNode account : accounts) {
            if (account.get("kind").asString().equals(kind)) {
                return account;
            }
        }
        throw new AssertionError("No " + kind + " account");
    }

    // ---- the journal as stored

    /** The movements of the cooperative, oldest first, with dates and months as ISO text. */
    List<Map<String, Object>> storedMovements(UUID tenant) {
        return ownerJdbc().queryForList("select m.id, a.kind as account, m.kind, m.amount::text as amount, "
                + "m.moved_on::text as moved_on, m.booking_month::text as booking_month, m.source_type, m.source_id, "
                + "m.payment_row_id, m.reverses_movement_id, m.reason, m.created_by from account_movement m "
                + "join money_account a on a.id = m.account_id where m.tenant_id = ? order by m.created_at, m.amount desc",
                tenant);
    }
}
