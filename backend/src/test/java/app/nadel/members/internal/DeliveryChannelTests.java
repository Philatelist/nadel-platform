package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.problem.FieldProblem;
import org.junit.jupiter.api.Test;

class DeliveryChannelTests {

    @Test
    void paperNeedsNothing() {
        assertThat(DeliveryChannel.PAPER.check(null, null, null)).isEmpty();
    }

    @Test
    void emailNeedsAnEmail() {
        assertThat(DeliveryChannel.EMAIL.check(null, "tg", "+380671234567"))
                .contains(new FieldProblem("email", "CHANNEL_CONTACT_MISSING"));
        assertThat(DeliveryChannel.EMAIL.check("a@b.co", null, null)).isEmpty();
    }

    @Test
    void telegramNeedsAUsername() {
        assertThat(DeliveryChannel.TELEGRAM.check("a@b.co", null, "+380671234567"))
                .contains(new FieldProblem("telegramUsername", "CHANNEL_CONTACT_MISSING"));
        assertThat(DeliveryChannel.TELEGRAM.check(null, "tg_user", null)).isEmpty();
    }

    @Test
    void viberNeedsAViberPhone() {
        assertThat(DeliveryChannel.VIBER.check("a@b.co", "tg", null))
                .contains(new FieldProblem("viberPhone", "CHANNEL_CONTACT_MISSING"));
        assertThat(DeliveryChannel.VIBER.check(null, null, "+380671234567")).isEmpty();
    }

    @Test
    void aBlankContactCountsAsMissing() {
        assertThat(DeliveryChannel.EMAIL.check("  ", null, null)).isPresent();
    }
}
