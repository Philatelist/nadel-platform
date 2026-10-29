package app.nadel.notifications.internal;

import app.nadel.notifications.internal.DeliverySettingsService.BotToken;
import app.nadel.notifications.internal.telegram.TelegramClient;
import app.nadel.notifications.internal.telegram.TelegramProperties;
import app.nadel.tenancy.TenantContext;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the cooperatives' bots on this instance (feature 017 §2.5, ADR 0018): one {@link BotWorker} per cooperative
 * whose bot lease this instance holds. Every round ({@link #tick}, every {@code nadel.telegram.supervisor-interval},
 * 20 s, through {@link BotSchedule}):
 * <ol>
 *   <li>the cooperatives with a bot come from {@code notifications_bot_tenants()}, in a transaction without a
 *       cooperative;</li>
 *   <li>for each, bound with {@code TenantContext.runAs}: the token is opened and the lease in {@code bot_poll_state}
 *       claimed or renewed for {@code nadel.telegram.lease} (60 s) — only when this instance holds it or it ran out;
 *       </li>
 *   <li>a held bot gets a worker if it has none, a new one when the token changed (settings version), and none while
 *       Telegram refuses that token (401) until it changes; a bot held elsewhere, or no longer configured, loses its
 *       worker here.</li>
 * </ol>
 * So exactly one instance answers a bot; when it stops, another takes over once its lease ran out. The instance id is
 * random per JVM. A clean shutdown releases the leases.
 */
@Component
class BotSupervisor {

    /** This JVM's instance id. */
    static final String INSTANCE_ID = UUID.randomUUID().toString();

    private static final Logger log = LoggerFactory.getLogger(BotSupervisor.class);
    private static final Duration STOP_WAIT = Duration.ofSeconds(5);

    private final String instanceId;
    private final Duration lease;
    private final DeliverySettingsService settings;
    private final BotPollState state;
    private final TelegramClient telegram;
    private final BotConversation conversation;
    private final BotDialogue dialogue;
    private final TransactionTemplate transaction;
    private final Map<UUID, BotWorker> workers = new ConcurrentHashMap<>();
    /** The settings version whose token Telegram refused, per cooperative. */
    private final Map<UUID, Integer> refused = new ConcurrentHashMap<>();

    @Autowired
    BotSupervisor(TelegramProperties properties, DeliverySettingsService settings, BotPollState state,
            TelegramClient telegram, BotConversation conversation, BotDialogue dialogue,
            PlatformTransactionManager transactionManager) {
        this(INSTANCE_ID, properties.lease(), settings, state, telegram, conversation, dialogue, transactionManager);
    }

    /** A supervisor with its own instance id and lease (the tests run two in one JVM). */
    BotSupervisor(String instanceId, Duration lease, DeliverySettingsService settings, BotPollState state,
            TelegramClient telegram, BotConversation conversation, BotDialogue dialogue,
            PlatformTransactionManager transactionManager) {
        this.instanceId = instanceId;
        this.lease = lease;
        this.settings = settings;
        this.state = state;
        this.telegram = telegram;
        this.conversation = conversation;
        this.dialogue = dialogue;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** One round: claims or renews the leases and starts, restarts or stops the workers. */
    synchronized void tick() {
        List<UUID> tenants;
        try {
            tenants = transaction.execute(status -> state.botTenants());
        } catch (RuntimeException e) {
            log.warn("Bot supervisor could not list the bots: {}", e.getClass().getSimpleName());
            return;
        }
        Set<UUID> seen = new HashSet<>();
        for (UUID tenantId : tenants == null ? List.<UUID>of() : tenants) {
            seen.add(tenantId);
            try {
                TenantContext.runAs(tenantId, () -> {
                    supervise(tenantId);
                    return null;
                });
            } catch (RuntimeException e) {
                log.warn("Bot supervisor, cooperative {}: {}", tenantId, e.getClass().getSimpleName());
            }
        }
        for (UUID tenantId : Set.copyOf(workers.keySet())) {
            if (!seen.contains(tenantId)) {
                stop(tenantId);
            }
        }
    }

    /** Stops every worker of this instance and gives their leases up (shutdown; the tests between cases). */
    @PreDestroy
    synchronized void stopAll() {
        for (UUID tenantId : Set.copyOf(workers.keySet())) {
            stop(tenantId);
            try {
                TenantContext.runAs(tenantId, () -> transaction.execute(status -> {
                    state.release(tenantId, instanceId);
                    return null;
                }));
            } catch (RuntimeException e) {
                log.warn("Bot lease of cooperative {} not released: {}", tenantId, e.getClass().getSimpleName());
            }
        }
        refused.clear();
    }

    /** Stops every worker without giving the leases up: what a crashed instance leaves behind (tests only). */
    synchronized void abandon() {
        Set.copyOf(workers.keySet()).forEach(this::stop);
    }

    /** Whether this instance runs a worker for the cooperative now. */
    boolean running(UUID tenantId) {
        BotWorker worker = workers.get(tenantId);
        return worker != null && worker.alive();
    }

    private void supervise(UUID tenantId) {
        Optional<BotToken> token = settings.botToken();
        if (token.isEmpty()) {
            stop(tenantId);
            return;
        }
        int version = token.get().settingsVersion();
        Boolean held = transaction.execute(status -> state.claim(tenantId, instanceId, version, lease));
        if (!Boolean.TRUE.equals(held)) {
            stop(tenantId);
            return;
        }
        BotWorker worker = workers.get(tenantId);
        if (worker != null && worker.settingsVersion() == version && worker.alive()) {
            return;
        }
        if (worker != null) {
            stop(tenantId);
        }
        Integer refusedVersion = refused.get(tenantId);
        if (refusedVersion != null && refusedVersion == version) {
            return;
        }
        refused.remove(tenantId);
        BotWorker started = new BotWorker(tenantId, instanceId, token.get(), state, telegram, conversation,
                transaction, (tenant, refusedSettings) -> refused.put(tenant, refusedSettings));
        workers.put(tenantId, started);
        started.start();
        log.info("Bot of cooperative {} runs on this instance", tenantId);
    }

    private void stop(UUID tenantId) {
        BotWorker worker = workers.remove(tenantId);
        if (worker != null) {
            worker.stop(STOP_WAIT);
            dialogue.forget(tenantId);
        }
    }
}
