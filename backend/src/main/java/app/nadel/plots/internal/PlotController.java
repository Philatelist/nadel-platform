package app.nadel.plots.internal;

import nadel.contract.api.PlotsApi;
import nadel.contract.model.PlotFeatureCollection;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class PlotController implements PlotsApi {

    private final PlotMapQuery mapQuery;

    PlotController(PlotMapQuery mapQuery) {
        this.mapQuery = mapQuery;
    }

    @Override
    public ResponseEntity<PlotFeatureCollection> getPlots() {
        return ResponseEntity.ok(mapQuery.currentTenantMap());
    }
}
