package app.nadel.plots.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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
class PlotQueries {

    private final PlotRepository plots;

    PlotQueries(PlotRepository plots) {
        this.plots = plots;
    }

    /**
     * The active plots of the viewer's cooperative that have a polygon, shaped for the viewer: everyone gets number,
     * street, kind and status; area and debt only with {@code PLOTS_VIEW}. Archived plots are not on the map.
     */
    @Transactional(readOnly = true)
    PlotFeatureCollection map(AccessProfile viewer) {
        boolean details = canSeeDetails(viewer);
        var collection = new PlotFeatureCollection();
        collection.setType(PlotFeatureCollection.TypeEnum.FEATURE_COLLECTION);
        collection.setFeatures(plots.findActiveWithStatus().stream()
                .filter(row -> row.plot().getBoundary() != null)
                .map(row -> toFeature(row, details))
                .toList());
        return collection;
    }

    /** The one place that decides who sees area and debt. Ownership of the plot joins the rule with spec 007. */
    private static boolean canSeeDetails(AccessProfile viewer) {
        return viewer.can(Permission.PLOTS_VIEW);
    }

    private static PlotFeature toFeature(PlotRow row, boolean details) {
        Plot plot = row.plot();

        var props = new PlotProperties();
        props.setNumber(plot.getNumber());
        props.setStreet(plot.getStreet());
        props.setKind(nadel.contract.model.PlotKind.fromValue(plot.getKind().name()));
        props.setStatus(PlotStatus.fromValue(PlotStatusPolicy.statusOf(plot.getKind(), row.status()).name()));
        if (details) {
            props.setAreaM2(plot.getAreaM2().doubleValue()); // area, not money
            BigDecimal debt = row.debt() == null ? BigDecimal.ZERO : row.debt();
            props.setDebt(debt.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        }

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

    private static List<List<List<Double>>> rings(Polygon polygon) {
        var rings = new ArrayList<List<List<Double>>>();
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
