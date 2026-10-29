package app.nadel.notifications.internal;

import app.nadel.documents.DocumentFormats;
import app.nadel.meters.MemberReadings;
import app.nadel.meters.MemberReadings.ReadingDecisionView;
import app.nadel.meters.ReadingDecided;
import app.nadel.meters.ReadingStatus;
import app.nadel.notifications.internal.DeliverySettingsService.BotToken;
import app.nadel.notifications.internal.telegram.TelegramClient;
import app.nadel.tenancy.TenantContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tells a member in Telegram that the board approved or rejected the reading they submitted (feature 017 §2.5):
 * «Показання 12 345 по лічильнику ЕЛ-9/2 прийнято» or «… відхилено: {причина}», the reason as the board typed it.
 * <p>
 * The ADR 0007 listener pattern: {@code @Async @TransactionalEventListener} on {@link ReadingDecided} without
 * {@code @Transactional}, the event's cooperative bound ({@link TenantContext#runAs}) before a short read-only
 * transaction reads the bot token, the submitter's active link ({@link TelegramLinks#chatOfMember}) and the reading
 * ({@link MemberReadings#decision}); nothing is sent when the cooperative has no bot or the member no active link,
 * whatever channel they chose for invoices. The message goes straight through the bot outside any transaction, at the
 * bot's pace ({@link RateLimiters}), not through the delivery register: a reading message is not one of the register's
 * kinds (V29 {@code delivery.kind}), it is a reply in the bot's conversation like the bot's own answers, and a delivery
 * row would need a migration for no gain. Idempotent enough: one message per (reading, decision) on this instance (a
 * redelivered event finds it sent); only a crash between the send and the outbox's completion could repeat it. A 403
 * ends the link ({@link BotChat}). Logs carry the cooperative id only, never the value, the reason or the chat.
 */
@Component
class ReadingMessages {

    private static final Logger log = LoggerFactory.getLogger(ReadingMessages.class);
    private static final int REMEMBERED = 10_000;

    private final MemberReadings readings;
    private final DeliverySettingsService settings;
    private final TelegramLinks links;
    private final TelegramClient telegram;
    private final RateLimiters limiters;
    private final TransactionTemplate readOnly;
    /** The (reading, decision) pairs told on this instance, oldest first, bounded. */
    private final Set<String> told = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > REMEMBERED;
        }
    });

    ReadingMessages(MemberReadings readings, DeliverySettingsService settings, TelegramLinks links,
            TelegramClient telegram, RateLimiters limiters, PlatformTransactionManager transactionManager) {
        this.readings = readings;
        this.settings = settings;
        this.links = links;
        this.telegram = telegram;
        this.limiters = limiters;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Async
    @TransactionalEventListener
    void on(ReadingDecided event) {
        tell(event);
    }

    /** Tells the member about the decision; whether a message went. */
    boolean tell(ReadingDecided event) {
        String key = event.readingId() + ":" + event.status();
        synchronized (told) {
            if (!told.add(key)) {
                return false;
            }
        }
        boolean sent = TenantContext.runAs(event.tenantId(), () -> {
            Optional<Prepared> prepared = readOnly.execute(status -> prepare(event));
            if (prepared == null || prepared.isEmpty()) {
                return false;
            }
            Prepared message = prepared.get();
            try {
                limiters.telegram(event.tenantId(), event.submitterMemberId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return new BotChat(event.tenantId(), message.token(), message.chatId(), telegram, links)
                    .send(message.text(), null);
        });
        if (!sent) {
            synchronized (told) {
                told.remove(key);
            }
        }
        log.debug("Reading decision message of cooperative {}: {}", event.tenantId(), sent ? "sent" : "not sent");
        return sent;
    }

    /** The token, the chat and the text, or empty when there is nobody to tell. */
    private Optional<Prepared> prepare(ReadingDecided event) {
        Optional<BotToken> token = settings.botToken();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        Optional<Long> chat = links.chatOfMember(event.submitterMemberId());
        Optional<ReadingDecisionView> reading = readings.decision(event.readingId());
        if (chat.isEmpty() || reading.isEmpty()) {
            return Optional.empty();
        }
        NotificationTexts texts = NotificationTexts.uk();
        ReadingDecisionView view = reading.get();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("value", DocumentFormats.number(view.value().value()));
        params.put("meter", view.serial());
        params.put("reason", view.reason() == null ? "" : view.reason());
        String text = event.status() == ReadingStatus.APPROVED
                ? texts.text("bot.readings.approved", params)
                : texts.text("bot.readings.rejected", params);
        return Optional.of(new Prepared(token.get(), chat.get(), text));
    }

    /** A message ready to go. Never prints the chat or the text. */
    private record Prepared(BotToken token, long chatId, String text) {

        @Override
        public String toString() {
            return "Prepared[]";
        }
    }
}
