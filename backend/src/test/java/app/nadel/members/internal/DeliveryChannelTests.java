package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.problem.FieldProblem;
import org.junit.jupiter.api.Test;

class DeliveryChannelTests {

    @Test
    void paperNeedsNothing() {
        assertThat(DeliveryChannel.PAPER.check(null, null)).isEmpty();
    }

    @Test
    void emailNeedsAnEmail() {
        assertThat(DeliveryChannel.EMAIL.check(null, "+380671234567"))
                .contains(new FieldProblem("email", "CHANNEL_CONTACT_MISSING"));
        assertThat(DeliveryChannel.EMAIL.check("a@b.co", null)).isEmpty();
    }

    /** Feature 017: the member links Telegram through the cooperative's bot, so no contact is needed. */
    @Test
    void telegramNeedsNoContact() {
        assertThat(DeliveryChannel.TELEGRAM.check(null, null)).isEmpty();
    }

    @Test
    void viberNeedsAViberPhone() {
        assertThat(DeliveryChannel.VIBER.check("a@b.co", null))
                .contains(new FieldProblem("viberPhone", "CHANNEL_CONTACT_MISSING"));
        assertThat(DeliveryChannel.VIBER.check(null, "+380671234567")).isEmpty();
    }

    @Test
    void aBlankContactCountsAsMissing() {
        assertThat(DeliveryChannel.EMAIL.check("  ", null)).isPresent();
    }
}
