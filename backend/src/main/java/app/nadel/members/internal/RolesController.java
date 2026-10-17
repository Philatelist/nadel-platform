package app.nadel.members.internal;

import app.nadel.identity.CurrentUser;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import nadel.contract.api.RolesApi;
import nadel.contract.model.RoleDetails;
import nadel.contract.model.RoleInput;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class RolesController implements RolesApi {

    private final CurrentUser currentUser;
    private final RoleQueries queries;
    private final RoleCommands commands;

    RolesController(CurrentUser currentUser, RoleQueries queries, RoleCommands commands) {
        this.currentUser = currentUser;
        this.queries = queries;
        this.commands = commands;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_ROLES_MANAGE')")
    public ResponseEntity<List<RoleDetails>> listRoles() {
        return ResponseEntity.ok(queries.list(currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_ROLES_MANAGE')")
    public ResponseEntity<RoleDetails> createRole(RoleInput roleInput) {
        RoleDetails created = commands.create(roleInput, currentUser.profile());
        return ResponseEntity.created(URI.create("/api/v1/roles/" + created.getId())).body(created);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_ROLES_MANAGE')")
    public ResponseEntity<RoleDetails> updateRole(UUID id, RoleInput roleInput) {
        return ResponseEntity.ok(commands.update(id, roleInput, currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_ROLES_MANAGE')")
    public ResponseEntity<Void> deleteRole(UUID id) {
        commands.delete(id, currentUser.profile());
        return ResponseEntity.noContent().build();
    }
}
