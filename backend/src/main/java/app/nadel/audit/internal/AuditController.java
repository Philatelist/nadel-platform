package app.nadel.audit.internal;

import app.nadel.identity.CurrentUser;
import app.nadel.identity.Permission;
import java.time.LocalDate;
import java.util.UUID;
import nadel.contract.api.AuditApi;
import nadel.contract.model.AuditEntityType;
import nadel.contract.model.AuditPage;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class AuditController implements AuditApi {

    private final CurrentUser currentUser;
    private final AuditQueries queries;

    AuditController(CurrentUser currentUser, AuditQueries queries) {
        this.currentUser = currentUser;
        this.queries = queries;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_AUDIT_VIEW')")
    public ResponseEntity<AuditPage> listAuditEntries(AuditEntityType entityType, UUID entityId, UUID actorMemberId,
            LocalDate from, LocalDate to, Integer page, Integer size) {
        // The cooperative comes from the caller's profile (the token), never from the request. Sealed personal values
        // are opened only for a reader who may also view members (PERM_MEMBERS_VIEW); anyone else sees them hidden.
        return ResponseEntity.ok(queries.list(currentUser.profile().tenantId(), entityType, entityId, actorMemberId,
                from, to, page, size, currentUser.can(Permission.MEMBERS_VIEW)));
    }
}
