package app.nadel.meters.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import app.nadel.meters.ConsumptionCalculator;
import app.nadel.meters.ConsumptionCalculator.Interval;
import app.nadel.meters.ConsumptionCalculator.MeterHistory;
import app.nadel.meters.ConsumptionCalculator.Reading;
import app.nadel.meters.ReadingUsage;
import app.nadel.meters.internal.ReadingValidator.MeterSpan;
import app.nadel.meters.internal.ReadingValidator.Neighbours;
import app.nadel.problem.ApiProblemException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.MeterReading;
import nadel.contract.model.MeterReadingHistory;
import nadel.contract.model.ReadingInterval;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the readings of a meter with the intervals between its counted points. Only approved readings count; pending,
 * rejected and superseded rows are part of the history but never of the consumption.
 */
@Service
class ReadingQueries {

    /** Newest date first; on one date the counted reading first, then by status, then the newest row first. */
    private static final Comparator<MeterReadingRecord> HISTORY_ORDER = Comparator
            .comparing(MeterReadingRecord::getTakenOn, Comparator.reverseOrder())
            .thenComparing(MeterReadingRecord::getStatus)
            .thenComparing(MeterReadingRecord::getCreatedAt, Comparator.reverseOrder())
            .thenComparing(MeterReadingRecord::getId);

    private final MeterRepository meters;
    private final MeterReadingRepository readings;
    private final MeterAccess access;
    private final List<ReadingUsage> usages;

    ReadingQueries(MeterRepository meters, MeterReadingRepository readings, MeterAccess access,
            List<ReadingUsage> usages) {
        this.meters = meters;
        this.readings = readings;
        this.access = access;
        this.usages = usages;
    }

    /**
     * The readings and intervals of a meter. A caller with {@code METERS_VIEW} may ask for any meter, anyone else only
     * for a meter of a plot they own now; every other case, an unknown meter and a meter of another cooperative
     * included, is the same 404. The platform operator gets 403.
     */
    @Transactional(readOnly = true)
    MeterReadingHistory history(UUID meterId, AccessProfile viewer) {
        access.requireCooperativeData(viewer);
        MeterRecord meter = meters.findById(meterId).orElseThrow(ApiProblemException::notFound);
        if (!access.maySee(viewer, meter)) {
            throw ApiProblemException.notFound();
        }
        List<MeterReadingRecord> all = readings.findByMeterId(meterId);
        List<Reading> counted = all.stream().filter(MeterReadingRecord::isApproved)
                .sorted(Comparator.comparing(MeterReadingRecord::getTakenOn))
                .map(MeterReadingRecord::toReading).toList();
        MeterSpan span = MeterSpan.of(meter);
        List<Interval> intervals = ConsumptionCalculator.intervals(new MeterHistory(meter.getInstalledOn(),
                meter.getInitialValue(), meter.getRemovedOn(), meter.getFinalValue(), counted));
        Map<LocalDate, Interval> byEnd = intervals.stream()
                .collect(Collectors.toMap(Interval::to, Function.identity()));
        boolean mayChange = viewer.can(Permission.READINGS_ENTER);

        List<MeterReading> history = all.stream().sorted(HISTORY_ORDER)
                .map(reading -> ReadingDtoMapper.toDto(reading,
                        reading.isApproved() ? byEnd.get(reading.getTakenOn()) : null,
                        mayChange && reading.isApproved() && !used(meter, span, counted, reading.getTakenOn())))
                .toList();
        List<ReadingInterval> newestFirst = intervals.reversed().stream().map(ReadingDtoMapper::toDto).toList();
        return new MeterReadingHistory(meterId, history, newestFirst);
    }

    /** Whether a charge uses the readings around a counted reading on {@code date}, so it can no longer change. */
    private boolean used(MeterRecord meter, MeterSpan span, List<Reading> counted, LocalDate date) {
        Neighbours neighbours = ReadingValidator.neighbours(span, counted, date);
        LocalDate from = neighbours.spanFrom(date);
        LocalDate to = neighbours.spanTo(date);
        return usages.stream().anyMatch(usage -> usage.isUsed(meter.getTenantId(), meter.getId(), from, to));
    }
}
