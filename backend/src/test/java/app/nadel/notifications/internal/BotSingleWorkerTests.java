package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.notifications.FakeBotApi.Reply;
import app.nadel.notifications.FakeBotApi.Sent;
import app.nadel.notifications.internal.telegram.TelegramClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * One bot worker per cooperative across application instances (feature 017 §2.5): two supervisors with their own
 * instance ids over the one database stand for two copies of the application. Exactly one of them answers each
 * update; when the holder dies, the other takes the bot over once the lease ran out; a new token restarts the bot,
 * and a token Telegram refuses stops it until the token changes. A worker that finds the lease taken by another
 * instance stops without answering; a bot whose token is gone loses its worker at the next round. A failed poll is
 * tried again after a growing pause (as long as Telegram asks after a 429), and a stop ends that pause at once. A
 * database failure in a round, at a bot's start or while an update is taken is logged with codes only and never stops
 * the other work.
 */
@ExtendWith(OutputCaptureExtension.class)
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

    @Autowired
    DeliveryDispatcher dispatcher;

    @Autowired
    JdbcTemplate jdbc;

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

    @Test
    void aWorkerThatFindsTheLeaseTakenElsewhereStopsWithoutAnswering() throws Exception {
        saveToken(TOKEN);
        first = instance("instance-a");
        first.tick();
        assertThat(first.running(DEMO_TENANT)).isTrue();

        // another instance took the bot over meanwhile (this one missed its renewal)
        ownerJdbc().update("update bot_poll_state set lease_owner = 'instance-b' where tenant_id = ?", DEMO_TENANT);
        long chat = chat();
        long update = telegram.pushMessage(TOKEN, chat, "/help");

        await().atMost(Duration.ofSeconds(10)).until(() -> !first.running(DEMO_TENANT));
        assertThat(telegram.sent(chat)).isEmpty();
        // the update is left for the holder: its offset did not move
        Long taken = inTenant(DEMO_TENANT, sql -> sql.queryForObject("select last_update_id from bot_poll_state",
                Long.class));
        assertThat(taken).isLessThan(update);
        assertThat(holder()).isEqualTo("instance-b");
    }

    @Test
    void aBotWhoseTokenIsGoneLosesItsWorkerAtTheNextRound() throws Exception {
        saveToken(TOKEN);
        first = instance("instance-a");
        first.tick();
        assertThat(first.running(DEMO_TENANT)).isTrue();

        ownerJdbc().update("update delivery_settings set bot_token_enc = null, bot_token_last4 = null "
                + "where tenant_id = ?", DEMO_TENANT);
        first.tick();

        assertThat(first.running(DEMO_TENANT)).isFalse();
        long chat = chat();
        telegram.pushMessage(TOKEN, chat, "/help");
        Thread.sleep(500);
        assertThat(telegram.sent(chat)).isEmpty();
    }

    @Test
    void theScheduledRoundStartsTheSavedBot() throws Exception {
        saveToken(TOKEN);
        first = instance("instance-a");

        new BotSchedule(first, dispatcher).tick();

        assertThat(first.running(DEMO_TENANT)).isTrue();
        assertThat(lastReply(chat(), "/help", 1).text()).startsWith("Це бот кооперативу");
    }

    @Test
    void aFailedPollIsTriedAgainAfterAGrowingPauseAndTheBotAnswersAfterwards(CapturedOutput output)
            throws Exception {
        telegram.failNext("getUpdates", Reply.error(502, "Bad Gateway"));
        telegram.failNext("getUpdates", Reply.error(502, "Bad Gateway"));
        saveToken(TOKEN);
        first = instance("instance-a");
        first.tick();
        long chat = chat();

        telegram.pushMessage(TOKEN, chat, "/help");

        await().atMost(Duration.ofSeconds(15)).until(() -> telegram.sent(chat).size() == 1);
        assertThat(telegram.sent(chat).getFirst().text()).startsWith("Це бот кооперативу");
        assertThat(telegram.calls("getUpdates").size()).isGreaterThanOrEqualTo(3);
        assertThat(output.getAll()).contains("failed: UNAVAILABLE; next try in 1 s")
                .contains("failed: UNAVAILABLE; next try in 2 s")
                .doesNotContain(TOKEN);
        assertThat(first.running(DEMO_TENANT)).isTrue();
    }

    @Test
    void aPollTelegramAsksToSlowDownWaitsAsLongAsAskedAndAStopEndsTheWait(CapturedOutput output)
            throws Exception {
        telegram.failNext("getUpdates", Reply.tooManyRequests(30));
        saveToken(TOKEN);
        first = instance("instance-a");
        first.tick();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> output.getAll().contains("failed: TOO_MANY_REQUESTS; next try in 30 s"));
        assertThat(first.running(DEMO_TENANT)).isTrue();
        int polls = telegram.calls("getUpdates").size();

        long started = System.nanoTime();
        first.stopAll();
        Duration stopping = Duration.ofNanos(System.nanoTime() - started);

        // the 30 s wait is cut short (the stop does not sit out its own 5 s wait either)
        assertThat(stopping).isLessThan(Duration.ofSeconds(3));
        assertThat(first.running(DEMO_TENANT)).isFalse();
        Thread.sleep(300);
        assertThat(telegram.calls("getUpdates")).hasSize(polls);
    }

    @Test
    void theSupervisorRidesOutDatabaseFailuresAndTheBotKeepsAnswering(CapturedOutput output) throws Exception {
        FlakyPollState flaky = new FlakyPollState(jdbc);
        saveToken(TOKEN);
        first = instance("instance-a", flaky);
        first.tick();
        assertThat(first.running(DEMO_TENANT)).isTrue();

        // the list of bots cannot be read: the round does nothing
        flaky.listFails = true;
        first.tick();
        assertThat(output.getAll()).contains("Bot supervisor could not list the bots: IllegalStateException");
        assertThat(first.running(DEMO_TENANT)).isTrue();
        long chat = chat();
        assertThat(lastReply(chat, "/help", 1).text()).startsWith("Це бот кооперативу");

        // the lease of one cooperative cannot be renewed: its worker goes on until the lease is lost
        flaky.listFails = false;
        flaky.claimFails = true;
        first.tick();
        assertThat(output.getAll()).contains("Bot supervisor, cooperative " + DEMO_TENANT + ": IllegalStateException");
        assertThat(first.running(DEMO_TENANT)).isTrue();
        assertThat(lastReply(chat, "/help", 2).text()).startsWith("Це бот кооперативу");

        // the lease cannot be given up at the stop: the worker stops anyway, the lease runs out by itself
        flaky.claimFails = false;
        flaky.releaseFails = true;
        first.stopAll();
        assertThat(output.getAll()).contains("Bot lease of cooperative " + DEMO_TENANT + " not released: "
                + "IllegalStateException");
        assertThat(first.running(DEMO_TENANT)).isFalse();
        assertThat(holder()).isEqualTo("instance-a");
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    @Test
    void aBotThatCannotReadWhereItStandsDoesNotPollAndStartsAgainAtTheNextRound(CapturedOutput output)
            throws Exception {
        FlakyPollState flaky = new FlakyPollState(jdbc);
        flaky.startFails = true;
        saveToken(TOKEN);
        first = instance("instance-a", flaky);

        first.tick();

        await().atMost(Duration.ofSeconds(10)).until(() -> !first.running(DEMO_TENANT));
        assertThat(output.getAll()).contains("Bot of cooperative " + DEMO_TENANT + " could not start: "
                + "IllegalStateException");
        assertThat(telegram.calls("getUpdates")).isEmpty();

        flaky.startFails = false;
        first.tick();
        assertThat(first.running(DEMO_TENANT)).isTrue();
        assertThat(lastReply(chat(), "/help", 1).text()).startsWith("Це бот кооперативу");
    }

    @Test
    void anUpdateThatCannotBeTakenIsSkippedWithoutItsTextAndTheNextIsAnswered(CapturedOutput output)
            throws Exception {
        FlakyPollState flaky = new FlakyPollState(jdbc);
        flaky.takeFailures.set(1);
        saveToken(TOKEN);
        first = instance("instance-a", flaky);
        first.tick();
        long chat = chat();

        telegram.pushMessage(TOKEN, chat, "приватне повідомлення");
        long next = telegram.pushMessage(TOKEN, chat, "/help");

        awaitTaken(next);
        await().atMost(Duration.ofSeconds(10)).until(() -> telegram.sent(chat).size() == 1);
        Thread.sleep(300);
        assertThat(telegram.sent(chat)).singleElement()
                .satisfies(reply -> assertThat(reply.text()).startsWith("Це бот кооперативу"));
        assertThat(output.getAll()).contains("Bot update of cooperative " + DEMO_TENANT + " failed: "
                + "IllegalStateException").doesNotContain("приватне повідомлення");
        assertThat(first.running(DEMO_TENANT)).isTrue();
    }

    private BotSupervisor instance(String id) {
        return instance(id, state);
    }

    private BotSupervisor instance(String id, BotPollState pollState) {
        return new BotSupervisor(id, LEASE, settings, pollState, client, conversation, dialogue, transactionManager);
    }

    /** The poll state over the real table, with database failures the test switches on. */
    static final class FlakyPollState extends BotPollState {

        volatile boolean listFails;
        volatile boolean claimFails;
        volatile boolean startFails;
        volatile boolean releaseFails;
        final AtomicInteger takeFailures = new AtomicInteger();

        FlakyPollState(JdbcTemplate jdbc) {
            super(jdbc);
        }

        @Override
        List<UUID> botTenants() {
            fail(listFails);
            return super.botTenants();
        }

        @Override
        boolean claim(UUID tenantId, String owner, int settingsVersion, Duration lease) {
            fail(claimFails);
            return super.claim(tenantId, owner, settingsVersion, lease);
        }

        @Override
        long lastUpdateId(UUID tenantId) {
            fail(startFails);
            return super.lastUpdateId(tenantId);
        }

        @Override
        boolean take(UUID tenantId, String owner, int settingsVersion, long updateId) {
            fail(takeFailures.getAndDecrement() > 0);
            return super.take(tenantId, owner, settingsVersion, updateId);
        }

        @Override
        void release(UUID tenantId, String owner) {
            fail(releaseFails);
            super.release(tenantId, owner);
        }

        private static void fail(boolean failing) {
            if (failing) {
                throw new IllegalStateException("The database is not reachable");
            }
        }
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
