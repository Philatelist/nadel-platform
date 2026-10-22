package app.nadel.meters.internal;

import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import app.nadel.meters.ConsumptionCalculator.Interval;
import app.nadel.meters.ConsumptionCalculator.Reading;
import app.nadel.meters.InvalidMeterValueException;
import app.nadel.meters.MeterValue;
import app.nadel.meters.PlotMeterPointsChanged;
import app.nadel.meters.PlotMeterPointsChanged.Cause;
import app.nadel.meters.ReadingUsage;
import app.nadel.meters.internal.MeterReadingRecord.Status;
import app.nadel.meters.internal.ReadingValidator.MeterSpan;
import app.nadel.meters.internal.ReadingValidator.Neighbours;
import app.nadel.meters.internal.ReadingValidator.Warning;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import nadel.contract.model.MeterReading;
import nadel.contract.model.ReadingBatch;
import nadel.contract.model.ReadingBatchResult;
import nadel.contract.model.ReadingCorrection;
import nadel.contract.model.ReadingEntry;
import nadel.contract.model.ReadingRejection;
import nadel.contract.model.ReadingRowError;
import nadel.contract.model.ReadingRowOutcome;
import nadel.contract.model.ReadingRowResult;
import nadel.contract.model.ReadingSaved;
import nadel.contract.model.ReadingSubmission;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Enters, corrects and deletes readings, takes a member's submission and approves or rejects it (spec 010, section
 * 2.4). The cooperative comes from the caller's {@link AccessProfile}, never from the request; the caller holds
 * {@code READINGS_ENTER} for entry, correction and deletion and {@code READINGS_APPROVE} for a decision (checked on the
 * controller), and a submission is open to a current owner of the plot (checked here).
 * <p>
 * Every write locks the meter {@code FOR UPDATE} first, so the writes of one meter (readings, removal, deletion) run one
 * after another and the ordering check sees the committed readings. A reading of the same date replaces the counted
 * one: the old row becomes {@code SUPERSEDED} and is flushed before the new {@code APPROVED} row is inserted (the
 * partial unique index {@code meter_reading_approved_uq} allows one approved reading per meter and date), and only then
 * is the old row linked to its successor. A correction is a new row that supersedes the corrected one, so the history
 * keeps both; a deletion removes the row. Before any change the {@link ReadingUsage} ports are asked whether a charge
 * uses the readings around the date ({@code READING_IN_USE}), and every change of a counted reading of a plot meter
 * publishes {@link PlotMeterPointsChanged} in its transaction (the ledger charges the intervals around it).
 * <p>
 * A member's reading waits for approval ({@code PENDING}): it never counts and never supersedes a counted reading; a
 * second pending reading of the same meter and date supersedes the first. Approval checks it again against the counted
 * readings as they are then and supersedes a counted reading of the same date as an entry does.
 * <p>
 * A batch saves each row in its own transaction: a refused row is reported in the answer and never undoes another.
 * Every saved change publishes its audit event in its own transaction.
 */
@Service
class ReadingCommands {

    private final MeterRepository meters;
    private final MeterReadingRepository readings;
    private final MeterAccess access;
    private final List<ReadingUsage> usages;
    private final MeterAuditPublisher audit;
    private final MeterPointEvents pointEvents;
    private final Clock clock;
    private final TransactionTemplate rowTransaction;

    @PersistenceContext
    private EntityManager entityManager;

    ReadingCommands(MeterRepository meters, MeterReadingRepository readings, MeterAccess access,
            List<ReadingUsage> usages, MeterAuditPublisher audit, MeterPointEvents pointEvents, Clock clock,
            PlatformTransactionManager transactions) {
        this.meters = meters;
        this.readings = readings;
        this.access = access;
        this.usages = usages;
        this.audit = audit;
        this.pointEvents = pointEvents;
        this.clock = clock;
        this.rowTransaction = new TransactionTemplate(transactions);
    }

    /** A saved reading: the new row, the interval it closes, the rows it superseded and its warnings. */
    private record Saved(MeterReadingRecord reading, @Nullable Interval interval, List<UUID> superseded,
            List<Warning> warnings) {
    }

    /**
     * Enters the rows one by one, each in its own transaction (this method has none). The answer has one result per
     * row in request order: saved, or refused with the code, field and parameters of the problem.
     */
    ReadingBatchResult enter(ReadingBatch batch, AccessProfile actor) {
        List<ReadingRowResult> results = new ArrayList<>();
        List<ReadingEntry> rows = batch.getRows();
        for (int index = 0; index < rows.size(); index++) {
            ReadingEntry row = rows.get(index);
            ReadingRowResult result;
            try {
                Saved saved = Objects.requireNonNull(rowTransaction.execute(
                        status -> enterOne(row.getMeterId(), row.getTakenOn(), row.getValue(), actor)));
                result = new ReadingRowResult(index, row.getMeterId(), ReadingRowOutcome.SAVED, saved.superseded(),
                        ReadingDtoMapper.toDto(saved.warnings()));
                result.setReading(ReadingDtoMapper.toDto(saved.reading(), saved.interval(), true));
            } catch (ApiProblemException refused) {
                result = new ReadingRowResult(index, row.getMeterId(), ReadingRowOutcome.REJECTED, List.of(),
                        List.of());
                result.setError(rowError(refused));
            }
            results.add(result);
        }
        return new ReadingBatchResult(results);
    }

    /**
     * Corrects the date or value of a counted reading: a new row takes its place, and the corrected row (and a counted
     * reading on the new date) become {@code SUPERSEDED}. A request that changes nothing is answered with the reading
     * as it is and not audited.
     */
    @Transactional
    ReadingSaved correct(UUID readingId, ReadingCorrection input, AccessProfile actor) {
        MeterValue value = parse(input.getValue());
        MeterRecord meter = lockMeterOf(readingId);
        MeterReadingRecord reading = readings.findById(readingId).orElseThrow(ApiProblemException::notFound);
        if (!reading.isApproved()) {
            throw ReadingProblem.statusInvalid();
        }
        MeterSpan span = MeterSpan.of(meter);
        LocalDate oldDate = reading.getTakenOn();
        LocalDate newDate = input.getTakenOn();
        List<MeterReadingRecord> others = approved(meter).stream()
                .filter(other -> !other.getId().equals(readingId)).toList();
        if (newDate.equals(oldDate) && value.equals(reading.getValue())) {
            Interval interval = ReadingValidator.intervalEndingAt(span, readingsOf(others), oldDate, value)
                    .orElse(null);
            return saved(new Saved(reading, interval, List.of(), List.of()));
        }
        MeterReadingRecord sameDate = onDate(others, newDate);
        List<Reading> counted = readingsOf(others.stream().filter(other -> other != sameDate).toList());
        ReadingValidator.check(span, counted, LocalDate.now(clock), newDate, value).ifPresent(violation -> {
            throw ReadingProblem.of(violation);
        });
        Neighbours oldNeighbours = ReadingValidator.neighbours(span, readingsOf(others), oldDate);
        Neighbours newNeighbours = ReadingValidator.neighbours(span, counted, newDate);
        ensureNotUsed(meter, earlier(oldNeighbours.spanFrom(oldDate), newNeighbours.spanFrom(newDate)),
                later(oldNeighbours.spanTo(oldDate), newNeighbours.spanTo(newDate)));

        List<MeterReadingRecord> replaced = sameDate == null ? List.of(reading) : List.of(reading, sameDate);
        MeterReadingRecord created = MeterReadingRecord.entered(meter.getTenantId(), meter.getId(), newDate, value,
                actor.memberId(), clock.instant());
        replace(replaced, created);
        audit.publishReading(actor, "READING_CORRECTED", meter, List.of(
                new FieldChange("takenOn", oldDate.toString(), newDate.toString()),
                new FieldChange("value", reading.getValue().toString(), value.toString())));
        pointEvents.changed(meter, newDate, Cause.READING_CORRECTED);
        if (!oldDate.equals(newDate)) {
            pointEvents.changed(meter, oldDate, Cause.READING_CORRECTED);
        }
        return saved(new Saved(created, ReadingValidator.intervalEndingAt(span, counted, newDate, value).orElse(null),
                replaced.stream().map(MeterReadingRecord::getId).toList(),
                ReadingValidator.warnings(span, counted, newDate, value)));
    }

    /** Deletes a counted reading; the intervals around it join. A reading it had replaced stays superseded. */
    @Transactional
    void delete(UUID readingId, AccessProfile actor) {
        MeterRecord meter = lockMeterOf(readingId);
        MeterReadingRecord reading = readings.findById(readingId).orElseThrow(ApiProblemException::notFound);
        if (!reading.isApproved()) {
            throw ReadingProblem.statusInvalid();
        }
        LocalDate date = reading.getTakenOn();
        List<Reading> others = readingsOf(approved(meter).stream()
                .filter(other -> !other.getId().equals(readingId)).toList());
        Neighbours neighbours = ReadingValidator.neighbours(MeterSpan.of(meter), others, date);
        ensureNotUsed(meter, neighbours.spanFrom(date), neighbours.spanTo(date));
        readings.delete(reading);
        entityManager.flush();
        audit.publishReading(actor, "READING_DELETED", meter, List.of(
                new FieldChange("takenOn", date.toString(), null),
                new FieldChange("value", reading.getValue().toString(), null)));
        pointEvents.changed(meter, date, Cause.READING_DELETED);
    }

    // ---- the member flow

    /**
     * A member submits the reading of an active meter of a plot they own now (spec 010, section 2.4). It waits for
     * approval ({@code PENDING}, source {@code MEMBER}) and never counts or supersedes a counted reading until then; a
     * pending reading of the same meter and date is superseded by it, and the same value again changes nothing. It is
     * checked against the counted readings as the board's entry is. Any plot the caller does not own now (an unknown
     * plot and a plot of another cooperative included) is the same 404 {@code PLOT_NOT_FOUND}; a meter that is not a
     * meter of that plot is 404 {@code NOT_FOUND} on {@code meterId}; the platform operator gets 403.
     */
    @Transactional
    ReadingSaved submit(UUID plotId, ReadingSubmission input, AccessProfile actor) {
        access.requireCooperativeData(actor);
        if (!access.ownsNow(actor, plotId)) {
            throw MeterAccess.plotNotFound();
        }
        MeterValue value = parse(input.getValue());
        MeterRecord meter = meters.findByIdForUpdate(input.getMeterId())
                .filter(found -> plotId.equals(found.getPlotId()))
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, ProblemCodes.NOT_FOUND,
                        "The meter was not found.", List.of(new FieldProblem("meterId", ProblemCodes.NOT_FOUND))));
        if (!meter.isActive()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.METER_REMOVED,
                    "The meter is removed; it takes no more readings from members.");
        }
        LocalDate date = input.getTakenOn() != null ? input.getTakenOn() : LocalDate.now(clock);
        MeterSpan span = MeterSpan.of(meter);
        List<Reading> counted = readingsOf(approved(meter));
        ReadingValidator.check(span, counted, LocalDate.now(clock), date, value).ifPresent(violation -> {
            throw ReadingProblem.of(violation);
        });
        List<Warning> warnings = ReadingValidator.warnings(span, counted, date, value);
        List<MeterReadingRecord> pending = readings.findByMeterIdAndStatusOrderByTakenOn(meter.getId(), Status.PENDING)
                .stream().filter(other -> other.getTakenOn().equals(date)).toList();
        MeterReadingRecord same = pending.stream().filter(other -> other.getValue().equals(value)).findFirst()
                .orElse(null);
        if (same != null) {
            // the same reading again (a second tap on "send"): nothing changes and nothing is audited
            return new ReadingSaved(ReadingDtoMapper.toDto(same, null, false), List.of(),
                    ReadingDtoMapper.toDto(warnings));
        }
        MeterReadingRecord created = MeterReadingRecord.submitted(meter.getTenantId(), meter.getId(), date, value,
                actor.memberId(), clock.instant());
        replace(pending, created);
        audit.publishReading(actor, "READING_SUBMITTED", meter, List.of(
                new FieldChange("takenOn", null, date.toString()),
                new FieldChange("value", pending.isEmpty() ? null : pending.getFirst().getValue().toString(),
                        value.toString())));
        return new ReadingSaved(ReadingDtoMapper.toDto(created, null, false),
                pending.stream().map(MeterReadingRecord::getId).toList(), ReadingDtoMapper.toDto(warnings));
    }

    /**
     * A pending reading starts to count. It is checked again against the counted readings as they are now and the
     * {@link ReadingUsage} ports are asked about the span around its date; a counted reading of the same date is
     * superseded by it (flushed out of {@code APPROVED} first, then linked).
     */
    @Transactional
    ReadingSaved approve(UUID readingId, AccessProfile actor) {
        MeterRecord meter = lockMeterOf(readingId);
        MeterReadingRecord reading = readings.findById(readingId).orElseThrow(ApiProblemException::notFound);
        if (!reading.isPending()) {
            throw ReadingProblem.notPending();
        }
        LocalDate date = reading.getTakenOn();
        MeterValue value = reading.getValue();
        MeterSpan span = MeterSpan.of(meter);
        List<MeterReadingRecord> approved = approved(meter);
        MeterReadingRecord sameDate = onDate(approved, date);
        List<Reading> counted = readingsOf(approved.stream().filter(other -> other != sameDate).toList());
        ReadingValidator.check(span, counted, LocalDate.now(clock), date, value).ifPresent(violation -> {
            throw ReadingProblem.of(violation);
        });
        Neighbours neighbours = ReadingValidator.neighbours(span, counted, date);
        ensureNotUsed(meter, neighbours.spanFrom(date), neighbours.spanTo(date));

        Instant now = clock.instant();
        if (sameDate != null) {
            sameDate.supersede(now);
            entityManager.flush();
        }
        reading.approve(actor.memberId(), now);
        entityManager.flush();
        if (sameDate != null) {
            sameDate.supersededBy(reading.getId());
            entityManager.flush();
        }
        audit.publishReading(actor, "READING_APPROVED", meter, List.of(
                new FieldChange("takenOn", sameDate == null ? null : date.toString(), date.toString()),
                new FieldChange("value", sameDate == null ? null : sameDate.getValue().toString(), value.toString())));
        pointEvents.changed(meter, date, Cause.READING_APPROVED);
        Interval interval = ReadingValidator.intervalEndingAt(span, counted, date, value).orElse(null);
        return new ReadingSaved(ReadingDtoMapper.toDto(reading, interval, actor.can(Permission.READINGS_ENTER)),
                sameDate == null ? List.of() : List.of(sameDate.getId()),
                ReadingDtoMapper.toDto(ReadingValidator.warnings(span, counted, date, value)));
    }

    /**
     * A pending reading is not approved, with the reason the member sees. The reason is user content: it goes to the
     * reading and the audit entry, never to a log.
     */
    @Transactional
    MeterReading reject(UUID readingId, ReadingRejection input, AccessProfile actor) {
        String reason = input.getReason() == null ? "" : input.getReason().strip();
        if (reason.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("reason", ProblemCodes.REQUIRED)));
        }
        MeterRecord meter = lockMeterOf(readingId);
        MeterReadingRecord reading = readings.findById(readingId).orElseThrow(ApiProblemException::notFound);
        if (!reading.isPending()) {
            throw ReadingProblem.notPending();
        }
        reading.reject(reason, actor.memberId(), clock.instant());
        entityManager.flush();
        audit.publishReading(actor, "READING_REJECTED", meter, List.of(
                new FieldChange("takenOn", null, reading.getTakenOn().toString()),
                new FieldChange("value", null, reading.getValue().toString()),
                new FieldChange("reason", null, reason)));
        return ReadingDtoMapper.toDto(reading, null, false);
    }

    // ---- one row

    /** Enters one reading; runs in the row's transaction. */
    private Saved enterOne(UUID meterId, @Nullable LocalDate takenOn, String text, AccessProfile actor) {
        MeterValue value = parse(text);
        MeterRecord meter = meters.findByIdForUpdate(meterId).orElseThrow(() -> new ApiProblemException(
                HttpStatus.NOT_FOUND, ProblemCodes.NOT_FOUND, "The meter was not found.",
                List.of(new FieldProblem("meterId", ProblemCodes.NOT_FOUND))));
        LocalDate date = takenOn != null ? takenOn : LocalDate.now(clock);
        MeterSpan span = MeterSpan.of(meter);
        List<MeterReadingRecord> approved = approved(meter);
        MeterReadingRecord sameDate = onDate(approved, date);
        List<Reading> counted = readingsOf(approved.stream().filter(other -> other != sameDate).toList());
        if (sameDate != null && sameDate.getValue().equals(value)) {
            // the same reading again (a second tap on "save"): nothing changes and nothing is audited
            return new Saved(sameDate, ReadingValidator.intervalEndingAt(span, counted, date, value).orElse(null),
                    List.of(), ReadingValidator.warnings(span, counted, date, value));
        }
        ReadingValidator.check(span, counted, LocalDate.now(clock), date, value).ifPresent(violation -> {
            throw ReadingProblem.of(violation);
        });
        Neighbours neighbours = ReadingValidator.neighbours(span, counted, date);
        ensureNotUsed(meter, neighbours.spanFrom(date), neighbours.spanTo(date));

        List<MeterReadingRecord> replaced = sameDate == null ? List.of() : List.of(sameDate);
        MeterReadingRecord created = MeterReadingRecord.entered(meter.getTenantId(), meter.getId(), date, value,
                actor.memberId(), clock.instant());
        replace(replaced, created);
        if (sameDate == null) {
            audit.publishReading(actor, "READING_ENTERED", meter, List.of(
                    new FieldChange("takenOn", null, date.toString()),
                    new FieldChange("value", null, value.toString())));
        } else {
            audit.publishReading(actor, "READING_REPLACED", meter, List.of(
                    new FieldChange("takenOn", date.toString(), date.toString()),
                    new FieldChange("value", sameDate.getValue().toString(), value.toString())));
        }
        pointEvents.changed(meter, date, Cause.READING_ENTERED);
        return new Saved(created, ReadingValidator.intervalEndingAt(span, counted, date, value).orElse(null),
                replaced.stream().map(MeterReadingRecord::getId).toList(),
                ReadingValidator.warnings(span, counted, date, value));
    }

    // ---- helpers

    /**
     * Supersedes the old rows and inserts the new one, in the order the partial unique index of approved readings needs:
     * the old rows leave {@code APPROVED} (flushed), the new row is inserted (flushed), then the old rows are linked to
     * it ({@code superseded_by_id} references an existing row).
     */
    private void replace(List<MeterReadingRecord> replaced, MeterReadingRecord created) {
        Instant now = clock.instant();
        if (!replaced.isEmpty()) {
            replaced.forEach(old -> old.supersede(now));
            entityManager.flush();
        }
        entityManager.persist(created);
        entityManager.flush();
        if (!replaced.isEmpty()) {
            replaced.forEach(old -> old.supersededBy(created.getId()));
            entityManager.flush();
        }
    }

    /**
     * Locks the meter of the reading {@code FOR UPDATE}; the reading is read after the lock, so it is the committed
     * state. 404 for an unknown reading or one of another cooperative (RLS).
     */
    private MeterRecord lockMeterOf(UUID readingId) {
        UUID meterId = readings.findMeterIdOf(readingId).orElseThrow(ApiProblemException::notFound);
        return meters.findByIdForUpdate(meterId).orElseThrow(ApiProblemException::notFound);
    }

    private List<MeterReadingRecord> approved(MeterRecord meter) {
        return readings.findByMeterIdAndStatusOrderByTakenOn(meter.getId(), Status.APPROVED);
    }

    /** 409 {@code READING_IN_USE} when any port says a charge uses the readings of the meter in the span. */
    private void ensureNotUsed(MeterRecord meter, LocalDate from, LocalDate to) {
        if (usages.stream().anyMatch(usage -> usage.isUsed(meter.getTenantId(), meter.getId(), from, to))) {
            throw ReadingProblem.inUse();
        }
    }

    private static @Nullable MeterReadingRecord onDate(List<MeterReadingRecord> readings, LocalDate date) {
        return readings.stream().filter(reading -> reading.getTakenOn().equals(date)).findFirst().orElse(null);
    }

    private static List<Reading> readingsOf(List<MeterReadingRecord> records) {
        return records.stream().map(MeterReadingRecord::toReading).toList();
    }

    private static LocalDate earlier(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    /** The value of a reading; the contract pattern already checked its form, so a failure here is a 400 too. */
    private static MeterValue parse(String text) {
        try {
            return MeterValue.parse(text);
        } catch (InvalidMeterValueException e) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("value", ProblemCodes.METER_VALUE_INVALID)));
        }
    }

    private static ReadingSaved saved(Saved saved) {
        return new ReadingSaved(ReadingDtoMapper.toDto(saved.reading(), saved.interval(), true), saved.superseded(),
                ReadingDtoMapper.toDto(saved.warnings()));
    }

    /** A refused row: the problem code, its field and, for a broken reading rule, the parameters of the message. */
    private static ReadingRowError rowError(ApiProblemException refused) {
        ReadingRowError error = new ReadingRowError(refused.code());
        if (refused instanceof ReadingProblem problem) {
            error.setField(problem.field());
            error.setParams(Map.copyOf(problem.params()));
        } else if (!refused.errors().isEmpty()) {
            error.setField(refused.errors().getFirst().field());
        }
        return error;
    }
}
