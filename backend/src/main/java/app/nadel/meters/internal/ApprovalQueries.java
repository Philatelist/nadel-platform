package app.nadel.meters.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.meters.ConsumptionCalculator.Reading;
import app.nadel.meters.internal.MeterReadingRecord.Status;
import app.nadel.meters.internal.ReadingValidator.MeterSpan;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.PendingReading;
import nadel.contract.model.ReadingStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The readings members submitted that wait for approval (the "На підтвердження" screen), each with its meter and what
 * approving it would give: the interval it would close and its warnings, worked out against the counted readings as
 * they are now. The number of queries does not grow with the number of readings.
 */
@Service
class ApprovalQueries {

    private final MeterReadingRepository readings;
    private final MeterRepository meters;
    private final PlotCatalog plots;
    private final ReadingFacts facts;
    private final MeterAccess access;
    private final Clock clock;

    ApprovalQueries(MeterReadingRepository readings, MeterRepository meters, PlotCatalog plots, ReadingFacts facts,
            MeterAccess access, Clock clock) {
        this.readings = readings;
        this.meters = meters;
        this.plots = plots;
        this.facts = facts;
        this.access = access;
        this.clock = clock;
    }

    /**
     * The pending readings, oldest reading date first. Only {@code PENDING} is supported (400 otherwise). The meter's
     * note is shown only to a viewer with {@code METERS_VIEW}.
     */
    @Transactional(readOnly = true)
    List<PendingReading> list(ReadingStatus status, AccessProfile viewer) {
        if (status != ReadingStatus.PENDING) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("status", ProblemCodes.INVALID)));
        }
        List<MeterReadingRecord> pending = readings.findByStatusOrderByTakenOnAscCreatedAtAscIdAsc(Status.PENDING);
        if (pending.isEmpty()) {
            return List.of();
        }
        List<UUID> meterIds = pending.stream().map(MeterReadingRecord::getMeterId).distinct().toList();
        Map<UUID, MeterRecord> byId = meters.findAllById(meterIds).stream()
                .collect(Collectors.toMap(MeterRecord::getId, Function.identity()));
        Map<UUID, PlotFacts> plotFacts = plots.findAll(byId.values().stream().map(MeterRecord::getPlotId)
                .filter(Objects::nonNull).distinct().toList());
        Map<UUID, Reading> latest = facts.latestApproved(meterIds);
        Map<UUID, List<Reading>> counted = facts.approvedByMeter(meterIds);
        boolean withNote = access.seesEveryMeter(viewer);
        LocalDate today = LocalDate.now(clock);

        List<PendingReading> result = new ArrayList<>(pending.size());
        for (MeterReadingRecord reading : pending) {
            MeterRecord meter = byId.get(reading.getMeterId());
            PlotFacts plot = meter.getPlotId() == null ? null : plotFacts.get(meter.getPlotId());
            // a pending reading is a reading: the meter is no longer deletable
            PendingReading item = new PendingReading(ReadingDtoMapper.toDto(reading, null, false),
                    MeterDtoMapper.toDto(meter, plot, latest.get(meter.getId()), true, withNote), List.of());
            MeterSpan span = MeterSpan.of(meter);
            List<Reading> others = counted.get(meter.getId());
            if (ReadingValidator.check(span, others, today, reading.getTakenOn(), reading.getValue()).isEmpty()) {
                ReadingValidator.intervalEndingAt(span, others, reading.getTakenOn(), reading.getValue())
                        .ifPresent(interval -> item.setInterval(ReadingDtoMapper.toDto(interval)));
                item.setWarnings(ReadingDtoMapper.toDto(
                        ReadingValidator.warnings(span, others, reading.getTakenOn(), reading.getValue())));
            }
            result.add(item);
        }
        return result;
    }
}
