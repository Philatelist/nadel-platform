package app.nadel.plots.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.CurrentUser;
import app.nadel.identity.Permission;
import app.nadel.problem.ApiProblemException;
import java.util.UUID;
import nadel.contract.api.OwnershipsApi;
import nadel.contract.model.MemberOwnerships;
import nadel.contract.model.OwnershipChange;
import nadel.contract.model.OwnershipCorrection;
import nadel.contract.model.PlotOwnerships;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class OwnershipController implements OwnershipsApi {

    private final CurrentUser currentUser;
    private final OwnershipQueries queries;
    private final OwnershipCommands commands;

    OwnershipController(CurrentUser currentUser, OwnershipQueries queries, OwnershipCommands commands) {
        this.currentUser = currentUser;
        this.queries = queries;
        this.commands = commands;
    }

    /**
     * Owners and history of a plot, for callers with {@code PLOTS_VIEW} and for a member who owns the plot now. The
     * platform operator is refused (403); for everyone else, a former owner included, a plot they may not read is 404,
     * exactly like a plot that does not exist or belongs to another cooperative (RLS).
     */
    @Override
    public ResponseEntity<PlotOwnerships> listPlotOwnerships(UUID id) {
        AccessProfile viewer = currentUser.profile();
        CooperativeCallers.require(viewer);
        return ResponseEntity.ok(queries.ofPlotFor(id, viewer).orElseThrow(ApiProblemException::notFound));
    }

    /**
     * The plots of a member, for callers with {@code PLOTS_VIEW} or {@code MEMBERS_VIEW} and for the member
     * themself. The platform operator is refused (403); everyone else gets 404 before any lookup when not allowed,
     * and for a member who does not exist or belongs to another cooperative (RLS), so existence is not revealed.
     */
    @Override
    public ResponseEntity<MemberOwnerships> listMemberOwnerships(UUID id) {
        AccessProfile viewer = currentUser.profile();
        CooperativeCallers.require(viewer);
        boolean allowed = id.equals(viewer.memberId())
                || viewer.can(Permission.PLOTS_VIEW) || viewer.can(Permission.MEMBERS_VIEW);
        if (!allowed) {
            throw ApiProblemException.notFound();
        }
        return ResponseEntity.ok(queries.ofMember(id).orElseThrow(ApiProblemException::notFound));
    }

    /**
     * Changes the owners of a plot from a date. The platform operator and everyone without both permissions get 403; a
     * plot that does not exist or belongs to another cooperative (RLS) is 404.
     */
    @Override
    @PreAuthorize("hasAuthority('PERM_PLOTS_VIEW') and hasAuthority('PERM_OWNERSHIP_EDIT')")
    public ResponseEntity<PlotOwnerships> createOwnershipChange(UUID id, OwnershipChange ownershipChange) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commands.change(id, ownershipChange, currentUser.profile()));
    }

    /**
     * Corrects one owner record (and, optionally, the shares of its co-owners) with a reason. The platform operator
     * and everyone without both permissions get 403; a record that does not exist or belongs to another cooperative
     * (RLS) is 404.
     */
    @Override
    @PreAuthorize("hasAuthority('PERM_PLOTS_VIEW') and hasAuthority('PERM_OWNERSHIP_EDIT')")
    public ResponseEntity<PlotOwnerships> createOwnershipCorrection(UUID id, OwnershipCorrection ownershipCorrection) {
        return ResponseEntity.ok(commands.correct(id, ownershipCorrection, currentUser.profile()));
    }
}
