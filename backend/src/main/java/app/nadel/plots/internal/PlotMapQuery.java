package app.nadel.plots.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import nadel.contract.model.PlotFeature;
import nadel.contract.model.PlotFeatureCollection;
import nadel.contract.model.PlotProperties;
import nadel.contract.model.PlotStatus;
import nadel.contract.model.PolygonGeometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PlotMapQuery {

    private final PlotRepository plots;

    PlotMapQuery(PlotRepository plots) {
        this.plots = plots;
    }

    @Transactional(readOnly = true)
    PlotFeatureCollection currentTenantMap() {
        var collection = new PlotFeatureCollection();
        collection.setType(PlotFeatureCollection.TypeEnum.FEATURE_COLLECTION);
        collection.setFeatures(plots.findAllWithStatus().stream()
                .filter(row -> row.plot().getBoundary() != null)
                .map(PlotMapQuery::toFeature)
                .toList());
        return collection;
    }

    private static PlotFeature toFeature(PlotRow row) {
        Plot plot = row.plot();

        var props = new PlotProperties();
        props.setNumber(plot.getNumber());
        props.setStreet(plot.getStreet());
        props.setKind(nadel.contract.model.PlotKind.fromValue(plot.getKind().name()));
        props.setAreaM2(plot.getAreaM2().doubleValue()); // area, not money
        props.setStatus(statusOf(plot, row.status()));
        BigDecimal debt = row.debt() == null ? BigDecimal.ZERO : row.debt();
        props.setDebt(debt.setScale(2, RoundingMode.UNNECESSARY).toPlainString());

        var geometry = new PolygonGeometry();
        geometry.setType(PolygonGeometry.TypeEnum.POLYGON);
        geometry.setCoordinates(rings(plot.getBoundary()));

        var feature = new PlotFeature();
        feature.setType(PlotFeature.TypeEnum.FEATURE);
        feature.setId(plot.getId());
        feature.setGeometry(geometry);
        feature.setProperties(props);
        return feature;
    }

    private static PlotStatus statusOf(Plot plot, PlotStatusCode cached) {
        if (plot.getKind() == PlotKind.COMMON || cached == null) {
            return PlotStatus.GREY;
        }
        return PlotStatus.fromValue(cached.name());
    }

    private static List<List<List<Double>>> rings(Polygon polygon) {
        var rings = new java.util.ArrayList<List<List<Double>>>();
        rings.add(ring(polygon.getExteriorRing()));
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            rings.add(ring(polygon.getInteriorRingN(i)));
        }
        return rings;
    }

    private static List<List<Double>> ring(LineString line) {
        return Arrays.stream(line.getCoordinates())
                .map(c -> List.of(c.getX(), c.getY()))
                .toList();
    }
}
