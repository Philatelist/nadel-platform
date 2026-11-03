package app.nadel.treasury;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
