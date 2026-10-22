package app.nadel.meters.internal;

import app.nadel.identity.CurrentUser;
import java.time.LocalDate;
import java.util.UUID;
import nadel.contract.api.MeterTreeApi;
import nadel.contract.model.MeterParentChange;
import nadel.contract.model.MeterPlacement;
import nadel.contract.model.MeterTree;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code meter-tree}. Reading the tree needs {@code METERS_VIEW}; a move needs {@code METERS_VIEW}
 * and {@code METERS_MANAGE}, as every meter write. The platform operator has no permissions and gets 403.
 */
@RestController
@RequestMapping("/api/v1")
class MeterTreeController implements MeterTreeApi {

    private final CurrentUser currentUser;
    private final TreeQueries queries;
    private final TreeCommands commands;

    MeterTreeController(CurrentUser currentUser, TreeQueries queries, TreeCommands commands) {
        this.currentUser = currentUser;
        this.queries = queries;
        this.commands = commands;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_METERS_VIEW')")
    public ResponseEntity<MeterTree> getMeterTree(LocalDate from, LocalDate to) {
        return ResponseEntity.ok(queries.tree(from, to));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_METERS_VIEW') and hasAuthority('PERM_METERS_MANAGE')")
    public ResponseEntity<MeterPlacement> setMeterParent(UUID id, MeterParentChange meterParentChange) {
        return ResponseEntity.ok(commands.setParent(id, meterParentChange, currentUser.profile()));
    }
}
