package app.nadel.identity.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.CurrentUser;
import app.nadel.tenancy.TenantDirectory;
import java.util.Optional;
import nadel.contract.api.MeApi;
import nadel.contract.model.BaseRole;
import nadel.contract.model.Me;
import nadel.contract.model.Permission;
import nadel.contract.model.RoleRef;
import nadel.contract.model.TenantSummary;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class MeController implements MeApi {

    private final CurrentUser currentUser;
    private final TenantDirectory tenantDirectory;

    MeController(CurrentUser currentUser, TenantDirectory tenantDirectory) {
        this.currentUser = currentUser;
        this.tenantDirectory = tenantDirectory;
    }

    @Override
    public ResponseEntity<Me> getMe() {
        AccessProfile profile = currentUser.profile();
        var jwt = ((JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication()).getToken();
        String email = Optional.ofNullable(jwt.getClaimAsString("email")).orElse("");

        var me = new Me();
        me.setUserId(profile.subject());
        me.setEmail(email);
        me.setFullName(profile.displayName());
        me.setMemberId(profile.memberId());
        me.setPlatformOperator(profile.platformOperator());
        me.setRoles(profile.roles().stream().map(MeController::toDto).toList());
        me.setPermissions(profile.permissions().stream()
                .map(p -> Permission.fromValue(p.name()))
                .toList());
        if (!profile.platformOperator()) {
            tenantDirectory.current().ifPresent(t -> {
                var tenant = new TenantSummary();
                tenant.setId(t.id());
                tenant.setName(t.name());
                me.setTenant(tenant);
            });
        }
        return ResponseEntity.ok(me);
    }

    private static RoleRef toDto(app.nadel.identity.RoleRef role) {
        var dto = new RoleRef(role.id(), role.name(), role.nameCustomized());
        if (role.baseCode() != null) {
            dto.setBaseCode(BaseRole.fromValue(role.baseCode()));
        }
        return dto;
    }
}
