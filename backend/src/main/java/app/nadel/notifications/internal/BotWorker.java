package app.nadel.notifications.internal;

import app.nadel.notifications.internal.DeliverySettingsService.BotToken;
import app.nadel.notifications.internal.telegram.TelegramClient;
import app.nadel.notifications.internal.telegram.TelegramException;
import app.nadel.notifications.internal.telegram.TelegramUpdate;
import app.nadel.tenancy.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The long poll of one cooperative's bot on this instance (feature 017 §2.5), on a virtual thread: {@code getUpdates}
 * from the saved offset, then each update in turn. Before an update is answered it is taken in the database
 * ({@link BotPollState#take}: the offset moves past it only while this instance holds the lease), so an update is
 * answered at most once even if two instances poll for a moment, and a reading submitted from the bot (slice 5) is
 * never submitted twice. An instance that finds it no longer holds the lease stops.
 * <p>
 * Telegram refusing the token (401) stops the worker and reports it ({@code onUnauthorized}), so the supervisor does
 * not restart it until the token changes; other failures are retried after a growing pause (up to 30 s; a 429 waits
 * as long as Telegram asks). Logs carry the cooperative id and codes only.
 */
final class BotWorker {

    private static final Logger log = LoggerFactory.getLogger(BotWorker.class);
    private static final Duration MAX_PAUSE = Duration.ofSeconds(30);

    /** Told when Telegram refused the token of a settings version. */
    @FunctionalInterface
    interface UnauthorizedListener {
        void unauthorized(UUID tenantId, int settingsVersion);
    }

    private final UUID tenantId;
    private final String owner;
    private final BotToken token;
    private final BotPollState state;
    private final TelegramClient telegram;
    private final BotConversation conversation;
    private final TransactionTemplate transaction;
    private final UnauthorizedListener onUnauthorized;
    private final Object waiting = new Object();
    private volatile boolean stopped;
    /** True while the thread waits on Telegram or pauses: only then may {@link #stop} interrupt it, never mid-SQL. */
    private boolean interruptible;
    private Thread thread;

    BotWorker(UUID tenantId, String owner, BotToken token, BotPollState state, TelegramClient telegram,
            BotConversation conversation, TransactionTemplate transaction, UnauthorizedListener onUnauthorized) {
        this.tenantId = tenantId;
        this.owner = owner;
        this.token = token;
        this.state = state;
        this.telegram = telegram;
        this.conversation = conversation;
        this.transaction = transaction;
        this.onUnauthorized = onUnauthorized;
    }

    int settingsVersion() {
        return token.settingsVersion();
    }

    void start() {
        synchronized (waiting) {
            thread = Thread.ofVirtual().name("telegram-bot-" + tenantId).unstarted(this::run);
        }
        thread.start();
    }

    boolean alive() {
        Thread running = thread;
        return !stopped && running != null && running.isAlive();
    }

    /** Stops the poll (an open long poll is interrupted) and waits up to {@code wait} for the thread to end. */
    void stop(Duration wait) {
        Thread running;
        synchronized (waiting) {
            stopped = true;
            running = thread;
            if (running != null && interruptible) {
                running.interrupt();
            }
        }
        if (running == null) {
            return;
        }
        try {
            running.join(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        long offset;
        try {
            long last = inTenant(() -> state.lastUpdateId(tenantId));
            offset = last == 0 ? 0 : last + 1;
        } catch (RuntimeException e) {
            log.warn("Bot of cooperative {} could not start: {}", tenantId, e.getClass().getSimpleName());
            stopped = true;
            return;
        }
        Duration pause = Duration.ofSeconds(1);
        while (!stopped) {
            List<TelegramUpdate> updates;
            try {
                if (!enterWait()) {
                    return;
                }
                try {
                    updates = telegram.getUpdates(token.token(), offset);
                } finally {
                    leaveWait();
                }
                pause = Duration.ofSeconds(1);
            } catch (TelegramException e) {
                if (stopped) {
                    return;
                }
                if (e.kind() == TelegramException.Kind.UNAUTHORIZED) {
                    log.warn("Bot of cooperative {} stopped: Telegram refused its token", tenantId);
                    stopped = true;
                    onUnauthorized.unauthorized(tenantId, token.settingsVersion());
                    return;
                }
                Duration wait = e.retryAfter().orElse(pause);
                log.warn("Bot poll of cooperative {} failed: {}; next try in {} s", tenantId, e.kind(),
                        wait.toSeconds());
                pause = pause.multipliedBy(2).compareTo(MAX_PAUSE) > 0 ? MAX_PAUSE : pause.multipliedBy(2);
                sleep(wait);
                continue;
            }
            for (TelegramUpdate update : updates) {
                if (stopped) {
                    return;
                }
                offset = Math.max(offset, update.updateId() + 1);
                if (!handle(update)) {
                    stopped = true;
                    log.info("Bot of cooperative {} stopped here: the lease is held elsewhere", tenantId);
                    return;
                }
            }
        }
    }

    /** Takes and answers one update; false when this instance no longer holds the bot. */
    private boolean handle(TelegramUpdate update) {
        try {
            boolean mine = inTenant(() -> state.take(tenantId, owner, token.settingsVersion(), update.updateId()));
            if (!mine) {
                return inTenant(() -> state.holds(tenantId, owner, token.settingsVersion()));
            }
            TenantContext.runAs(tenantId, () -> {
                conversation.handle(token, update);
                return null;
            });
        } catch (RuntimeException e) {
            // never the exception's message: it may quote member input
            log.warn("Bot update of cooperative {} failed: {}", tenantId, e.getClass().getSimpleName());
        }
        return true;
    }

    private <T> T inTenant(Supplier<T> work) {
        return TenantContext.runAs(tenantId, () -> transaction.execute(status -> work.get()));
    }

    private void sleep(Duration wait) {
        if (!enterWait()) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            stopped = true;
        } finally {
            leaveWait();
        }
    }

    /** Marks the thread interruptible; false when the worker is already stopped. */
    private boolean enterWait() {
        synchronized (waiting) {
            if (stopped) {
                return false;
            }
            interruptible = true;
            return true;
        }
    }

    /** Ends the interruptible part and clears an interrupt that arrived in it (the loop then sees {@code stopped}). */
    private void leaveWait() {
        synchronized (waiting) {
            interruptible = false;
            Thread.interrupted();
        }
    }
}
