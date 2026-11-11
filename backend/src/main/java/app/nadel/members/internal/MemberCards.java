package app.nadel.members.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import org.springframework.stereotype.Component;

/**
 * The member card ({@code Member} of the API) as the viewer may see it: the one place that puts the contacts, the
 * roles, the Telegram flag and, for a viewer with {@code MEMBERS_VIEW}, the access to the system (feature 020) together
 * ({@link MemberDtoMapper}). Runs in the caller's transaction.
 */
@Component
class MemberCards {

    private final RoleGrantReader grants;
    private final TelegramLinkFlags telegram;
    private final MemberAccessState access;
    private final InvitationDeliveries deliveries;

    MemberCards(RoleGrantReader grants, TelegramLinkFlags telegram, MemberAccessState access,
            InvitationDeliveries deliveries) {
        this.grants = grants;
        this.telegram = telegram;
        this.access = access;
        this.deliveries = deliveries;
    }

    nadel.contract.model.Member card(Member member, OpenedContacts contacts, AccessProfile viewer) {
        nadel.contract.model.MemberAccess memberAccess = viewer.can(Permission.MEMBERS_VIEW)
                ? access.view(member, contacts.email() != null && !contacts.email().isBlank(),
                        () -> deliveries.emailReady(member.getTenantId()))
                : null;
        return MemberDtoMapper.toDto(member, contacts, grants.read(member), viewer, telegram.linked(member.getId()),
                memberAccess);
    }
}
