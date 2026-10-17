package app.nadel.members.internal;

import java.util.List;
import nadel.contract.api.PermissionsApi;
import nadel.contract.model.PermissionInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class PermissionsController implements PermissionsApi {

    @Override
    @PreAuthorize("hasAuthority('PERM_ROLES_MANAGE')")
    public ResponseEntity<List<PermissionInfo>> listPermissions() {
        return ResponseEntity.ok(PermissionCatalog.all());
    }
}
