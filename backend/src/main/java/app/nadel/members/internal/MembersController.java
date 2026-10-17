package app.nadel.members.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.CurrentUser;
import app.nadel.identity.Permission;
import app.nadel.problem.ApiProblemException;
import java.util.UUID;
import nadel.contract.api.MembersApi;
import nadel.contract.model.MemberPage;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class MembersController implements MembersApi {

    private final CurrentUser currentUser;
    private final MemberQueries queries;

    MembersController(CurrentUser currentUser, MemberQueries queries) {
        this.currentUser = currentUser;
        this.queries = queries;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_MEMBERS_VIEW')")
    public ResponseEntity<MemberPage> listMembers(String q, Boolean includeArchived, Integer page, Integer size) {
        return ResponseEntity.ok(queries.list(currentUser.profile(), q, includeArchived, page, size));
    }

    /**
     * A caller always opens their own card. Another card needs MEMBERS_VIEW; without it the answer is 404 before
     * any lookup, exactly like for a missing id, so the existence of a member is not revealed. For holders of
     * MEMBERS_VIEW the lookup runs under RLS: a member of another cooperative is not found either.
     */
    @Override
    public ResponseEntity<nadel.contract.model.Member> getMember(UUID id) {
        AccessProfile viewer = currentUser.profile();
        if (!id.equals(viewer.memberId()) && !viewer.can(Permission.MEMBERS_VIEW)) {
            throw ApiProblemException.notFound();
        }
        return ResponseEntity.ok(queries.card(id, viewer).orElseThrow(ApiProblemException::notFound));
    }
}
