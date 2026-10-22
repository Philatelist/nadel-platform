package app.nadel.meters.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.meters.ConsumptionCalculator.Reading;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterResource;
import app.nadel.meters.internal.MeterChains.Neighbours;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import java.text.Collator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.Meter;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the meter register, a meter card and the meters of a plot. The number of queries does not grow with the number
 * of meters: the plots, the latest readings, the meters with readings and the replacement links are loaded in bulk.
 */
@Service
class MeterQueries {

    /** Removed meters after active ones, then electricity before water, the newest installation first. */
    private static final Comparator<MeterRecord> WITHIN_A_PLOT = Comparator
            .comparing((MeterRecord meter) -> !meter.isActive())
            .thenComparing(MeterRecord::getResource)
            .thenComparing(MeterRecord::getInstalledOn, Comparator.reverseOrder())
            .thenComparing(MeterRecord::getId);

    private final MeterRepository meters;
    private final PlotCatalog plots;
    private final MeterAccess access;
    private final ReadingFacts readings;
    private final MeterChains chains;

    MeterQueries(MeterRepository meters, PlotCatalog plots, MeterAccess access, ReadingFacts readings,
            MeterChains chains) {
        this.meters = meters;
        this.plots = plots;
        this.access = access;
        this.readings = readings;
        this.chains = chains;
    }

    /**
     * The register: plot meters in street and number order (electricity before water, active before removed), then the
     * other meters by name. Every filter is optional; see {@code listMeters} in the API contract.
     */
    @Transactional(readOnly = true)
    List<Meter> list(@Nullable MeterResource resource, @Nullable MeterKind kind, @Nullable String street,
            @Nullable LocalDate noReadingSince, boolean includeRemoved) {
        return list(resource, kind, street, noReadingSince, includeRemoved, true);
    }

    /**
     * The register as {@link #list(MeterResource, MeterKind, String, LocalDate, boolean)}, with the board's note only
     * when {@code withNote} (the reading round of a caller without {@code METERS_VIEW} leaves it out).
     */
    @Transactional(readOnly = true)
    List<Meter> list(@Nullable MeterResource resource, @Nullable MeterKind kind, @Nullable String street,
            @Nullable LocalDate noReadingSince, boolean includeRemoved, boolean withNote) {
        List<MeterRecord> candidates = (includeRemoved && noReadingSince == null ? meters.findAll()
                : meters.findByRemovedOnIsNull()).stream()
                .filter(meter -> resource == null || meter.getResource() == resource)
                .filter(meter -> kind == null || meter.getKind() == kind)
                .toList();
        Map<UUID, PlotFacts> plotFacts = plots.findAll(candidates.stream().map(MeterRecord::getPlotId)
                .filter(Objects::nonNull).distinct().toList());
        String wantedStreet = street == null || street.isBlank() ? null : normalised(street);
        if (wantedStreet != null) {
            candidates = candidates.stream()
                    .filter(meter -> meter.getPlotId() != null)
                    .filter(meter -> {
                        PlotFacts plot = plotFacts.get(meter.getPlotId());
                        return plot != null && plot.street() != null && normalised(plot.street()).equals(wantedStreet);
                    })
                    .toList();
        }
        List<UUID> ids = candidates.stream().map(MeterRecord::getId).toList();
        Map<UUID, Reading> latest = readings.latestApproved(ids);
        if (noReadingSince != null) {
            candidates = candidates.stream()
                    .filter(meter -> MeterDtoMapper.lastReading(meter, latest.get(meter.getId())).takenOn()
                            .isBefore(noReadingSince))
                    .toList();
        }
        Set<UUID> withReadings = readings.withReadings(candidates.stream().map(MeterRecord::getId).toList());
        Map<UUID, Neighbours> neighbours = chains.neighboursOf(candidates);
        return inRegisterOrder(candidates).stream()
                .map(meter -> MeterDtoMapper.toDto(meter,
                        meter.getPlotId() == null ? null : plotFacts.get(meter.getPlotId()), latest.get(meter.getId()),
                        withReadings.contains(meter.getId()), withNote,
                        neighbours.getOrDefault(meter.getId(), Neighbours.NONE)))
                .toList();
    }

    /** The meter card; 404 when unknown or of another cooperative (RLS). */
    @Transactional(readOnly = true)
    Meter get(UUID id) {
        return card(meters.findById(id).orElseThrow(ApiProblemException::notFound), true);
    }

    /**
     * Every meter the plot has had. A caller with {@code METERS_VIEW} may ask for any plot, anyone else only for a plot
     * they own now and does not see the board's note; every other case, an unknown plot and a plot of another
     * cooperative included, is the same 404 {@code PLOT_NOT_FOUND}. The platform operator gets 403.
     */
    @Transactional(readOnly = true)
    List<Meter> forPlot(UUID plotId, AccessProfile viewer) {
        access.requireCooperativeData(viewer);
        PlotFacts plot = plots.find(plotId).orElseThrow(MeterAccess::plotNotFound);
        boolean full = access.seesEveryMeter(viewer);
        if (!full && !access.ownsNow(viewer, plotId)) {
            throw MeterAccess.plotNotFound();
        }
        List<MeterRecord> found = meters.findByPlotId(plotId).stream().sorted(WITHIN_A_PLOT).toList();
        List<UUID> ids = found.stream().map(MeterRecord::getId).toList();
        Map<UUID, Reading> latest = readings.latestApproved(ids);
        Set<UUID> withReadings = readings.withReadings(ids);
        Map<UUID, Neighbours> neighbours = chains.neighboursOf(found);
        return found.stream()
                .map(meter -> MeterDtoMapper.toDto(meter, plot, latest.get(meter.getId()),
                        withReadings.contains(meter.getId()), full,
                        neighbours.getOrDefault(meter.getId(), Neighbours.NONE)))
                .toList();
    }

    /** One meter as the API shows it, read in the caller's transaction. */
    Meter card(MeterRecord meter, boolean withNote) {
        PlotFacts plot = meter.getPlotId() == null ? null : plots.find(meter.getPlotId()).orElse(null);
        Reading latest = readings.latestApproved(meter.getId()).orElse(null);
        boolean hasReadings = !readings.withReadings(List.of(meter.getId())).isEmpty();
        return MeterDtoMapper.toDto(meter, plot, latest, hasReadings, withNote, chains.neighboursOf(meter));
    }

    /** Plot meters in street and number order, then the other meters by name (Ukrainian alphabet). */
    private List<MeterRecord> inRegisterOrder(List<MeterRecord> candidates) {
        Map<UUID, List<MeterRecord>> byPlot = new HashMap<>();
        List<MeterRecord> named = new ArrayList<>();
        for (MeterRecord meter : candidates) {
            if (meter.getPlotId() != null) {
                byPlot.computeIfAbsent(meter.getPlotId(), id -> new ArrayList<>()).add(meter);
            } else {
                named.add(meter);
            }
        }
        List<MeterRecord> ordered = new ArrayList<>(candidates.size());
        for (UUID plotId : plots.inStreetOrder(byPlot.keySet())) {
            byPlot.get(plotId).stream().sorted(WITHIN_A_PLOT).forEach(ordered::add);
        }
        Collator collator = Collator.getInstance(Locale.forLanguageTag("uk"));
        named.stream()
                .sorted(Comparator.comparing((MeterRecord meter) -> Objects.requireNonNullElse(meter.getName(), ""),
                        collator).thenComparing(MeterRecord::getId))
                .forEach(ordered::add);
        return ordered;
    }

    private static String normalised(String street) {
        return street.strip().toLowerCase(Locale.ROOT);
    }
}
