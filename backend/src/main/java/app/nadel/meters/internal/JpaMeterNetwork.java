package app.nadel.meters.internal;

import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterNetwork;
import app.nadel.meters.MeterResource;
import app.nadel.meters.internal.MeterReadingRecord.Status;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves {@link MeterNetwork} from the meter register and the approved readings; RLS limits both to the caller's
 * cooperative. The "not read since" rule is the one of the reading round's filter ({@code noReadingSince}): the last
 * point of a meter is its latest approved reading, else its installation, and the meter is stale when that point is
 * dated before the given day. The points of a meter are those of {@code ConsumptionCalculator}: the initial value, the
 * approved readings and the final value, a reading taking the place of a boundary point on its date. A water meter of
 * a plot that is not connected to water on the check date is never stale (feature 011 §2.8).
 */
@Component
class JpaMeterNetwork implements MeterNetwork {

    /** Values per {@code IN} list. */
    private static final int CHUNK = 500;

    private final MeterRepository meters;
    private final MeterReadingRepository readings;
    private final WaterConnectionRepository waterConnections;

    JpaMeterNetwork(MeterRepository meters, MeterReadingRepository readings,
            WaterConnectionRepository waterConnections) {
        this.meters = meters;
        this.readings = readings;
        this.waterConnections = waterConnections;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaleMeter> staleMeters(LocalDate asOf, int days) {
        LocalDate since = asOf.minusDays(days);
        List<MeterRecord> working = meters.findAll().stream()
                .filter(meter -> meter.getKind() == MeterKind.PLOT && meter.getPlotId() != null)
                .filter(meter -> !meter.getInstalledOn().isAfter(asOf))
                .filter(meter -> meter.getRemovedOn() == null || meter.getRemovedOn().isAfter(asOf))
                .toList();
        // a plot that is not connected to water on the day owes no water reading (feature 011 §2.8)
        Set<UUID> connected = connectedOn(working, asOf);
        working = working.stream()
                .filter(meter -> meter.getResource() != MeterResource.WATER || connected.contains(meter.getPlotId()))
                .toList();
        Map<UUID, LocalDate> latest = new HashMap<>();
        List<UUID> ids = working.stream().map(MeterRecord::getId).toList();
        for (int from = 0; from < ids.size(); from += CHUNK) {
            for (Object[] row : readings.latestDatesOnOrBefore(ids.subList(from, Math.min(from + CHUNK, ids.size())),
                    Status.APPROVED, asOf)) {
                latest.put((UUID) row[0], (LocalDate) row[1]);
            }
        }
        List<StaleMeter> stale = new ArrayList<>();
        for (MeterRecord meter : working) {
            LocalDate last = latest.getOrDefault(meter.getId(), meter.getInstalledOn());
            if (last.isBefore(since)) {
                stale.add(new StaleMeter(meter.getId(), meter.getPlotId(), meter.getResource(), last));
            }
        }
        return stale;
    }

    /** The plots of the water meters among {@code working} that are connected to water on the day. */
    private Set<UUID> connectedOn(List<MeterRecord> working, LocalDate day) {
        List<UUID> plotIds = working.stream().filter(meter -> meter.getResource() == MeterResource.WATER)
                .map(MeterRecord::getPlotId).filter(Objects::nonNull).distinct().toList();
        Set<UUID> connected = new HashSet<>();
        for (int from = 0; from < plotIds.size(); from += CHUNK) {
            connected.addAll(waterConnections.plotsConnectedOn(
                    plotIds.subList(from, Math.min(from + CHUNK, plotIds.size())), day));
        }
        return connected;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY) // a row lock needs a read-write transaction
    public Optional<MeterPoints> lockSharedPoints(UUID meterId) {
        return meters.findByIdForShare(meterId).map(meter -> {
            TreeMap<LocalDate, Point> points = new TreeMap<>();
            points.put(meter.getInstalledOn(), new Point(meter.getInstalledOn(), meter.getInitialValue(),
                    "INSTALL:" + meter.getId(), PointKind.INSTALLATION));
            if (meter.getRemovedOn() != null && meter.getFinalValue() != null) {
                points.put(meter.getRemovedOn(), new Point(meter.getRemovedOn(), meter.getFinalValue(),
                        "REMOVAL:" + meter.getId(), PointKind.REMOVAL));
            }
            for (MeterReadingRecord reading : readings.findByMeterIdAndStatusOrderByTakenOn(meter.getId(),
                    Status.APPROVED)) {
                points.put(reading.getTakenOn(), new Point(reading.getTakenOn(), reading.getValue(),
                        reading.getId().toString(), PointKind.READING));
            }
            return new MeterPoints(meter.getId(), meter.getKind(), meter.getResource(), meter.getPlotId(),
                    List.copyOf(points.values()));
        });
    }
}
