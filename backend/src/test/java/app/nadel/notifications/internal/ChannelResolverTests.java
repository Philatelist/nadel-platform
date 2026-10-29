package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.members.DeliveryChannel;
import app.nadel.notifications.internal.ChannelResolver.Resolution;
import org.junit.jupiter.api.Test;

/** The channel rule of feature 017 §2.3, one test per row of its table (and each way a row's situation can arise). */
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
}
