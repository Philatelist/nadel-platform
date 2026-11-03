package app.nadel.notifications.internal;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import app.nadel.notifications.FakeBotApi;
import app.nadel.notifications.FakeBotApi.Sent;
import app.nadel.tenancy.TenantContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Fixtures of the bot tests (feature 017 §2.2, §2.5): the demo cooperative's bot against the in-JVM fake Bot API,
 * started by the test through the supervisor (nothing polls on a schedule in tests), members with plots, link codes,
 * and messages pushed from chats. The bots are stopped and the fixtures removed after each test.
 */
abstract class BotTestSupport extends DeliverySettingsTestSupport {

    static final String TOKEN = "123456789:AAbotTokenForTheBotTests5678";
    static final String MENU_ROW_1 = "Борг | Рахунок";
    static final String MENU_ROW_2 = "Показання | Відключити";

    final FakeBotApi telegram = FakeBotApi.shared();

    @Autowired
    BotSupervisor supervisor;

    @Autowired
    LinkCodes codes;

    private final List<UUID> ownedPlots = new ArrayList<>();

    @AfterEach
    void stopBots() {
        supervisor.stopAll();
        for (UUID plot : ownedPlots) {
            ownerJdbc().update("delete from ownership where plot_id = ?", plot);
        }
        ownedPlots.clear();
    }

    /** Saves the bot token as the demo chairman and starts the bot on this instance. */
    void startBot() throws Exception {
        startBot(TOKEN);
    }

    void startBot(String token) throws Exception {
        save(chairman(), Map.of("telegram", Map.of("botToken", token))).andExpect(status().isOk());
        supervisor.tick();
        await().atMost(Duration.ofSeconds(10)).until(() -> supervisor.running(DEMO_TENANT));
    }

    /** A chat id no other test uses. */
    static long chat() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
    }

    /** An active member of the demo cooperative with an e-mail (so they can sign in) who owns the given plots. */
    Owner owner(String firstName, int plots) {
        String email = "bot-" + UUID.randomUUID() + "@demo.local";
        UUID member = newMember(DEMO_TENANT, "Бот", firstName, null, email);
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < plots; i++) {
            UUID plot = plot(DEMO_TENANT);
            ownedPlots.add(plot);
            ownerJdbc().update("insert into ownership (tenant_id, plot_id, member_id, share_numerator, "
                    + "share_denominator, valid_from) values (?, ?, ?, 1, 1, date '2020-01-01')", DEMO_TENANT, plot,
                    member);
            numbers.add(ownerJdbc().queryForObject("select number from plot where id = ?", String.class, plot));
        }
        return new Owner(member, email, numbers.stream().sorted().toList());
    }

    /** A member of the demo cooperative and the numbers of their plots. */
    record Owner(UUID id, String email, List<String> plots) {

        String bearer() {
            return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
        }
    }

    /** A new account code of the member (as «Підключити Telegram» issues it). */
    String accountCode(UUID member) {
        return TenantContext.runAs(DEMO_TENANT, () -> codes.issue(member, LinkCodes.Purpose.ACCOUNT).code());
    }

    /** Sends a message from the chat and waits until the bot has answered {@code replies} messages to it in all. */
    List<Sent> say(long chat, String text, int replies) {
        telegram.pushMessage(TOKEN, chat, text);
        await().atMost(Duration.ofSeconds(10)).until(() -> telegram.sent(chat).size() >= replies);
        return telegram.sent(chat);
    }

    /** The bot's last message to the chat after it answered {@code replies} messages in all. */
    Sent lastReply(long chat, String text, int replies) {
        return say(chat, text, replies).getLast();
    }

    /** Waits until the demo bot has taken every update up to {@code updateId}. */
    void awaitTaken(long updateId) {
        await().atMost(Duration.ofSeconds(10)).until(() -> inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select last_update_id from bot_poll_state", Long.class)) >= updateId);
    }

    /** Links the chat to the member through the bot, as the member would. */
    void link(long chat, UUID member) {
        int before = telegram.sent(chat).size();
        say(chat, "/start " + accountCode(member), before + 1);
    }

    /** The member the chat is linked to now (read through the blind index), or null. */
    UUID linkedMember(long chat) {
        return TenantContext.runAs(DEMO_TENANT, () -> links().memberOfChat(chat).orElse(null));
    }

    @Autowired
    private TelegramLinks telegramLinks;

    TelegramLinks links() {
        return telegramLinks;
    }

    /** The links of the member, oldest first: the end reason (null while active). */
    List<String> linkHistory(UUID member) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForList("select coalesce(end_reason, 'ACTIVE') from telegram_link "
                + "where member_id = ? order by linked_at, ended_at nulls last", String.class, member));
    }

    /** The audit entries about the member with the given action, once the outbox delivered them. */
    List<Map<String, Object>> memberAudit(UUID member, String action, int expected) {
        await().atMost(Duration.ofSeconds(10)).until(() -> inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from audit_entry where entity_id = ? and action = ?", Integer.class, member,
                action)) >= expected);
        return inTenant(DEMO_TENANT, sql -> sql.queryForList("select action, entity_type, entity_label, "
                + "cast(changes as text) as changes from audit_entry where entity_id = ? and action = ? "
                + "order by occurred_at", member, action));
    }

    ResultActions myNotifications(String bearer) throws Exception {
        return mvc.perform(get("/api/v1/me/notifications").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    ResultActions createLink(String bearer) throws Exception {
        return mvc.perform(post("/api/v1/me/telegram-link").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    ResultActions deleteLink(String bearer) throws Exception {
        return mvc.perform(delete("/api/v1/me/telegram-link").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
