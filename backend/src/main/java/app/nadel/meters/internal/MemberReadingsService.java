package app.nadel.meters.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.meters.MemberReadings;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterValue;
import app.nadel.meters.ReadingStatus;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import nadel.contract.model.ReadingSaved;
import nadel.contract.model.ReadingSubmission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Serves the public {@link MemberReadings} (feature 017 §2.5) over the account's own code: the owner rule of
 * {@link MeterAccess#ownsNow} and the submission of {@link ReadingCommands#submit}, so the bot and the account accept
 * and refuse the same readings with the same codes. A submission runs in a new transaction: a refusal rolls back only
 * that and becomes a {@link Rejected} answer. Nothing it reads or receives is logged.
 */
@Service
class MemberReadingsService implements MemberReadings {

    private final MeterRepository meters;
    private final MeterReadingRepository readings;
    private final MeterAccess access;
    private final ReadingCommands commands;
    private final Clock clock;
    private final TransactionTemplate ownTransaction;

    MemberReadingsService(MeterRepository meters, MeterReadingRepository readings, MeterAccess access,
            ReadingCommands commands, Clock clock, PlatformTransactionManager transactions) {
        this.meters = meters;
        this.readings = readings;
        this.access = access;
        this.commands = commands;
        this.clock = clock;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberMeter> metersOf(UUID plotId, AccessProfile member) {
        Objects.requireNonNull(plotId, "plotId");
        if (member.platformOperator() || member.tenantId() == null || !access.ownsNow(member, plotId)) {
            return List.of();
        }
        return meters.findActiveOfPlots(List.of(plotId)).stream()
                .filter(meter -> meter.getKind() == MeterKind.PLOT && plotId.equals(meter.getPlotId()))
                .sorted(Comparator.comparing(MeterRecord::getResource).thenComparing(MeterRecord::getSerial)
                        .thenComparing(MeterRecord::getId))
                .map(meter -> new MemberMeter(meter.getId(), meter.getResource(), meter.getSerial()))
                .toList();
    }

    @Override
    public SubmitResult submit(UUID plotId, UUID meterId, String value, AccessProfile member) {
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(meterId, "meterId");
        ReadingSubmission submission = new ReadingSubmission(meterId, Objects.requireNonNull(value, "value"));
        // today in the cooperative's zone: the application clock is Europe/Kyiv (ClockConfiguration)
        submission.setTakenOn(LocalDate.now(clock));
        try {
            return Objects.requireNonNull(ownTransaction.execute(status -> {
                ReadingSaved saved = commands.submit(plotId, submission, member);
                String serial = meters.findById(meterId).map(MeterRecord::getSerial).orElseThrow();
                return new Accepted(saved.getReading().getId(), MeterValue.parse(saved.getReading().getValue()),
                        serial);
            }));
        } catch (ApiProblemException refused) {
            return new Rejected(List.of(problem(refused)));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReadingDecisionView> decision(UUID readingId) {
        return readings.findById(Objects.requireNonNull(readingId, "readingId")).flatMap(reading ->
                meters.findById(reading.getMeterId()).map(meter -> new ReadingDecisionView(reading.getValue(),
                        meter.getSerial(), ReadingStatus.valueOf(reading.getStatus().name()),
                        reading.getRejectReason())));
    }

    /**
     * The problem the account would show: a broken reading rule with its parameters; a malformed value as its field
     * code ({@code METER_VALUE_INVALID}, the code the account's form shows); a meter that is not the plot's as
     * {@code NOT_FOUND}; anything else by its code.
     */
    static Problem problem(ApiProblemException refused) {
        if (refused instanceof ReadingProblem rule) {
            return new Problem(rule.code(), rule.params());
        }
        if (ProblemCodes.VALIDATION_FAILED.equals(refused.code()) && !refused.errors().isEmpty()) {
            return new Problem(refused.errors().getFirst().code(), Map.of());
        }
        return new Problem(refused.code(), Map.of());
    }
}
