package app.nadel.members.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import java.time.ZoneOffset;
import java.util.List;
import nadel.contract.model.BaseRole;
import nadel.contract.model.MemberSummary;
import nadel.contract.model.RoleRef;
import org.jspecify.annotations.Nullable;

/**
 * The only place that turns a member into API output, and therefore the only place that decides what a viewer may
 * see. The contacts come opened ({@link MemberCrypto}). {@code notes} is filled only for a viewer with
 * {@code MEMBERS_VIEW}; otherwise it stays null and the key is absent from the JSON (null properties are not
 * serialised).
 */
final class MemberDtoMapper {

    private MemberDtoMapper() {
    }

    static nadel.contract.model.Member toDto(Member member, OpenedContacts contacts, List<RoleGrant> roles,
            AccessProfile viewer) {
        var dto = new nadel.contract.model.Member(
                member.getId(),
                member.getLastName(),
                member.getFirstName(),
                List.copyOf(contacts.phones()),
                nadel.contract.model.DeliveryChannel.fromValue(member.getDeliveryChannel().name()),
                roles.stream().map(MemberDtoMapper::roleRef).toList(),
                member.isArchived());
        dto.setMiddleName(member.getMiddleName());
        dto.setEmail(contacts.email());
        dto.setPostalAddress(contacts.postalAddress());
        dto.setTelegramUsername(contacts.telegramUsername());
        dto.setViberPhone(contacts.viberPhone());
        if (member.getArchivedAt() != null) {
            dto.setArchivedAt(member.getArchivedAt().atOffset(ZoneOffset.UTC));
        }
        if (viewer.can(Permission.MEMBERS_VIEW)) {
            dto.setNotes(contacts.notes());
        }
        return dto;
    }

    /** A row of the list. It carries no {@code notes} by construction: the summary schema has no such field. */
    static MemberSummary toSummary(Member member, List<String> phones, @Nullable String email,
            List<app.nadel.identity.RoleRef> roles) {
        var dto = new MemberSummary(
                member.getId(),
                member.getLastName(),
                member.getFirstName(),
                List.copyOf(phones),
                nadel.contract.model.DeliveryChannel.fromValue(member.getDeliveryChannel().name()),
                roles.stream().map(MemberDtoMapper::roleRef).toList(),
                member.isArchived());
        dto.setMiddleName(member.getMiddleName());
        dto.setEmail(email);
        return dto;
    }

    private static RoleRef roleRef(RoleGrant role) {
        return roleRef(role.ref());
    }

    private static RoleRef roleRef(app.nadel.identity.RoleRef role) {
        var ref = new RoleRef(role.id(), role.name(), role.nameCustomized());
        if (role.baseCode() != null) {
            ref.setBaseCode(BaseRole.fromValue(role.baseCode()));
        }
        return ref;
    }
}
