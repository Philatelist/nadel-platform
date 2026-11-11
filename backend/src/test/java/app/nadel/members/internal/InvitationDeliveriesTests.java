package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.members.InvitationDelivery;
import app.nadel.members.InvitationDeliveryResult;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Without an implementation of the invitation delivery SPI (feature 020) no cooperative can invite. */
class InvitationDeliveriesTests {

    @Test
    void withoutAnImplementationNothingIsReadyAndNothingIsSent() {
        var deliveries = new InvitationDeliveries(new StaticListableBeanFactory()
                .getBeanProvider(InvitationDelivery.class));
        UUID tenant = UUID.randomUUID();

        assertThat(deliveries.emailReady(tenant)).isFalse();
        assertThat(deliveries.deliver(tenant, UUID.randomUUID(), "a@b.c", URI.create("http://x/set-password"),
                Instant.now()).failure()).isEqualTo(InvitationDeliveryResult.Failure.NOT_CONFIGURED);
    }
}
