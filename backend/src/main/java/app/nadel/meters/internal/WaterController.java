package app.nadel.meters.internal;

import app.nadel.identity.CurrentUser;
import java.util.List;
import java.util.UUID;
import nadel.contract.api.WaterApi;
import nadel.contract.model.PlotWaterConnections;
import nadel.contract.model.WaterConnectionCreate;
import nadel.contract.model.WaterConnectionEnd;
import nadel.contract.model.WaterSource;
import nadel.contract.model.WaterSourceConnection;
import nadel.contract.model.WaterSourceDisconnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code water} (feature 011 §2.8). Changes need {@code METERS_MANAGE}; the water sources list
 * needs {@code PLOTS_VIEW}; the connections of a plot are open to {@code PLOTS_VIEW} and to the plot's current owners
 * (checked in {@link WaterConnectionQueries}). The platform operator has no permissions and gets 403.
 */
@RestController
@RequestMapping("/api/v1")
class WaterController implements WaterApi {

    private final CurrentUser currentUser;
    private final WaterConnectionQueries queries;
    private final WaterConnectionCommands commands;

    WaterController(CurrentUser currentUser, WaterConnectionQueries queries, WaterConnectionCommands commands) {
        this.currentUser = currentUser;
        this.queries = queries;
        this.commands = commands;
    }

    /** No {@code @PreAuthorize}: an owner may ask for their own plot (anyone else gets 404, the operator 403). */
    @Override
    public ResponseEntity<PlotWaterConnections> getPlotWaterConnections(UUID id) {
        return ResponseEntity.ok(queries.forPlot(id, currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_METERS_MANAGE')")
    public ResponseEntity<PlotWaterConnections> connectPlotWater(UUID id, WaterConnectionCreate body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commands.connect(id, body.getSourceMeterId(), body.getFrom(), currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_METERS_MANAGE')")
    public ResponseEntity<PlotWaterConnections> endPlotWaterConnection(UUID id, UUID connectionId,
            WaterConnectionEnd body) {
        return ResponseEntity.ok(commands.end(id, connectionId, body.getUntil(), currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_PLOTS_VIEW')")
    public ResponseEntity<List<WaterSource>> listWaterSources() {
        return ResponseEntity.ok(queries.sources());
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_METERS_MANAGE')")
    public ResponseEntity<WaterSource> connectWaterSourcePlots(String sourceId, WaterSourceConnection body) {
        return ResponseEntity.ok(commands.connectAll(sourceId, body.getPlotIds(), body.getFrom(),
                currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_METERS_MANAGE')")
    public ResponseEntity<WaterSource> disconnectWaterSourcePlots(String sourceId, WaterSourceDisconnection body) {
        return ResponseEntity.ok(commands.disconnectAll(sourceId, body.getPlotIds(), body.getUntil(),
                currentUser.profile()));
    }
}
