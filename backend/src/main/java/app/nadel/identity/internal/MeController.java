package app.nadel.identity.internal;

import app.nadel.tenancy.TenantDirectory;
import nadel.contract.api.MeApi;
import nadel.contract.model.Me;
import nadel.contract.model.Role;
import nadel.contract.model.TenantSummary;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class MeController implements MeApi {

    private final TenantDirectory tenantDirectory;

    MeController(TenantDirectory tenantDirectory) {
        this.tenantDirectory = tenantDirectory;
    }

    @Override
    public ResponseEntity<Me> getMe() {
        var auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        var jwt = auth.getToken();

        var me = new Me();
        me.setUserId(jwt.getSubject());
        me.setEmail(jwt.getClaimAsString("email"));
        me.setFullName(fullName(jwt.getClaimAsString("name"), jwt.getClaimAsString("email")));
        me.setRoles(auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.substring("ROLE_".length()))
                .map(Role::fromValue)
                .sorted()
                .toList());
        tenantDirectory.current().ifPresent(t -> {
            var tenant = new TenantSummary();
            tenant.setId(t.id());
            tenant.setName(t.name());
            me.setTenant(tenant);
        });
        return ResponseEntity.ok(me);
    }

    private static String fullName(String name, String fallback) {
        return name != null && !name.isBlank() ? name : fallback;
    }
}
