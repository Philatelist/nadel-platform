package app.nadel.notifications.internal;

import app.nadel.members.MemberContacts;
import app.nadel.members.MemberDirectory;
import app.nadel.notifications.internal.DeliveryRows.Queued;
import app.nadel.notifications.internal.DeliverySettingsService.BotToken;
import app.nadel.notifications.internal.mail.SmtpSettings;
import app.nadel.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the queued deliveries of one lane, a cooperative and a channel (feature 017 §2.4, the JobRunr job
 * {@link DispatchDeliveries}). One run:
 * <ol>
 *   <li>binds the cooperative ({@code TenantContext.runAs}) and claims the lane's lease ({@link DeliveryLanes},
 *       {@code nadel.delivery.lane-lease}); a lane held elsewhere is tried again after {@code busy-retry} (5 s);</li>
 *   <li>takes the due rows a few at a time in a short transaction, and for each: prepares the message in a short
 *       read-only transaction (the documents, the payment or the announcement, the opened mailbox or bot token, the member's address or
 *       chat), waits for its slot ({@link RateLimiters}), sends it outside any transaction, and records the outcome
 *       in a short transaction;</li>
 *   <li>stops after {@code run-limit} (60 s) and wakes the lane again at once, or when nothing is due and wakes it at
 *       the earliest next attempt, or when Telegram asked to wait and wakes it then.</li>
 * </ol>
 * <b>Attempts.</b> Three in all: now, after 1 min, after 5 min ({@code retry-delays}); then {@code FAILED} with the
 * last code. Permanent failures ({@code TELEGRAM_BLOCKED}, {@code NO_CONTACT}, {@code CHANNEL_NOT_CONFIGURED},
 * {@code SMTP_AUTH_FAILED}) fail at once; a blocked bot also ends the member's link ({@code BLOCKED}). Telegram's 429
 * moves the row by {@code retry_after} without counting an attempt and pauses the lane. An unexpected error while
 * preparing or sending counts as a {@code CONNECTION_FAILED} attempt, so it backs off too. JobRunr's own retries only
 * cover a crashed run. An outcome is recorded only while the row is still the one that was sent (its attempts
 * unchanged), so a retry asked meanwhile is not lost.
 * <p>
 * {@link #sweep} (every minute, {@link BotSchedule}) wakes every lane with due rows through
 * {@code notifications_due_lanes()}, so nothing waits after a restart or a lost job. Logs carry ids and codes only.
 */
@Component
class DeliveryDispatcher {

    /** This JVM's id as a lane holder. */
    static final String INSTANCE_ID = "delivery-" + UUID.randomUUID();

    /** The kinds sent: invoices, payment and reversal messages, announcements. */
    static final List<String> KINDS = List.of("INVOICE", DeliveryRows.PAYMENT, DeliveryRows.REVERSAL,
            DeliveryRows.ANNOUNCEMENT);

    private static final Logger log = LoggerFactory.getLogger(DeliveryDispatcher.class);
    private static final int BATCH = 20;

    /** What a run did (the tests read it). */
    record Run(boolean busy, int sent, int failed, int retried) {
    }

    /** A message ready to go, or the outcome that makes sending pointless. */
    private record Prepared(@Nullable DeliveryMessage message, @Nullable SmtpSettings smtp, @Nullable String to,
            int emailRate, @Nullable String token, long chatId, @Nullable SendOutcome outcome) {

        static Prepared failed(String code) {
            return new Prepared(null, null, null, 0, null, 0, SendOutcome.permanent(code));
        }
    }

    private final String instanceId;
    private final DeliveryRows rows;
    private final DeliveryLanes lanes;
    private final DeliveryQueue queue;
    private final InvoiceMessages invoices;
    private final PaymentMessages payments;
    private final AnnouncementMessages announcements;
    private final DeliverySettingsService settings;
    private final MemberDirectory members;
    private final TelegramLinks links;
    private final MailChannel mail;
    private final TelegramChannel telegram;
    private final RateLimiters limiters;
    private final Clock clock;
    private final DeliveryProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    @Autowired
    DeliveryDispatcher(DeliveryRows rows, DeliveryLanes lanes, DeliveryQueue queue, InvoiceMessages invoices,
            PaymentMessages payments, AnnouncementMessages announcements, DeliverySettingsService settings,
            MemberDirectory members, TelegramLinks links, MailChannel mail, TelegramChannel telegram,
            RateLimiters limiters, Clock clock, DeliveryProperties properties, JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this(INSTANCE_ID, rows, lanes, queue, invoices, payments, announcements, settings, members, links, mail,
                telegram, limiters, clock, properties, jdbc, transactionManager);
    }

    /** A dispatcher with its own holder id, clock and pace (the tests). */
    DeliveryDispatcher(String instanceId, DeliveryRows rows, DeliveryLanes lanes, DeliveryQueue queue,
            InvoiceMessages invoices, PaymentMessages payments, AnnouncementMessages announcements,
            DeliverySettingsService settings, MemberDirectory members, TelegramLinks links, MailChannel mail,
            TelegramChannel telegram, RateLimiters limiters, Clock clock, DeliveryProperties properties,
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.instanceId = instanceId;
        this.rows = rows;
        this.lanes = lanes;
        this.queue = queue;
        this.invoices = invoices;
        this.payments = payments;
        this.announcements = announcements;
        this.settings = settings;
        this.members = members;
        this.links = links;
        this.mail = mail;
        this.telegram = telegram;
        this.limiters = limiters;
        this.clock = clock;
        this.properties = properties;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** One run of the lane (the job). */
    Run dispatch(UUID tenantId, Channel channel) {
        if (channel == Channel.PAPER) {
            throw new IllegalArgumentException("Paper is printed, not sent");
        }
        return TenantContext.runAs(tenantId, () -> run(tenantId, channel));
    }

    /** Wakes every lane that has due rows (the one-minute sweep). */
    void sweep() {
        List<Object[]> due;
        try {
            due = transaction.execute(status -> jdbc.query(
                    "select tenant_id, channel from notifications_due_lanes(?)",
                    (rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)},
                    clock.instant().atOffset(java.time.ZoneOffset.UTC)));
        } catch (RuntimeException e) {
            log.warn("Delivery sweep could not list the lanes: {}", e.getClass().getSimpleName());
            return;
        }
        for (Object[] lane : due == null ? List.<Object[]>of() : due) {
            queue.wake((UUID) lane[0], Channel.valueOf((String) lane[1]));
        }
    }

    private Run run(UUID tenantId, Channel channel) {
        if (!claim(tenantId, channel)) {
            queue.schedule(tenantId, channel, clock.instant().plus(properties.busyRetry()));
            return new Run(true, 0, 0, 0);
        }
        Instant deadline = clock.instant().plus(properties.runLimit());
        Set<UUID> done = new HashSet<>();
        int sent = 0;
        int failed = 0;
        int retried = 0;
        Instant waitUntil = null;
        boolean outOfTime = false;
        try {
            outer:
            while (true) {
                List<Queued> batch = transaction.execute(status -> rows.due(channel, clock.instant(), BATCH, done,
                        KINDS));
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                for (Queued row : batch) {
                    if (!clock.instant().isBefore(deadline)) {
                        outOfTime = true;
                        break outer;
                    }
                    if (!claim(tenantId, channel)) {
                        break outer;
                    }
                    done.add(row.id());
                    SendOutcome outcome;
                    long preparedChat = 0;
                    try {
                        Prepared prepared = readOnly.execute(status -> prepare(row));
                        preparedChat = prepared.chatId();
                        outcome = prepared.outcome() != null ? prepared.outcome() : send(tenantId, row, prepared);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break outer;
                    } catch (RuntimeException e) {
                        // counted as a failed attempt, so the row backs off and ends FAILED instead of looping
                        log.warn("Delivery {} of cooperative {} not sent: {}", row.id(), tenantId,
                                e.getClass().getSimpleName());
                        outcome = SendOutcome.transientFailure(SendOutcome.CONNECTION_FAILED);
                    }
                    long chatId = preparedChat;
                    Instant now = clock.instant();
                    switch (outcome) {
                        case SendOutcome.Sent s -> {
                            transaction.execute(status -> rows.sent(row.id(), row.attempts(), now));
                            sent++;
                        }
                        case SendOutcome.Wait wait -> {
                            waitUntil = now.plus(wait.retryAfter());
                            Instant until = waitUntil;
                            transaction.execute(status -> rows.postpone(row.id(), row.attempts(), until));
                            break outer;
                        }
                        case SendOutcome.Failed failure -> {
                            boolean last = failure.permanent() || row.attempts() + 1 >= DeliveryProperties.ATTEMPTS;
                            transaction.execute(status -> {
                                if (last) {
                                    rows.failed(row.id(), row.attempts(), failure.code());
                                    if (SendOutcome.TELEGRAM_BLOCKED.equals(failure.code())) {
                                        links.unlinkChat(chatId, TelegramLinks.EndReason.BLOCKED);
                                    }
                                } else {
                                    rows.retryLater(row.id(), row.attempts(), failure.code(),
                                            now.plus(properties.retryDelays().get(row.attempts())));
                                }
                                return null;
                            });
                            if (last) {
                                failed++;
                            } else {
                                retried++;
                            }
                        }
                    }
                }
            }
        } finally {
            transaction.execute(status -> {
                lanes.release(tenantId, channel, instanceId);
                return null;
            });
        }
        if (outOfTime) {
            queue.wake(tenantId, channel);
        } else if (waitUntil != null) {
            queue.schedule(tenantId, channel, waitUntil);
        } else {
            Optional<Instant> next = transaction.execute(status -> rows.nextDue(channel, clock.instant(), KINDS));
            if (next != null) {
                next.ifPresent(at -> queue.schedule(tenantId, channel, at));
            }
        }
        log.info("Delivery lane {} of cooperative {}: {} sent, {} failed, {} to try again", channel, tenantId, sent,
                failed, retried);
        return new Run(false, sent, failed, retried);
    }

    private boolean claim(UUID tenantId, Channel channel) {
        return Boolean.TRUE.equals(transaction.execute(status -> lanes.claim(tenantId, channel, instanceId,
                properties.laneLease())));
    }

    /** What the row needs to be sent, read in one short transaction. */
    private Prepared prepare(Queued row) {
        DeliveryMessage message = message(row);
        if (message == null) {
            return Prepared.failed(SendOutcome.NO_CONTACT);
        }
        if (row.channel() == Channel.EMAIL) {
            Optional<SmtpSettings> smtp = settings.mailbox();
            if (smtp.isEmpty()) {
                return Prepared.failed(SendOutcome.CHANNEL_NOT_CONFIGURED);
            }
            MemberContacts contacts = members.contacts(Set.of(row.memberId())).get(row.memberId());
            if (contacts == null || contacts.email() == null || contacts.email().isBlank()) {
                return Prepared.failed(SendOutcome.NO_CONTACT);
            }
            return new Prepared(message, smtp.get(), contacts.email(), settings.current().emailRatePerSecond(), null,
                    0, null);
        }
        Optional<BotToken> token = settings.botToken();
        if (token.isEmpty()) {
            return Prepared.failed(SendOutcome.CHANNEL_NOT_CONFIGURED);
        }
        Optional<Long> chat = links.chatOfMember(row.memberId());
        if (chat.isEmpty()) {
            return Prepared.failed(SendOutcome.NO_CONTACT);
        }
        return new Prepared(message, null, null, 0, token.get().token(), chat.get(), null);
    }

    /**
     * The message of the row: an invoice, a payment or reversal message, or an announcement; null when its source is
     * gone.
     */
    private @Nullable DeliveryMessage message(Queued row) {
        if (row.invoiceId() != null) {
            return invoices.of(row.invoiceId()).orElse(null);
        }
        if (row.paymentId() != null) {
            return payments.of(row.paymentId(), row.kind()).orElse(null);
        }
        if (row.announcementId() != null) {
            return announcements.of(row.announcementId()).orElse(null);
        }
        return null;
    }

    /** Waits for the slot and sends, outside any transaction. */
    private SendOutcome send(UUID tenantId, Queued row, Prepared prepared) throws InterruptedException {
        if (row.channel() == Channel.EMAIL) {
            limiters.email(tenantId, prepared.emailRate());
            return mail.send(tenantId, prepared.smtp(), prepared.to(), prepared.message());
        }
        limiters.telegram(tenantId, row.memberId());
        return telegram.send(prepared.token(), prepared.chatId(), prepared.message());
    }
}
