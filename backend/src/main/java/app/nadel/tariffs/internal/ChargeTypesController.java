package app.nadel.tariffs.internal;

import app.nadel.identity.CurrentUser;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import nadel.contract.api.TariffsApi;
import nadel.contract.model.ChargeType;
import nadel.contract.model.ChargeTypeCreate;
import nadel.contract.model.ChargeTypeOrder;
import nadel.contract.model.ChargeTypeUpdate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The charge type endpoints of tag {@code tariffs}. Reading needs {@code TARIFFS_VIEW}; every write needs
 * {@code TARIFFS_VIEW} and {@code TARIFFS_MANAGE}, so a manager can always see what they change. The platform operator
 * has no permissions and gets 403.
 */
@RestController
@RequestMapping("/api/v1")
class ChargeTypesController implements TariffsApi {

    private final CurrentUser currentUser;
    private final ChargeTypeQueries queries;
    private final ChargeTypeCommands commands;

    ChargeTypesController(CurrentUser currentUser, ChargeTypeQueries queries, ChargeTypeCommands commands) {
        this.currentUser = currentUser;
        this.queries = queries;
        this.commands = commands;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_TARIFFS_VIEW')")
    public ResponseEntity<List<ChargeType>> listChargeTypes() {
        return ResponseEntity.ok(queries.list());
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_TARIFFS_VIEW') and hasAuthority('PERM_TARIFFS_MANAGE')")
    public ResponseEntity<ChargeType> createChargeType(ChargeTypeCreate chargeTypeCreate) {
        ChargeType created = commands.create(chargeTypeCreate, currentUser.profile());
        return ResponseEntity.created(URI.create("/api/v1/charge-types/" + created.getId())).body(created);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_TARIFFS_VIEW') and hasAuthority('PERM_TARIFFS_MANAGE')")
    public ResponseEntity<ChargeType> updateChargeType(UUID id, ChargeTypeUpdate chargeTypeUpdate) {
        return ResponseEntity.ok(commands.update(id, chargeTypeUpdate, currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_TARIFFS_VIEW') and hasAuthority('PERM_TARIFFS_MANAGE')")
    public ResponseEntity<Void> archiveChargeType(UUID id) {
        commands.archive(id, currentUser.profile());
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_TARIFFS_VIEW') and hasAuthority('PERM_TARIFFS_MANAGE')")
    public ResponseEntity<Void> reorderChargeTypes(ChargeTypeOrder chargeTypeOrder) {
        commands.reorder(chargeTypeOrder, currentUser.profile());
        return ResponseEntity.noContent().build();
    }
}
