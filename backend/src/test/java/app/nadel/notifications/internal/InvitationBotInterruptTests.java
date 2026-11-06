package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.nadel.members.DeliveryChannel;
import app.nadel.members.InvitationDeliveryResult;
import app.nadel.notifications.internal.DeliverySettingsService.BotToken;
import app.nadel.notifications.internal.mail.Letters;
import app.nadel.notifications.internal.mail.MailOutcome;
import app.nadel.notifications.internal.mail.SmtpSettings;
import app.nadel.notifications.internal.telegram.TelegramClient;
import app.nadel.tenancy.TenantDirectory;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The bot copy of the invitation (feature 020 §2.1, {@link InvitationLetters}) when the wait for the bot's slot is
 * interrupted: the letter was accepted, so the invitation counts as sent by e-mail alone, nothing goes to the bot, and
 * the interrupt stays set for the caller. {@link InvitationTelegramTests} covers the bot against the fake Bot API.
 */
class InvitationBotInterruptTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final URI LINK = URI.create("http://localhost:5173/set-password?token=interruptTOKEN_1");

    private final DeliverySettingsService settings = mock(DeliverySettingsService.class);
    private final Letters letters = mock(Letters.class);
    private final TelegramLinks links = mock(TelegramLinks.class);
    private final TelegramClient telegram = mock(TelegramClient.class);
    private final RateLimiters limiters = mock(RateLimiters.class);
    private final InvitationLetters invitations = new InvitationLetters(settings, letters, mock(TenantDirectory.class),
            links, telegram, limiters, Clock.systemUTC());

    @AfterEach
    void clearTheInterrupt() {
        Thread.interrupted();
    }

    @Test
    void anInterruptedWaitForTheBotLeavesTheLetterAsTheInvitation() throws Exception {
        when(settings.mailbox()).thenReturn(Optional.of(mock(SmtpSettings.class)));
        when(letters.send(eq(TENANT), any(), any())).thenReturn(new MailOutcome(true, "250 OK", null));
        when(settings.botToken()).thenReturn(Optional.of(new BotToken("123:bot-token", 1)));
        when(links.chatOfMember(MEMBER)).thenReturn(Optional.of(42L));
        doThrow(new InterruptedException()).when(limiters).telegram(TENANT, MEMBER);

        InvitationDeliveryResult result = invitations.deliver(TENANT, MEMBER, "invitee@coop.example", LINK,
                Instant.parse("2026-11-08T12:30:00Z"));

        assertThat(result.isSent()).isTrue();
        assertThat(result.channels()).containsExactly(DeliveryChannel.EMAIL);
        assertThat(Thread.currentThread().isInterrupted()).as("the interrupt is restored for the caller").isTrue();
        verifyNoInteractions(telegram);
    }
}
