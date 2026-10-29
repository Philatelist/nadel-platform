package app.nadel.members.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.RoleRef;
import app.nadel.privacy.IndexKind;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import nadel.contract.model.MemberPage;
import nadel.contract.model.MemberSummary;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MemberQueries {

    /** Bound for a search candidate the query does not stand for: no HMAC is 32 zero bytes, so it matches nothing. */
    private static final byte[] NO_MATCH = new byte[32];

    private final MemberRepository members;
    private final RoleGrantReader grants;
    private final MemberCrypto crypto;
    private final TelegramLinkFlags telegram;

    MemberQueries(MemberRepository members, RoleGrantReader grants, MemberCrypto crypto, TelegramLinkFlags telegram) {
        this.members = members;
        this.grants = grants;
        this.crypto = crypto;
        this.telegram = telegram;
    }

    /** The card of a member of the current cooperative (RLS hides other cooperatives), shaped for the viewer. */
    @Transactional(readOnly = true)
    Optional<nadel.contract.model.Member> card(UUID id, AccessProfile viewer) {
        return members.findById(id)
                .map(member -> MemberDtoMapper.toDto(member, crypto.open(member), grants.read(member), viewer,
                        telegram.linked(member.getId())));
    }

    /**
     * One page of the members of the viewer's cooperative, sorted by last name then first name.
     * <p>
     * {@code query} (trimmed) is a case-insensitive substring of the last, first or middle name. The contacts are
     * sealed, so they match exactly through their blind indexes ({@link MemberSearchTerms}): a query with {@code @}
     * the whole e-mail (case ignored), a query that parses as a phone the whole phone in any usual spelling, and
     * exactly four digits the last four digits of any phone. The caller must hold {@code MEMBERS_VIEW}.
     */
    @Transactional(readOnly = true)
    MemberPage list(AccessProfile viewer, String query, boolean includeArchived, int page, int size) {
        UUID tenantId = viewer.tenantId();
        MemberSearchTerms terms = MemberSearchTerms.parse(query);
        Page<Member> result = members.search(tenantId, includeArchived, likeContains(terms.text()),
                index(tenantId, IndexKind.EMAIL, terms.email()), index(tenantId, IndexKind.PHONE, terms.phone()),
                index(tenantId, IndexKind.PHONE_LAST4, terms.last4()), PageRequest.of(page, size));
        Map<UUID, List<RoleRef>> roles = grants.readRefs(tenantId,
                result.getContent().stream().map(Member::getId).toList());
        List<MemberSummary> items = result.getContent().stream()
                .map(member -> MemberDtoMapper.toSummary(member, crypto.phones(member), crypto.email(member),
                        roles.getOrDefault(member.getId(), List.of())))
                .toList();
        return new MemberPage(items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    private byte[] index(UUID tenantId, IndexKind kind, @Nullable String normalised) {
        return normalised == null ? NO_MATCH : crypto.index(tenantId, kind, normalised);
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
