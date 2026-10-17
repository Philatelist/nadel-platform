package app.nadel.members.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.CurrentUser;
import app.nadel.problem.ApiProblemException;
import java.util.UUID;
import nadel.contract.api.MembersApi;
import org.springframework.http.ResponseEntity;
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

    /**
     * Slice 1: a caller can open only their own card; any other id is answered exactly like a missing one.
     * Holders of MEMBERS_VIEW will read other cards from slice 2.
     */
    @Override
    public ResponseEntity<nadel.contract.model.Member> getMember(UUID id) {
        AccessProfile viewer = currentUser.profile();
        if (!id.equals(viewer.memberId())) {
            throw ApiProblemException.notFound();
        }
        return ResponseEntity.ok(queries.card(id, viewer).orElseThrow(ApiProblemException::notFound));
    }
}
