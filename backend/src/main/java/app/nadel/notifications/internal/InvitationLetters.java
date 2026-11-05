package app.nadel.notifications.internal;

import app.nadel.members.DeliveryChannel;
import app.nadel.members.InvitationDelivery;
import app.nadel.members.InvitationDeliveryResult;
import app.nadel.members.InvitationDeliveryResult.Failure;
import app.nadel.notifications.internal.mail.Letter;
import app.nadel.notifications.internal.mail.Letters;
import app.nadel.notifications.internal.mail.MailOutcome;
import app.nadel.notifications.internal.mail.SmtpSettings;
import app.nadel.tenancy.TenantContext;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
 * in the cooperative's zone (the application {@link Clock}'s). Telegram: the bot copy of the link for a member with an
 * active Telegram link comes in a later slice (feature 020 slice 6) and joins the channels of the result here; until
 * then the letter is the only channel.
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
    private final Clock clock;

    InvitationLetters(DeliverySettingsService settings, Letters letters, TenantDirectory tenants, Clock clock) {
        this.settings = settings;
        this.letters = letters;
        this.tenants = tenants;
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
            return InvitationDeliveryResult.sent(Set.of(DeliveryChannel.EMAIL));
        });
    }
}
