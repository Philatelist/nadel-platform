package app.nadel.members.internal;

import app.nadel.members.InvitationDelivery;
import app.nadel.members.InvitationDeliveryResult;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The invitation letters (feature 020) through the SPI {@link InvitationDelivery} that {@code notifications}
 * implements. Without an implementation no cooperative can send one: {@link #emailReady} is false and
 * {@link #deliver} answers {@code NOT_CONFIGURED}.
 */
@Component
class InvitationDeliveries {

    private final ObjectProvider<InvitationDelivery> delivery;

    InvitationDeliveries(ObjectProvider<InvitationDelivery> delivery) {
        this.delivery = delivery;
    }

    boolean emailReady(UUID tenantId) {
        InvitationDelivery implementation = delivery.getIfAvailable();
        return implementation != null && implementation.emailReady(tenantId);
    }

    InvitationDeliveryResult deliver(UUID tenantId, UUID memberId, String email, URI link, Instant expiresAt) {
        InvitationDelivery implementation = delivery.getIfAvailable();
        return implementation == null
                ? InvitationDeliveryResult.failed(InvitationDeliveryResult.Failure.NOT_CONFIGURED)
                : implementation.deliver(tenantId, memberId, email, link, expiresAt);
    }
}
