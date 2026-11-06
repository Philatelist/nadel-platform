package app.nadel.members;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The result of the invitation delivery SPI (feature 020): either sent through channels or failed with a reason. */
class InvitationDeliveryResultTests {

    @Test
    void aSentResultHasChannelsAndNoFailure() {
        InvitationDeliveryResult sent = InvitationDeliveryResult.sent(Set.of(DeliveryChannel.EMAIL));

        assertThat(sent.isSent()).isTrue();
        assertThat(sent.channels()).containsExactly(DeliveryChannel.EMAIL);
        assertThat(sent.failure()).isNull();
    }

    @Test
    void aFailedResultHasAReasonAndNoChannels() {
        InvitationDeliveryResult failed = InvitationDeliveryResult.failed(
                InvitationDeliveryResult.Failure.CONNECTION_FAILED);

        assertThat(failed.isSent()).isFalse();
        assertThat(failed.channels()).isEmpty();
        assertThat(failed.failure()).isEqualTo(InvitationDeliveryResult.Failure.CONNECTION_FAILED);
    }

    @Test
    void aResultIsNeverBothOrNeither() {
        assertThatThrownBy(() -> InvitationDeliveryResult.sent(Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvitationDeliveryResult(Set.of(DeliveryChannel.EMAIL),
                InvitationDeliveryResult.Failure.SMTP_REJECTED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvitationDeliveryResult.failed(null)).isInstanceOf(NullPointerException.class);
    }
}
