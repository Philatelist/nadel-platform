package app.nadel.meters.internal;

import app.nadel.identity.CurrentUser;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterResource;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import nadel.contract.api.MetersApi;
import nadel.contract.model.Meter;
import nadel.contract.model.MeterCreate;
import nadel.contract.model.MeterRemoval;
import nadel.contract.model.MeterUpdate;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code meters}. Reading needs {@code METERS_VIEW}; every write needs {@code METERS_VIEW} and
 * {@code METERS_MANAGE}, so a manager can always see what they change. The platform operator has no permissions and
 * gets 403. {@code GET /plots/{id}/meters} is the exception: it is open to every member for the plots they own.
 */
@RestController
@RequestMapping("/api/v1")
class MetersController implements MetersApi {

    private final CurrentUser currentUser;
    private final MeterQueries queries;
    private final MeterCommands commands;

    MetersController(CurrentUser currentUser, MeterQueries queries, MeterCommands commands) {
        this.currentUser = currentUser;
        this.queries = queries;
        this.commands = commands;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_METERS_VIEW')")
    public ResponseEntity<List<Meter>> listMeters(nadel.contract.model.@Nullable MeterResource resource,
            nadel.contract.model.@Nullable MeterKind kind, @Nullable String street, @Nullable LocalDate noReadingSince,
            Boolean includeRemoved) {
        return ResponseEntity.ok(queries.list(resource == null ? null : MeterResource.valueOf(resource.getValue()),
                kind == null ? null : MeterKind.valueOf(kind.getValue()), street, noReadingSince,
                Boolean.TRUE.equals(includeRemoved)));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_METERS_VIEW') and hasAuthority('PERM_METERS_MANAGE')")
    public ResponseEntity<Meter> registerMeter(MeterCreate meterCreate) {
        Meter created = commands.register(meterCreate, currentUser.profile());
        return ResponseEntity.created(URI.create("/api/v1/meters/" + created.getId())).body(created);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_METERS_VIEW')")
    public ResponseEntity<Meter> getMeter(UUID id) {
        return ResponseEntity.ok(queries.get(id));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_METERS_VIEW') and hasAuthority('PERM_METERS_MANAGE')")
    public ResponseEntity<Meter> updateMeter(UUID id, MeterUpdate meterUpdate) {
        return ResponseEntity.ok(commands.update(id, meterUpdate, currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_METERS_VIEW') and hasAuthority('PERM_METERS_MANAGE')")
    public ResponseEntity<Void> deleteMeter(UUID id) {
        commands.delete(id, currentUser.profile());
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_METERS_VIEW') and hasAuthority('PERM_METERS_MANAGE')")
    public ResponseEntity<Meter> removeMeter(UUID id, MeterRemoval meterRemoval) {
        return ResponseEntity.ok(commands.remove(id, meterRemoval, currentUser.profile()));
    }

    /**
     * No {@code @PreAuthorize}: a member may ask for the plots they own, so the check is in {@link MeterQueries}
     * (anyone else gets 404, the platform operator 403).
     */
    @Override
    public ResponseEntity<List<Meter>> listPlotMeters(UUID id) {
        return ResponseEntity.ok(queries.forPlot(id, currentUser.profile()));
    }
}
