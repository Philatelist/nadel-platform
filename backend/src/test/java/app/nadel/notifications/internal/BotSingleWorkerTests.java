package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.notifications.FakeBotApi.Sent;
import app.nadel.notifications.internal.telegram.TelegramClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * One bot worker per cooperative across application instances (feature 017 §2.5): two supervisors with their own
 * instance ids over the one database stand for two copies of the application. Exactly one of them answers each
 * update; when the holder dies, the other takes the bot over once the lease ran out; a new token restarts the bot,
 * and a token Telegram refuses stops it until the token changes.
 */
class BotSingleWorkerTests extends BotTestSupport {

    private static final String OTHER_TOKEN = "987654321:AAanotherTokenForTheBotTests4321";
    private static final String BAD_TOKEN = "bad654321:AAtokenTelegramRefusesInTheTests";
    private static final Duration LEASE = Duration.ofSeconds(2);

    @Autowired
    DeliverySettingsService settings;

    @Autowired
    BotPollState state;

    @Autowired
    TelegramClient client;

    @Autowired
    BotConversation conversation;

    @Autowired
    BotDialogue dialogue;

    @Autowired
    PlatformTransactionManager transactionManager;

    private BotSupervisor first;
    private BotSupervisor second;

    @AfterEach
    void stopInstances() {
        if (first != null) {
            first.stopAll();
        }
        if (second != null) {
            second.stopAll();
        }
    }

    @Test
    void twoInstancesAnswerEachUpdateExactlyOnce() throws Exception {
        saveToken(TOKEN);
        first = instance("instance-a");
        second = instance("instance-b");

        first.tick();
        second.tick();
        first.tick();
        second.tick();

        assertThat(List.of(first.running(DEMO_TENANT), second.running(DEMO_TENANT)))
                .containsExactlyInAnyOrder(true, false);
        long chat = chat();
        long last = 0;
        for (int i = 0; i < 5; i++) {
            last = telegram.pushMessage(TOKEN, chat, "/help");
        }
        awaitTaken(last);
        await().atMost(Duration.ofSeconds(10)).until(() -> telegram.sent(chat).size() >= 5);
        Thread.sleep(300);
        assertThat(telegram.sent(chat)).hasSize(5);
    }

    @Test
    void anotherInstanceTakesTheBotOverWhenTheLeaseRunsOut() throws Exception {
        saveToken(TOKEN);
        first = instance("instance-a");
        second = instance("instance-b");
        first.tick();
        assertThat(first.running(DEMO_TENANT)).isTrue();

        // the first instance dies without giving the lease up
        first.abandon();
        second.tick();
        assertThat(second.running(DEMO_TENANT)).isFalse();

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).until(() -> {
            second.tick();
            return second.running(DEMO_TENANT);
        });
        long chat = chat();
        assertThat(lastReply(chat, "/help", 1).text()).startsWith("Це бот кооперативу");
        assertThat(holder()).isEqualTo("instance-b");
    }

    @Test
    void aNewTokenRestartsTheBot() throws Exception {
        saveToken(TOKEN);
        first = instance("instance-a");
        first.tick();
        long chat = chat();
        assertThat(lastReply(chat, "/help", 1).tokenLast4()).isEqualTo("5678");

        saveToken(OTHER_TOKEN);
        first.tick();

        assertThat(first.running(DEMO_TENANT)).isTrue();
        telegram.pushMessage(OTHER_TOKEN, chat, "/help");
        await().atMost(Duration.ofSeconds(10)).until(() -> telegram.sent(chat).size() >= 2);
        Sent reply = telegram.sent(chat).getLast();
        assertThat(reply.tokenLast4()).isEqualTo("4321");
        // the old token is no longer polled
        int polledBefore = pollsWith("5678");
        Thread.sleep(1_500);
        assertThat(pollsWith("5678")).isEqualTo(polledBefore);
    }

    @Test
    void aTokenTelegramRefusesStopsTheBotUntilTheTokenChanges() throws Exception {
        saveToken(BAD_TOKEN);
        first = instance("instance-a");
        first.tick();
        await().atMost(Duration.ofSeconds(10)).until(() -> !first.running(DEMO_TENANT));
        int polls = telegram.calls("getUpdates").size();
        assertThat(polls).isEqualTo(1);

        first.tick();
        first.tick();
        assertThat(first.running(DEMO_TENANT)).isFalse();
        assertThat(telegram.calls("getUpdates")).hasSize(polls);

        saveToken(TOKEN);
        first.tick();
        assertThat(first.running(DEMO_TENANT)).isTrue();
        assertThat(lastReply(chat(), "/help", 1).text()).startsWith("Це бот кооперативу");
    }

    private BotSupervisor instance(String id) {
        return new BotSupervisor(id, LEASE, settings, state, client, conversation, dialogue, transactionManager);
    }

    private void saveToken(String token) throws Exception {
        save(chairman(), Map.of("telegram", Map.of("botToken", token))).andExpect(status().isOk());
    }

    private String holder() {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject("select lease_owner from bot_poll_state", String.class));
    }

    private int pollsWith(String last4) {
        return (int) telegram.calls("getUpdates").stream().filter(call -> call.tokenLast4().equals(last4)).count();
    }
}
