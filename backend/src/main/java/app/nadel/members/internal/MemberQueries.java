package app.nadel.members.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.RoleRef;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import nadel.contract.model.MemberPage;
import nadel.contract.model.MemberSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MemberQueries {

    private final MemberRepository members;
    private final RoleGrantReader grants;

    MemberQueries(MemberRepository members, RoleGrantReader grants) {
        this.members = members;
        this.grants = grants;
    }

    /** The card of a member of the current cooperative (RLS hides other cooperatives), shaped for the viewer. */
    @Transactional(readOnly = true)
    Optional<nadel.contract.model.Member> card(UUID id, AccessProfile viewer) {
        return members.findById(id).map(member -> MemberDtoMapper.toDto(member, grants.read(member), viewer));
    }

    /**
     * One page of the members of the viewer's cooperative, sorted by last name then first name.
     * <p>
     * {@code query} is a case-insensitive substring of the names or the e-mail; when it contains digits it also
     * matches the digits of any phone number. The caller must hold {@code MEMBERS_VIEW}.
     */
    @Transactional(readOnly = true)
    MemberPage list(AccessProfile viewer, String query, boolean includeArchived, int page, int size) {
        String text = query == null ? "" : query.strip();
        String digits = text.replaceAll("[^0-9]", "");
        Page<Member> result = members.search(viewer.tenantId(), includeArchived, likeContains(text), !digits.isEmpty(),
                likeContains(digits), PageRequest.of(page, size));
        Map<UUID, List<RoleRef>> roles = grants.readRefs(viewer.tenantId(),
                result.getContent().stream().map(Member::getId).toList());
        List<MemberSummary> items = result.getContent().stream()
                .map(member -> MemberDtoMapper.toSummary(member, roles.getOrDefault(member.getId(), List.of())))
                .toList();
        return new MemberPage(items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    /** {@code %text%} for a LIKE with escape character {@code !}; user input never changes the pattern's shape. */
    private static String likeContains(String text) {
        String escaped = text.toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }
}
