package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.members.DeliveryChannel;
import app.nadel.members.InvitationDeliveryResult;
import app.nadel.notifications.FakeBotApi;
import app.nadel.notifications.Mailpit;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The bot copy of the invitation (feature 020 §2.1, {@link InvitationLetters}) against Mailpit and the fake Bot API:
 * a member with an active Telegram link also gets the same set-password link from the cooperative's bot and the result
 * names both channels; without a bot or a link, or when the bot is refused, the letter alone is the invitation; the
 * bot never sends a link the letter did not; the log never carries the link, the chat or the address.
 */
@ExtendWith(OutputCaptureExtension.class)
class InvitationTelegramTests extends DeliveryTestSupport {

    private static final URI LINK = URI.create("http://localhost:5173/set-password?token=tgLINK123_-abc");
    private static final Instant EXPIRES = Instant.parse("2026-11-08T12:30:00Z");

    @Autowired
    InvitationLetters letters;

    @Test
    void aMemberWithTelegramGetsTheSameLinkFromTheBot(CapturedOutput output) throws Exception {
        mailbox(Mailpit.PASSWORD);
        bot();
        String email = "invitee-" + UUID.randomUUID() + "@delivery.test";
        UUID member = member("Телеграм", "EMAIL", email);
        long chat = link(member);

        InvitationDeliveryResult result = letters.deliver(tenant, member, email, LINK, EXPIRES);

        assertThat(result.isSent()).isTrue();
        assertThat(result.channels()).containsExactlyInAnyOrder(DeliveryChannel.EMAIL, DeliveryChannel.TELEGRAM);
        assertThat(mailpit.lettersTo(email)).hasSize(1);
        assertThat(telegram.sent(chat)).singleElement().satisfies(sent -> assertThat(sent.text())
                .contains(LINK.toString())
                .contains(email)
                .contains("08.11.2026 14:30")
                .contains("Розсилка"));
        assertThat(output.getAll()).contains("by the bot: SENT").doesNotContain(LINK.toString())
                .doesNotContain("tgLINK123").doesNotContain(email).doesNotContain(Long.toString(chat));
    }

    @Test
    void withoutABotOrALinkTheLetterIsTheInvitation() throws Exception {
        mailbox(Mailpit.PASSWORD);
        String email = "invitee-" + UUID.randomUUID() + "@delivery.test";
        UUID member = member("Телеграм", "EMAIL", email);
        long chat = link(member);

        // the member is linked, but the cooperative has no bot (any more)
        InvitationDeliveryResult noBot = letters.deliver(tenant, member, email, LINK, EXPIRES);
        assertThat(noBot.channels()).containsExactly(DeliveryChannel.EMAIL);
        assertThat(telegram.sent(chat)).isEmpty();

        // a bot, but the member has no Telegram link
        bot();
        UUID unlinked = member("Пошта", "EMAIL", "plain-" + UUID.randomUUID() + "@delivery.test");
        InvitationDeliveryResult noLink = letters.deliver(tenant, unlinked, "plain@delivery.test", LINK, EXPIRES);
        assertThat(noLink.channels()).containsExactly(DeliveryChannel.EMAIL);
        assertThat(telegram.sent()).noneMatch(sent -> sent.text().contains(LINK.toString()));
    }

    @Test
    void aRefusedBotNeverFailsTheInvitation() throws Exception {
        mailbox(Mailpit.PASSWORD);
        bot();
        String email = "invitee-" + UUID.randomUUID() + "@delivery.test";
        UUID member = member("Телеграм", "EMAIL", email);
        long chat = link(member);
        telegram.block(chat);

        InvitationDeliveryResult blocked = letters.deliver(tenant, member, email, LINK, EXPIRES);

        assertThat(blocked.isSent()).isTrue();
        assertThat(blocked.channels()).containsExactly(DeliveryChannel.EMAIL);
        assertThat(linked(member)).as("a blocked bot ends the link").isFalse();

        // Telegram itself fails: still the letter only
        UUID other = member("Інший", "EMAIL", "other-" + UUID.randomUUID() + "@delivery.test");
        long otherChat = link(other);
        telegram.failNext("sendMessage", FakeBotApi.Reply.error(500, "Internal Server Error"));
        InvitationDeliveryResult failed = letters.deliver(tenant, other, "other@delivery.test", LINK, EXPIRES);
        assertThat(failed.channels()).containsExactly(DeliveryChannel.EMAIL);
        assertThat(telegram.sent(otherChat)).isEmpty();
    }

    @Test
    void aBotWhoseStoredTokenCannotBeReadNeverFailsTheInvitation(CapturedOutput output) throws Exception {
        mailbox(Mailpit.PASSWORD);
        bot();
        String email = "invitee-" + UUID.randomUUID() + "@delivery.test";
        UUID member = member("Телеграм", "EMAIL", email);
        long chat = link(member);
        ownerJdbc().update("update delivery_settings set bot_token_enc = decode('00ff00ff00ff00ff00ff00ff00ff00ff"
                + "00ff00ff00ff00ff00ff00ff00ff00ff', 'hex') where tenant_id = ?", tenant);

        InvitationDeliveryResult result = letters.deliver(tenant, member, email, LINK, EXPIRES);

        assertThat(result.isSent()).isTrue();
        assertThat(result.channels()).containsExactly(DeliveryChannel.EMAIL);
        assertThat(telegram.sent(chat)).isEmpty();
        assertThat(output.getAll()).contains("by the bot failed").doesNotContain(LINK.toString())
                .doesNotContain("tgLINK123");
    }

    @Test
    void aLetterThatIsNotSentSendsNothingByTheBot() throws Exception {
        mailbox(Mailpit.PASSWORD);
        bot();
        String email = "invitee-" + UUID.randomUUID() + "@delivery.test";
        UUID member = member("Телеграм", "EMAIL", email);
        long chat = link(member);
        mailpit.refuseRecipients(true);

        InvitationDeliveryResult result = letters.deliver(tenant, member, email, LINK, EXPIRES);

        assertThat(result.isSent()).isFalse();
        assertThat(result.failure()).isEqualTo(InvitationDeliveryResult.Failure.SMTP_REJECTED);
        assertThat(telegram.sent(chat)).isEmpty();
    }
}
