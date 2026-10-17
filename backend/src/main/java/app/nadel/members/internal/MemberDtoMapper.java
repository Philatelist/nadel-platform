package app.nadel.members.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import java.time.ZoneOffset;
import java.util.List;
import nadel.contract.model.BaseRole;
import nadel.contract.model.MemberSummary;
import nadel.contract.model.RoleRef;

/**
 * The only place that turns a member into API output, and therefore the only place that decides what a viewer may
 * see. {@code notes} is filled only for a viewer with {@code MEMBERS_VIEW}; otherwise it stays null and the key is
 * absent from the JSON (null properties are not serialised).
 */
final class MemberDtoMapper {

    private MemberDtoMapper() {
    }

    static nadel.contract.model.Member toDto(Member member, List<RoleGrant> roles, AccessProfile viewer) {
        var dto = new nadel.contract.model.Member(
                member.getId(),
                member.getLastName(),
                member.getFirstName(),
                List.copyOf(member.getPhones()),
                nadel.contract.model.DeliveryChannel.fromValue(member.getDeliveryChannel().name()),
                roles.stream().map(MemberDtoMapper::roleRef).toList(),
                member.isArchived());
        dto.setMiddleName(member.getMiddleName());
        dto.setEmail(member.getEmail());
        dto.setPostalAddress(member.getPostalAddress());
        dto.setTelegramUsername(member.getTelegramUsername());
        dto.setViberPhone(member.getViberPhone());
        if (member.getArchivedAt() != null) {
            dto.setArchivedAt(member.getArchivedAt().atOffset(ZoneOffset.UTC));
        }
        if (viewer.can(Permission.MEMBERS_VIEW)) {
            dto.setNotes(member.getNotes());
        }
        return dto;
    }

    /** A row of the list. It carries no {@code notes} by construction: the summary schema has no such field. */
    static MemberSummary toSummary(Member member, List<app.nadel.identity.RoleRef> roles) {
        var dto = new MemberSummary(
                member.getId(),
                member.getLastName(),
                member.getFirstName(),
                List.copyOf(member.getPhones()),
                nadel.contract.model.DeliveryChannel.fromValue(member.getDeliveryChannel().name()),
                roles.stream().map(MemberDtoMapper::roleRef).toList(),
                member.isArchived());
        dto.setMiddleName(member.getMiddleName());
        dto.setEmail(member.getEmail());
        return dto;
    }

    private static RoleRef roleRef(RoleGrant role) {
        return roleRef(role.ref());
    }

    private static RoleRef roleRef(app.nadel.identity.RoleRef role) {
        var ref = new RoleRef(role.id(), role.name());
        if (role.baseCode() != null) {
            ref.setBaseCode(BaseRole.fromValue(role.baseCode()));
        }
        return ref;
    }
}
