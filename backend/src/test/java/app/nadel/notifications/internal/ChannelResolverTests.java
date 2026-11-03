package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.members.DeliveryChannel;
import app.nadel.notifications.internal.ChannelResolver.Resolution;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The channel rule of feature 017 §2.3, one test per row of its table (and each way a row's situation can arise), and
 * its form for an announcement through chosen channels (§2.6).
 */
class ChannelResolverTests {

    private static Resolution resolve(DeliveryChannel preferred, boolean hasEmail, boolean linked, boolean mailbox,
            boolean bot) {
        return ChannelResolver.resolve(preferred, hasEmail, linked, mailbox, bot);
    }

    @Test
    void telegramLinkedWithABotGoesByTelegram() {
        assertThat(resolve(DeliveryChannel.TELEGRAM, false, true, false, true))
                .isEqualTo(new Resolution(Channel.TELEGRAM, null));
        assertThat(resolve(DeliveryChannel.TELEGRAM, true, true, true, true).channel()).isEqualTo(Channel.TELEGRAM);
    }

    @Test
    void telegramNotLinkedOrWithoutABotFallsBackToEmailWhenPossibleOtherwisePaper() {
        // not linked
        assertThat(resolve(DeliveryChannel.TELEGRAM, true, false, true, true).channel()).isEqualTo(Channel.EMAIL);
        // linked, but the bot is gone
        assertThat(resolve(DeliveryChannel.TELEGRAM, true, true, true, false).channel()).isEqualTo(Channel.EMAIL);
        // no e-mail, or no mailbox: paper
        assertThat(resolve(DeliveryChannel.TELEGRAM, false, false, true, true))
                .isEqualTo(new Resolution(Channel.PAPER, null));
        assertThat(resolve(DeliveryChannel.TELEGRAM, true, false, false, true).channel()).isEqualTo(Channel.PAPER);
        assertThat(resolve(DeliveryChannel.TELEGRAM, true, true, false, false).channel()).isEqualTo(Channel.PAPER);
    }

    @Test
    void emailWithAnAddressAndAMailboxGoesByEmail() {
        assertThat(resolve(DeliveryChannel.EMAIL, true, false, true, false))
                .isEqualTo(new Resolution(Channel.EMAIL, null));
        assertThat(resolve(DeliveryChannel.EMAIL, true, true, true, true).channel()).isEqualTo(Channel.EMAIL);
    }

    @Test
    void emailWithoutAnAddressOrWithoutAMailboxGoesOnPaper() {
        assertThat(resolve(DeliveryChannel.EMAIL, false, false, true, true))
                .isEqualTo(new Resolution(Channel.PAPER, null));
        assertThat(resolve(DeliveryChannel.EMAIL, true, false, false, true).channel()).isEqualTo(Channel.PAPER);
        // a linked Telegram does not make an e-mail member go by Telegram
        assertThat(resolve(DeliveryChannel.EMAIL, false, true, false, true).channel()).isEqualTo(Channel.PAPER);
    }

    @Test
    void paperIsAlwaysPaper() {
        assertThat(resolve(DeliveryChannel.PAPER, true, true, true, true)).isEqualTo(new Resolution(Channel.PAPER, null));
        assertThat(resolve(DeliveryChannel.PAPER, false, false, false, false).channel()).isEqualTo(Channel.PAPER);
    }

    @Test
    void viberGoesOnPaperWithTheNote() {
        assertThat(resolve(DeliveryChannel.VIBER, true, true, true, true))
                .isEqualTo(new Resolution(Channel.PAPER, "VIBER_UNSUPPORTED"));
        assertThat(resolve(DeliveryChannel.VIBER, false, false, false, false))
                .isEqualTo(new Resolution(Channel.PAPER, ChannelResolver.VIBER_UNSUPPORTED));
    }

    // ---- announcements (feature 017 §2.6)

    private static final Set<Channel> TELEGRAM = EnumSet.of(Channel.TELEGRAM);
    private static final Set<Channel> EMAIL = EnumSet.of(Channel.EMAIL);
    private static final Set<Channel> BOTH = EnumSet.of(Channel.TELEGRAM, Channel.EMAIL);

    private static Channel announce(DeliveryChannel preferred, boolean hasEmail, boolean linked, Set<Channel> chosen) {
        return ChannelResolver.forAnnouncement(preferred, hasEmail, linked, true, true, chosen).channel();
    }

    @Test
    void anAnnouncementUsesTheMembersOwnChannelWhenItWasChosen() {
        assertThat(announce(DeliveryChannel.TELEGRAM, true, true, TELEGRAM)).isEqualTo(Channel.TELEGRAM);
        assertThat(announce(DeliveryChannel.TELEGRAM, true, true, BOTH)).isEqualTo(Channel.TELEGRAM);
        assertThat(announce(DeliveryChannel.EMAIL, true, true, EMAIL)).isEqualTo(Channel.EMAIL);
        assertThat(announce(DeliveryChannel.EMAIL, true, true, BOTH)).isEqualTo(Channel.EMAIL);
    }

    @Test
    void anAnnouncementFallsBackToTheOtherChosenChannelThatReachesTheMember() {
        // Telegram member, only e-mail chosen
        assertThat(announce(DeliveryChannel.TELEGRAM, true, true, EMAIL)).isEqualTo(Channel.EMAIL);
        assertThat(announce(DeliveryChannel.TELEGRAM, false, true, EMAIL)).isEqualTo(Channel.PAPER);
        // Telegram member not linked: e-mail as for invoices, but only when chosen
        assertThat(announce(DeliveryChannel.TELEGRAM, true, false, BOTH)).isEqualTo(Channel.EMAIL);
        assertThat(announce(DeliveryChannel.TELEGRAM, true, false, TELEGRAM)).isEqualTo(Channel.PAPER);
        // e-mail member, only Telegram chosen
        assertThat(announce(DeliveryChannel.EMAIL, true, true, TELEGRAM)).isEqualTo(Channel.TELEGRAM);
        assertThat(announce(DeliveryChannel.EMAIL, true, false, TELEGRAM)).isEqualTo(Channel.PAPER);
        // an e-mail member without one who is linked
        assertThat(announce(DeliveryChannel.EMAIL, false, true, BOTH)).isEqualTo(Channel.TELEGRAM);
    }

    @Test
    void anAnnouncementNeedsTheMailboxOrTheBotOfTheChannel() {
        assertThat(ChannelResolver.forAnnouncement(DeliveryChannel.TELEGRAM, true, true, true, false, TELEGRAM)
                .channel()).isEqualTo(Channel.PAPER);
        assertThat(ChannelResolver.forAnnouncement(DeliveryChannel.TELEGRAM, true, true, true, false, BOTH)
                .channel()).isEqualTo(Channel.EMAIL);
        assertThat(ChannelResolver.forAnnouncement(DeliveryChannel.EMAIL, true, false, false, true, EMAIL)
                .channel()).isEqualTo(Channel.PAPER);
    }

    @Test
    void paperAndViberMembersGetAnAnnouncementOnPaper() {
        assertThat(ChannelResolver.forAnnouncement(DeliveryChannel.PAPER, true, true, true, true, BOTH))
                .isEqualTo(new Resolution(Channel.PAPER, null));
        assertThat(ChannelResolver.forAnnouncement(DeliveryChannel.VIBER, true, true, true, true, BOTH))
                .isEqualTo(new Resolution(Channel.PAPER, ChannelResolver.VIBER_UNSUPPORTED));
    }
}
