package app.nadel.members;

import app.nadel.identity.AccessProfile;
import java.util.UUID;

/**
 * Changes how a member receives invoices, for modules that let the member choose it themselves (feature 017: the
 * «Сповіщення» panel of the member account, through {@code notifications}). The board still sets the channel on the
 * member form. The cooperative is bound by the caller's transaction (or one this method starts).
 */
public interface MemberChannels {

    /**
     * Sets the preferred channel of the member. {@code EMAIL} needs the member's e-mail and {@code VIBER} a Viber
     * phone (400 {@code VALIDATION_FAILED} with {@code CHANNEL_CONTACT_MISSING} otherwise); {@code TELEGRAM} and
     * {@code PAPER} need nothing. A change is written to the audit log as {@code MEMBER_UPDATED} of the member (field
     * {@code deliveryChannel}) with {@code actor} as its author; the current channel again writes nothing.
     *
     * @throws app.nadel.problem.ApiProblemException 404 when the member is not one of the cooperative, 400 as above
     */
    void setPreferredChannel(UUID memberId, DeliveryChannel channel, AccessProfile actor);
}
