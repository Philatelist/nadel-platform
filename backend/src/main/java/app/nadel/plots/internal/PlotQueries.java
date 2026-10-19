package app.nadel.plots.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.members.MemberDirectory;
import app.nadel.settings.CooperativeSettings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.PlotDetails;
import nadel.contract.model.PlotOwnerSummary;
import nadel.contract.model.PlotFeature;
import nadel.contract.model.PlotFeatureCollection;
import nadel.contract.model.PlotPage;
import nadel.contract.model.PlotProperties;
import nadel.contract.model.PlotStatus;
import nadel.contract.model.PlotSummary;
import nadel.contract.model.PolygonGeometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PlotQueries {

    /** Stands in for an empty list of owner ids: a native {@code IN ()} is invalid SQL; the owner branch is off then. */
    private static final List<UUID> NO_OWNER_IDS = List.of(new UUID(0, 0));

    private final PlotRepository plots;
    private final OwnershipQueries ownerships;
    private final MemberDirectory members;
    private final CooperativeSettings settings;

    PlotQueries(PlotRepository plots, OwnershipQueries ownerships, MemberDirectory members,
            CooperativeSettings settings) {
        this.plots = plots;
        this.ownerships = ownerships;
        this.members = members;
        this.settings = settings;
    }

    /**
     * What the viewer may see, decided once per request. A viewer with {@code PLOTS_VIEW} sees everything, so neither
     * their plots nor the cooperative setting is read; for anyone else the plots they own now and the setting are.
     * Call inside a transaction.
     */
    private ViewerScope scopeOf(AccessProfile viewer) {
        if (PlotDtoMapper.canSeeAllDetails(viewer)) {
            return ViewerScope.FULL;
        }
        Set<UUID> owned = viewer.memberId() == null
                ? Set.of()
                : ownerships.currentPlotIdsOf(viewer.memberId());
        return new ViewerScope(false, owned, settings.memberPlotStatusVisibility());
    }

    /**
     * The active plots of the viewer's cooperative that have a polygon, shaped for the viewer: everyone gets number,
     * street and kind; area and debt only with {@code PLOTS_VIEW} or for plots the viewer owns; the status only where
     * {@link ViewerScope#canSeeStatus} allows. Archived plots are not on the map.
     */
    @Transactional(readOnly = true)
    PlotFeatureCollection map(AccessProfile viewer) {
        ViewerScope scope = scopeOf(viewer);
        var collection = new PlotFeatureCollection();
        collection.setType(PlotFeatureCollection.TypeEnum.FEATURE_COLLECTION);
        collection.setFeatures(plots.findActiveWithStatus().stream()
                .filter(row -> row.plot().getBoundary() != null)
                .map(row -> toFeature(scope, row))
                .toList());
        return collection;
    }

    /**
     * One page of plots in natural order of the number. A viewer without {@code PLOTS_VIEW} gets the restricted
     * summaries (details only of the plots they own), never sees archived plots and can search neither by cadastral
     * number nor by owner. Under {@code OWN_ONLY} their status filter matches only the plots they own. A viewer with
     * {@code PLOTS_VIEW} also finds plots by the name of a current owner and gets each plot's owners.
     */
    @Transactional(readOnly = true)
    PlotPage list(AccessProfile viewer, String query, nadel.contract.model.PlotKind kind, PlotStatus status,
            boolean includeArchived, int page, int size) {
        ViewerScope scope = scopeOf(viewer);
        boolean full = scope.full();
        boolean ownedOnly = status != null && scope.statusFilterLimitedToOwned();
        if (ownedOnly && scope.ownedPlotIds().isEmpty()) {
            // nothing can match; an empty id list would also be an invalid native IN ()
            return new PlotPage(List.of(), page, size, 0L, 0);
        }
        String text = query == null ? "" : query.strip();
        List<UUID> ownerIds = full && !text.isEmpty() ? members.idsMatchingName(text) : List.of();
        Page<Plot> result = plots.search(full && includeArchived, kind == null ? null : kind.getValue(),
                status == null ? null : status.getValue(), likeContains(text), full, !ownerIds.isEmpty(),
                ownerIds.isEmpty() ? NO_OWNER_IDS : ownerIds, ownedOnly,
                ownedOnly ? List.copyOf(scope.ownedPlotIds()) : NO_OWNER_IDS, PageRequest.of(page, size));
        List<UUID> plotIds = result.getContent().stream().map(Plot::getId).toList();
        Map<UUID, PlotRow> rows = plots.findRowsByIds(plotIds).stream()
                .collect(Collectors.toMap(row -> row.plot().getId(), Function.identity()));
        Map<UUID, List<PlotOwnerSummary>> owners = full ? ownerships.currentOwners(plotIds) : Map.of();
        List<PlotSummary> items = result.getContent().stream()
                .map(plot -> PlotDtoMapper.toSummary(scope, rows.get(plot.getId()), owners.get(plot.getId())))
                .toList();
        return new PlotPage(items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    /**
     * The card of one plot. Empty when the plot does not exist, belongs to another cooperative (RLS), or is archived
     * and the viewer does not see every plot in full. The notes and timestamps are for the board only; the owners of
     * the plot see its other details.
     */
    @Transactional(readOnly = true)
    Optional<PlotDetails> card(UUID id, AccessProfile viewer) {
        ViewerScope scope = scopeOf(viewer);
        return plots.findRowById(id)
                .filter(row -> row.plot().getArchivedAt() == null || scope.full())
                .map(row -> PlotDtoMapper.toDetails(scope, row));
    }

    /** {@code %text%} for a LIKE with escape character {@code !}; user input never changes the pattern's shape. */
    private static String likeContains(String text) {
        String escaped = text.toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static PlotFeature toFeature(ViewerScope scope, PlotRow row) {
        Plot plot = row.plot();
        boolean details = scope.canSeeDetails(plot.getId());

        var props = new PlotProperties();
        props.setNumber(plot.getNumber());
        props.setStreet(plot.getStreet());
        props.setKind(nadel.contract.model.PlotKind.fromValue(plot.getKind().name()));
        if (scope.canSeeStatus(plot.getId())) {
            props.setStatus(PlotDtoMapper.statusOf(row));
        }
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
