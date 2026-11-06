package app.nadel.notifications.internal;

import app.nadel.members.DeliveryChannel;
import app.nadel.members.InvitationDelivery;
import app.nadel.members.InvitationDeliveryResult;
import app.nadel.members.InvitationDeliveryResult.Failure;
import app.nadel.notifications.internal.DeliverySettingsService.BotToken;
import app.nadel.notifications.internal.mail.Letter;
import app.nadel.notifications.internal.mail.Letters;
import app.nadel.notifications.internal.mail.MailOutcome;
import app.nadel.notifications.internal.mail.SmtpSettings;
import app.nadel.notifications.internal.telegram.TelegramClient;
import app.nadel.tenancy.TenantContext;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Serves {@code members.InvitationDelivery} (feature 020 §2.1): the letter «Задайте пароль» with the one-time
 * set-password link, from the cooperative's own mailbox ({@link DeliverySettingsService#mailbox}) through
 * {@link Letters}, which checks the mail server's host with {@code SmtpHostPolicy} before it connects. Synchronous, like
 * the test letter ({@link DeliveryChecks}): the board sees at once whether it went out, and the link never enters the
 * delivery register, the outbox or the audit log.
 * <p>
 * The texts are {@code invitation.*} of the Ukrainian bundle ({@link NotificationTexts}, ADR 0012); the expiry is shown
 * in the cooperative's zone (the application {@link Clock}'s).
 * <p>
 * <b>Telegram</b>, best effort: once the letter is accepted, a member with an active Telegram link
 * ({@link TelegramLinks#chatOfMember}) of a cooperative with a bot also gets the same link from the bot
 * ({@code invitation.telegram}), at the bot's pace ({@link RateLimiters}), straight through {@link TelegramClient} like
 * the reading messages ({@link BotChat}: a 403 ends the link). It adds {@code TELEGRAM} to the channels of the result;
 * no bot, no link or a refusal only leaves it out and never fails the invitation. The letter decides whether the
 * invitation was sent: the bot never sends a link the letter did not.
 * <p>
 * Logs carry the cooperative id, the member id and an outcome code only: never the address, the link or a text.
 */
@Component
class InvitationLetters implements InvitationDelivery {

    private static final Logger log = LoggerFactory.getLogger(InvitationLetters.class);
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final DeliverySettingsService settings;
    private final Letters letters;
    private final TenantDirectory tenants;
    private final TelegramLinks links;
    private final TelegramClient telegram;
    private final RateLimiters limiters;
    private final Clock clock;

    InvitationLetters(DeliverySettingsService settings, Letters letters, TenantDirectory tenants, TelegramLinks links,
            TelegramClient telegram, RateLimiters limiters, Clock clock) {
        this.settings = settings;
        this.letters = letters;
        this.tenants = tenants;
        this.links = links;
        this.telegram = telegram;
        this.limiters = limiters;
        this.clock = clock;
    }

    @Override
    public boolean emailReady(UUID tenantId) {
        return TenantContext.runAs(tenantId, () -> settings.current().emailConfigured());
    }

    @Override
    public InvitationDeliveryResult deliver(UUID tenantId, UUID memberId, String email, URI link,
            Instant expiresAt) {
        return TenantContext.runAs(tenantId, () -> {
            Optional<SmtpSettings> mailbox = settings.mailbox();
            if (mailbox.isEmpty()) {
                log.info("Invitation letter of cooperative {} to member {}: NOT_CONFIGURED", tenantId, memberId);
                return InvitationDeliveryResult.failed(Failure.NOT_CONFIGURED);
            }
            NotificationTexts texts = NotificationTexts.uk();
            String cooperative = tenants.current().map(TenantSummary::name).orElse("");
            Map<String, String> params = Map.of(
                    "cooperative", cooperative,
                    "email", email,
                    "link", link.toString(),
                    "expires", EXPIRY.format(expiresAt.atZone(clock.getZone())));
            MailOutcome outcome = letters.send(tenantId, mailbox.get(), new Letter(email,
                    texts.text("invitation.subject", params), texts.text("invitation.body", params), null, null,
                    null));
            log.info("Invitation letter of cooperative {} to member {}: {}", tenantId, memberId,
                    outcome.accepted() ? "ACCEPTED" : outcome.failure());
            if (!outcome.accepted()) {
                return InvitationDeliveryResult.failed(Failure.valueOf(outcome.failure().name()));
            }
            Set<DeliveryChannel> channels = EnumSet.of(DeliveryChannel.EMAIL);
            if (sendByBot(tenantId, memberId, texts.text("invitation.telegram", params))) {
                channels.add(DeliveryChannel.TELEGRAM);
            }
            return InvitationDeliveryResult.sent(channels);
        });
    }

    /** The bot copy of the link, best effort; whether it went. Never logs the chat or the text. */
    private boolean sendByBot(UUID tenantId, UUID memberId, String text) {
        try {
            Optional<BotToken> token = settings.botToken();
            Optional<Long> chat = token.isEmpty() ? Optional.empty() : links.chatOfMember(memberId);
            if (token.isEmpty() || chat.isEmpty()) {
                return false;
            }
            limiters.telegram(tenantId, memberId);
            boolean sent = new BotChat(tenantId, token.get(), chat.get(), telegram, links).send(text, null);
            log.info("Invitation message of cooperative {} to member {} by the bot: {}", tenantId, memberId,
                    sent ? "SENT" : "NOT_SENT");
            return sent;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            log.warn("Invitation message of cooperative {} to member {} by the bot failed: {}", tenantId, memberId,
                    e.getClass().getSimpleName());
            return false;
        }
    }
}
