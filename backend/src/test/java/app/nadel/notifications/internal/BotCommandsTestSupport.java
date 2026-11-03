package app.nadel.notifications.internal;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import app.nadel.notifications.FakeBotApi.Sent;
import app.nadel.notifications.FakeBotApi.SentDocument;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Fixtures of the tests of the bot's menu actions (feature 017 §2.5) on the throw-away cooperative of
 * {@link DeliveryTestSupport}: its bot on the in-JVM fake Bot API, started through the supervisor (the real long poll
 * and take-before-answer), members linked through the bot's own code path, charges of two types, payments, plot meters
 * and readings through the API. The bot is stopped and the meters removed after each test (the cooperative itself by
 * the parent).
 */
abstract class BotCommandsTestSupport extends DeliveryTestSupport {

    static final String MENU_ROW_1 = "Борг | Рахунок";
    static final String MENU_ROW_2 = "Показання | Відключити";
    static final String NBSP = " ";

    @Autowired
    BotSupervisor supervisor;

    @AfterEach
    void stopBotAndRemoveMeters() {
        supervisor.stopAll();
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                    "select count(*) from event_publication where completion_date is null and serialized_event like ?",
                    Integer.class, "%" + tenant + "%") == 0);
        } catch (ConditionTimeoutException e) {
            // a listener that failed stays incomplete; the clean-up goes on
        }
        ownerJdbc().update("delete from meter_reading where tenant_id = ?", tenant);
        ownerJdbc().update("delete from meter where tenant_id = ? and replaces_meter_id is not null", tenant);
        ownerJdbc().update("delete from meter where tenant_id = ?", tenant);
    }

    /** The cooperative's bot on the fake Bot API, polled by this instance. */
    void startBot() throws Exception {
        bot();
        supervisor.tick();
        await().atMost(Duration.ofSeconds(10)).until(() -> supervisor.running(tenant));
    }

    /** Today in Kyiv. */
    static LocalDate today() {
        return LocalDate.now(ZoneId.of("Europe/Kyiv"));
    }

    /** A member who gets invoices on paper (so payments plan no messages), with an e-mail to sign in with. */
    Member member(String lastName) {
        String email = "bot-" + UUID.randomUUID() + "@delivery.test";
        UUID id = member(lastName, "PAPER", email);
        return new Member(id, TestTokens.bearer(tenant, "sub-" + email, email));
    }

    /** A member and the token they sign in to the account with. */
    record Member(UUID id, String bearer) {
    }

    /** A regular charge of the plot of the seeded type with the code, in the current month. */
    void charge(UUID plot, String typeCode, String amount) {
        String month = month().toString();
        ownerJdbc().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month + "-01");
        UUID monthId = ownerJdbc().queryForObject("select id from billing_month where tenant_id = ? and month = "
                + "?::date", UUID.class, tenant, month + "-01");
        UUID type = ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? and code = ?",
                UUID.class, tenant, typeCode);
        UUID id = UUID.randomUUID();
        LocalDate first = LocalDate.parse(month + "-01");
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, 'REGULAR', "
                + "'TARIFF', ?, ?, ?, ?, ?)", id, tenant, plot, type, monthId, new BigDecimal(amount), first,
                first.withDayOfMonth(first.lengthOfMonth()), first, "TEST:" + id);
    }

    /** A cash payment for the plot received today, saved by the treasurer. */
    void pay(UUID plot, UUID payer, String amount) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plotId", plot.toString());
        body.put("source", "CASH");
        body.put("amount", amount);
        body.put("receivedOn", today().toString());
        body.put("payerMemberId", payer.toString());
        send(treasurer, post("/api/v1/payments?preview=false"), body).andExpect(status().isCreated());
    }

    /** The plot account as the member sees it in the account. */
    JsonNode account(Member member, UUID plot) throws Exception {
        return body(send(member.bearer(), get("/api/v1/plots/" + plot + "/account"), null)
                .andExpect(status().isOk()));
    }

    /** An active electricity meter of the plot, installed on New Year's Day, registered by the chairman. */
    UUID meter(UUID plot, String serial, String resource) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "PLOT");
        body.put("resource", resource);
        body.put("plotId", plot.toString());
        body.put("serial", serial);
        body.put("installedOn", today().withDayOfYear(1).toString());
        body.put("initialReading", "1000");
        return UUID.fromString(body(send(chairman, post("/api/v1/meters"), body).andExpect(status().isCreated()))
                .get("id").asString());
    }

    /** A counted reading of the meter entered by the chairman. */
    void entered(UUID meter, LocalDate takenOn, String value) throws Exception {
        JsonNode rows = body(send(chairman, post("/api/v1/readings"), Map.of("rows", List.of(Map.of(
                "meterId", meter.toString(), "takenOn", takenOn.toString(), "value", value))))
                .andExpect(status().isOk())).get("rows");
        if (!"SAVED".equals(rows.get(0).get("outcome").asString())) {
            throw new AssertionError("The reading was not saved: " + rows);
        }
    }

    /** The readings of the meter: id, status, taken_on, value, source and submitter, oldest first. */
    List<Map<String, Object>> readingsOf(UUID meter) {
        return ownerJdbc().queryForList("select id, status, taken_on, value, source, entered_by_member_id "
                + "from meter_reading where meter_id = ? order by created_at, id", meter);
    }

    /** Sends a message from the chat and waits until the bot has sent {@code replies} text messages to it in all. */
    List<Sent> say(long chat, String text, int replies) {
        telegram.pushMessage(TOKEN, chat, text);
        return awaitSent(chat, replies);
    }

    /** The bot's last text message to the chat once it sent {@code replies} in all. */
    Sent lastReply(long chat, String text, int replies) {
        return say(chat, text, replies).getLast();
    }

    List<Sent> awaitSent(long chat, int replies) {
        await().atMost(Duration.ofSeconds(10)).until(() -> telegram.sent(chat).size() >= replies);
        return telegram.sent(chat);
    }

    /** Waits until the bot has sent {@code documents} PDFs to the chat in all. */
    List<SentDocument> awaitDocuments(long chat, int documents) {
        await().atMost(Duration.ofSeconds(10)).until(() -> telegram.documents(chat).size() >= documents);
        return telegram.documents(chat);
    }

    /** Waits until the bot has taken every update up to {@code updateId}. */
    void awaitTaken(long updateId) {
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select coalesce(max(last_update_id), 0) from bot_poll_state where tenant_id = ?", Long.class,
                tenant) >= updateId);
    }
}
