package app.nadel.ledger.internal;

import app.nadel.audit.ActorNames;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import app.nadel.ledger.internal.BillingCalendar.MonthState;
import app.nadel.ledger.internal.ExpectedCharges.Charge;
import app.nadel.ledger.internal.ExpectedCharges.Expected;
import app.nadel.ledger.internal.ExpectedCharges.OfCommonConsumerRun;
import app.nadel.ledger.internal.ExpectedCharges.OfInterval;
import app.nadel.ledger.internal.ExpectedCharges.OfLossesRun;
import app.nadel.ledger.internal.ExpectedCharges.OfTariff;
import app.nadel.ledger.internal.NotChargedItemRecord.Kind;
import app.nadel.ledger.internal.NotChargedItemRecord.Reason;
import app.nadel.ledger.internal.NotChargedItemRecord.Status;
import app.nadel.ledger.internal.ReadingChargePlanning.MeterInterval;
import app.nadel.ledger.internal.RecalculationLineRecord.Action;
import app.nadel.ledger.internal.RecalculationPlanner.Line;
import app.nadel.ledger.internal.RecalculationPlanner.Plan;
import app.nadel.ledger.internal.RecalculationPlanner.QueueOutcome;
import app.nadel.ledger.internal.RecalculationPlanner.RunAction;
import app.nadel.ledger.internal.RecalculationPlanner.RunOutcome;
import app.nadel.meters.ConsumptionCalculator.Reading;
import app.nadel.meters.MeterCatalog;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterNetwork;
import app.nadel.meters.MeterNetwork.MeterPoints;
import app.nadel.meters.MeterNetwork.Network;
import app.nadel.meters.MeterResource;
import app.nadel.meters.ReadingChanges;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import app.nadel.tariffs.TariffBook;
import app.nadel.tariffs.TariffBook.ChargeTypeEntry;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
import app.nadel.tariffs.TariffVersionChanges;
import app.nadel.tariffs.VersionTerms;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import nadel.contract.model.NotChargedItem;
import nadel.contract.model.NotChargedReason;
import nadel.contract.model.Recalculation;
import nadel.contract.model.RecalculationAction;
import nadel.contract.model.RecalculationCauseKind;
import nadel.contract.model.RecalculationCauseType;
import nadel.contract.model.RecalculationChangeReason;
import nadel.contract.model.RecalculationLine;
import nadel.contract.model.ReadingCorrection;
import nadel.contract.model.RecalculationManualScope;
import nadel.contract.model.RecalculationReadingChange;
import nadel.contract.model.RecalculationRequest;
import nadel.contract.model.RecalculationTariffVersionChange;
import nadel.contract.model.TariffVersionCreate;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Previews and books recalculations ({@code POST /recalculations?preview=}, feature 012 §2.1, §2.2, §2.4, §2.6).
 * It handles the cause {@code MANUAL}, the tariff version causes {@code TARIFF_VERSION_ADD}, {@code _UPDATE} and
 * {@code _DELETE} ({@link #tariffVersion}: the change applied through {@link TariffVersionChanges} in the same
 * transaction, the scope from the tariff and the version's validity) and the reading causes {@code READING_ENTER},
 * {@code _CORRECT} and {@code _DELETE} ({@link #reading}: the change applied through {@link ReadingChanges}, the scope
 * the meter's intervals around the changed dates and the runs that read the meter on the days the change reaches) over
 * the charges of recurring tariffs (the month run's charges), the charges of plot meter intervals (consumption,
 * fixed-percentage losses, electricity and water) and the charges of proportional losses runs and common consumer runs.
 * <p>
 * Validation before anything is read: {@code cause.manual} is required; {@code to} before {@code from} is
 * {@code INVALID}, {@code to} after the current month in Europe/Kyiv {@code DATE_IN_FUTURE} (400, field
 * {@code cause.manual.to}); more than 24 months is 409 {@code RECALCULATION_RANGE_TOO_LONG}; a booking needs a reason
 * (trimmed, 1 to 500 characters: {@code REQUIRED}, {@code TOO_LONG}).
 * <p>
 * The preview and the booking run the same code in one transaction ({@link TransactionTemplate}); the preview's is the
 * outermost transaction and is marked rollback-only, so it writes nothing. The locks, in this order: the cooperative's
 * charging lock ({@link ChargingLock}), the plot meters of the plots of the scope in id order (each {@code FOR SHARE}
 * with its points and its advisory lock, {@link IntervalCharging#lockPoints}; only the resources of the chosen charge
 * types), when runs are in the scope the advisory lock of the losses runs, then that of the common consumer runs
 * ({@link LossesRuns#lockRuns}, {@link CommonConsumerRuns#lockRuns}) and every electricity meter {@code FOR SHARE}
 * ({@link MeterNetwork#lockSharedNetwork}, the order of a run's booking), the queue lock, the charge types and tariffs
 * {@code FOR SHARE} ({@link TariffChargeWriter#lockTariffs}), every billing month {@code FOR SHARE} (no month closes
 * under the plan). Then the scope: the months {@code from} to
 * {@code to}; the charge types chosen (unknown 400 {@code NOT_FOUND}, inactive or archived 400 {@code INVALID}) or every
 * active one; the plots chosen ({@code UNKNOWN_PLOT}, {@code PLOT_ARCHIVED}, {@code PLOT_COMMON_LAND}) or every
 * chargeable one. The expected charges come from the month run's plan loop ({@link MonthRun#expected}) and from the
 * listener's planning of the meters' intervals whose closing reading is dated in the months
 * ({@link IntervalCharging#expected}; a charge type that is not active, water before it is switched on, is not in the
 * scope and charges nothing), and from the booked runs whose range ends in the months: the losses runs when electricity
 * is in the scope, the common consumer runs when {@code COMMON_CONSUMERS} is; with plots chosen, the runs with a charge
 * of one of them, each as a whole. A run is planned again over its range and the nodes or consumers it charged, the run
 * itself ignored so it does not overlap itself ({@link LossesRuns#planAgain}, {@link CommonConsumerRuns#planAgain}).
 * The booked chains are the tariff charges of the scope (by the month of their source key), the interval charges of
 * the locked meters (by their debt date, the date of the reading that closes the interval) and the charges of the
 * runs, with their reversals and adjustments; {@link RecalculationPlanner} compares them; the digest is
 * {@link RecalculationDigest}.
 * <p>
 * A booking whose digest differs from the one of the plan now is 409 {@code PREVIEW_OUTDATED}; a plan without lines is
 * 409 {@code NOTHING_TO_RECALCULATE} (a preview of it answers with no lines, "No changes"). A plan without lines books
 * nothing at all, not even its queue outcomes: the queue is left to the month run, the listener and "Повторити", and
 * the preview of such a plan lists no "not charged" items. Otherwise, in this order: the {@code recalculation} row; the
 * runs of open months whose lines differ, each reversed as a whole (source {@code RECALCULATION}, the run and its nodes
 * or lines marked reversed) and, unless nothing is left to charge, booked again as a new run of the same range and
 * nodes or consumers with the lines' expected charges ({@link LossesRuns#book}, {@link CommonConsumerRuns#book}: its
 * value uses, its "not charged" items as 011 writes them, no run audit entry); the
 * reversals (source {@code RECALCULATION}, the reason, in the reversed charge's month); the new {@code REGULAR} charges
 * with their parts, and for an interval the two points it reads ({@code meter_value_use}) (the base key, or
 * {@code base#r{recalculation}} when a charge has the base key; in the month 011 would book them in); the adjustments
 * ({@link ChargeRecord#adjustment}, the difference, the original's service period and {@code accrued_on}, booked in the
 * earliest open month, with the parts of the expected charge and, for an interval, its two points, for a run line the
 * values of the nodes or the consumer it shares in); a plot new to a run of a closed month gets a charge of the original
 * run (key {@code LOSSES_RUN:{run}:{plot}} or {@code COMMON_CONSUMER_RUN:{run}:{consumer}:{plot}}); the queue (an item
 * that cannot be charged opened or refreshed, an interval item with the first point of its interval now, a charged one
 * {@code RESOLVED}, one with nothing left {@code OBSOLETE}); the {@code recalculation_line} rows; one
 * {@code AuditBatch} with {@code RECALCULATION_BOOKED}; {@code ChargesBooked} for the plots of the lines (and of the
 * runs reversed as a whole). The reason is user content: never logged.
 * <p>
 * Money paid on the charges a booking reverses or lowers is taken back in the same transaction (feature 015 §2.3,
 * technical considerations 2.4): before it writes anything, the booking takes the plot ledger locks
 * ({@link PlotLedgerLock}) of the plots of the lines and of every plot a run reversed as a whole charged, in one sorted
 * call; after the reversals, new charges and adjustments, {@link AllocationReleaser#settleCharges} releases everything
 * on a reversed charge ({@code CHARGE_REVERSED}) and the excess of a lowered one from its latest allocations
 * ({@code CHARGE_ADJUSTED}), by the actor. The months are already held {@code FOR SHARE} by the plan (taken before the
 * lines are known); every path that holds a plot ledger lock takes a month only {@code FOR SHARE}, which never waits on
 * this booking's shared locks, so the order cannot deadlock. A preview takes no plot ledger lock.
 */
@Service
class RecalculationService {

    /** The longest manual range, in months. */
    static final int MAX_MONTHS = 24;

    private static final int MAX_REASON = 500;
    private static final int CHUNK = 1000;
    private static final String WATER = "WATER";
    private static final String ELECTRICITY = "ELECTRICITY";
    private static final String COMMON_CONSUMERS = CommonConsumerPlanning.COMMON_CONSUMERS;
    private static final Set<RecalculationCauseType> TARIFF_CAUSES = EnumSet.of(
            RecalculationCauseType.TARIFF_VERSION_ADD, RecalculationCauseType.TARIFF_VERSION_UPDATE,
            RecalculationCauseType.TARIFF_VERSION_DELETE);
    private static final String VERSION_FIELDS = "cause.tariffVersion.version.";
    private static final Set<RecalculationCauseType> READING_CAUSES = EnumSet.of(
            RecalculationCauseType.READING_ENTER, RecalculationCauseType.READING_CORRECT,
            RecalculationCauseType.READING_DELETE);
    private static final String READING_FIELDS = "cause.reading.";

    private final TransactionTemplate transaction;
    private final ChargingLock chargingLock;
    private final TariffChargeWriter writer;
    private final MonthRun monthRun;
    private final IntervalCharging intervals;
    private final MeterCatalog meters;
    private final MeterNetwork network;
    private final LossesRuns lossesRuns;
    private final CommonConsumerRuns commonConsumerRuns;
    private final LossesRunRepository lossesRunRecords;
    private final CommonConsumerRunRepository commonConsumerRunRecords;
    private final PlotCatalog plots;
    private final ChargeTypeCatalog chargeTypes;
    private final ChargeRepository charges;
    private final BillingMonthRepository months;
    private final BillingMonths billingMonths;
    private final NotChargedItemRepository items;
    private final NotChargedViews itemViews;
    private final LedgerAuditPublisher audit;
    private final ActorNames actorNames;
    private final TariffVersionChanges tariffVersions;
    private final ReadingChanges readingChanges;
    private final TariffBook tariffBook;
    private final PlotLedgerLock plotLocks;
    private final AllocationReleaser releaser;
    private final EntityManager entityManager;
    private final Clock clock;

    RecalculationService(PlatformTransactionManager transactionManager, ChargingLock chargingLock,
            TariffChargeWriter writer, MonthRun monthRun, IntervalCharging intervals, MeterCatalog meters,
            MeterNetwork network, LossesRuns lossesRuns, CommonConsumerRuns commonConsumerRuns,
            LossesRunRepository lossesRunRecords, CommonConsumerRunRepository commonConsumerRunRecords,
            PlotCatalog plots, ChargeTypeCatalog chargeTypes,
            ChargeRepository charges, BillingMonthRepository months, BillingMonths billingMonths,
            NotChargedItemRepository items, NotChargedViews itemViews, LedgerAuditPublisher audit,
            ActorNames actorNames, TariffVersionChanges tariffVersions, ReadingChanges readingChanges,
            TariffBook tariffBook, PlotLedgerLock plotLocks, AllocationReleaser releaser, EntityManager entityManager,
            Clock clock) {
        this.transaction = new TransactionTemplate(transactionManager);
        this.chargingLock = chargingLock;
        this.writer = writer;
        this.monthRun = monthRun;
        this.intervals = intervals;
        this.meters = meters;
        this.network = network;
        this.lossesRuns = lossesRuns;
        this.commonConsumerRuns = commonConsumerRuns;
        this.lossesRunRecords = lossesRunRecords;
        this.commonConsumerRunRecords = commonConsumerRunRecords;
        this.plots = plots;
        this.chargeTypes = chargeTypes;
        this.charges = charges;
        this.months = months;
        this.billingMonths = billingMonths;
        this.items = items;
        this.itemViews = itemViews;
        this.audit = audit;
        this.actorNames = actorNames;
        this.tariffVersions = tariffVersions;
        this.readingChanges = readingChanges;
        this.tariffBook = tariffBook;
        this.plotLocks = plotLocks;
        this.releaser = releaser;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** A validated manual request: the months, the chosen charge types and plots (empty: all), the reason. */
    private record Manual(YearMonth from, YearMonth to, List<UUID> chargeTypeIds, List<UUID> plotIds,
            @Nullable String reason, @Nullable String digest) {

        List<YearMonth> months() {
            List<YearMonth> all = new ArrayList<>();
            for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
                all.add(month);
            }
            return all;
        }
    }

    /** A validated tariff version cause: the change to book with the recalculation, the reason, the digest. */
    private record TariffCause(RecalculationCauseType type, UUID tariffId, @Nullable UUID versionId,
            @Nullable TariffVersionCreate version, @Nullable String reason, @Nullable String digest) {
    }

    /** A validated reading cause: the change to book with the recalculation, the reason, the digest. */
    private record ReadingCause(RecalculationCauseType type, @Nullable UUID meterId, @Nullable UUID readingId,
            @Nullable ReadingCorrection reading, @Nullable String reason, @Nullable String digest) {
    }

    /**
     * The scope of a reading cause.
     *
     * @param meterId the meter whose reading changed
     * @param plotId  the plot of a plot meter while it is chargeable (its intervals are compared); null otherwise
     * @param from    the first day the change reaches (runs that read the meter on a day from here to {@code to})
     * @param to      the last day the change reaches
     * @param months  the months of the days the change can alter, as shown and stored
     */
    private record ReadingScope(UUID meterId, @Nullable UUID plotId, LocalDate from, LocalDate to,
            List<YearMonth> months) {
    }

    /**
     * What a recalculation covers, from the manual request or from the change it books.
     *
     * @param months        the billing months of the tariff charges (empty: nothing to compare)
     * @param window        the days whose interval charges (by the reading that closes them) and runs (by their end)
     *                      are compared; null when the months are empty
     * @param chargeTypeIds the charge types chosen (empty: every charging one)
     * @param plotIds       the plots chosen (empty: every chargeable one, or the plots of the tariff)
     * @param tariffId      the tariff of a tariff version cause: its plots are the scope; null for a manual one
     * @param digestCause   the normalised cause of the digest (no generated ids)
     * @param storedScope   the scope as stored and shown (ids and codes)
     * @param reading       the scope of a reading cause (its meter's intervals and runs instead of plots and the runs
     *                      ending in the window); null otherwise
     */
    private record Target(RecalculationCauseType causeType, List<YearMonth> months,
            IntervalCharging.@Nullable Window window, List<UUID> chargeTypeIds, List<UUID> plotIds,
            @Nullable UUID tariffId, RecalculationDigest.Cause digestCause, Map<String, Object> storedScope,
            @Nullable ReadingScope reading) {

        static Target of(Manual manual) {
            return new Target(RecalculationCauseType.MANUAL, manual.months(),
                    new IntervalCharging.Window(manual.from().atDay(1), manual.to().atEndOfMonth()),
                    manual.chargeTypeIds(), manual.plotIds(), null, RecalculationDigest.Cause.manual(),
                    RecalculationService.storedScope(RecalculationCauseType.MANUAL, manual.months(),
                            manual.chargeTypeIds(), manual.plotIds()), null);
        }

        boolean manual() {
            return causeType == RecalculationCauseType.MANUAL;
        }

        RecalculationRecord.Cause kind() {
            if (manual()) {
                return RecalculationRecord.Cause.MANUAL;
            }
            return reading != null ? RecalculationRecord.Cause.READING : RecalculationRecord.Cause.TARIFF_VERSION;
        }

        /** The months shown and audited: those of the tariff charges, or those a reading change can alter. */
        List<YearMonth> shownMonths() {
            return reading != null ? reading.months() : months;
        }
    }

    /** The scope as resolved under the locks. */
    private record Scope(List<YearMonth> months, List<ChargeTypeEntry> types, List<PlotFacts> plots) {
    }

    /** The plots of the scope, read before their meters are locked, and the problems of the chosen ones. */
    private record PlotScope(List<PlotFacts> plots, List<FieldProblem> problems) {
    }

    /** A meter interval of the scope, for its "not charged" item ({@code INTERVAL:{meter}:{toKey}}). */
    private record ScopedInterval(UUID plotId, MeterInterval interval) {
    }

    /**
     * The booked runs of the scope (their range ends in the months), each with its regular charges and the network of
     * its range, locked before the queue.
     */
    private record RunScope(List<LossesRunRecord> losses, List<CommonConsumerRunRecord> commonConsumers,
            Map<UUID, List<ChargeRecord>> charges, Map<UUID, Network> networks) {

        static RunScope none() {
            return new RunScope(List.of(), List.of(), Map.of(), Map.of());
        }
    }

    /**
     * What the plan is made of, and what the booking writes from.
     *
     * @param plotById            the plots of the scope and of the run lines (their numbers are shown)
     * @param lossesPlans         the losses runs of the scope planned again, by run id
     * @param commonConsumerPlans the common consumer runs of the scope planned again, by run id
     * @param runMonths           the billing month of each run of the scope, by run id
     */
    private record Planned(Plan plan, String digest, Snapshot snapshot, Map<UUID, ChargeRecord> originals,
            Map<ChargeIdentity, Expected> expected, Map<String, NotChargedItemRecord> openItems,
            Map<String, ScopedInterval> intervals, Map<UUID, PlotFacts> plotById,
            Map<UUID, LossesRuns.Planned> lossesPlans, Map<UUID, CommonConsumerPlanning.Plan> commonConsumerPlans,
            Map<UUID, YearMonth> runMonths) {
    }

    /**
     * @throws ApiProblemException 400 for an invalid request, 403 without the permission of a cause-driven change, 409
     *                             {@code RECALCULATION_RANGE_TOO_LONG}, {@code PREVIEW_OUTDATED},
     *                             {@code NOTHING_TO_RECALCULATE} or a refusal of the change
     */
    Recalculation run(RecalculationRequest request, boolean preview, AccessProfile actor) {
        RecalculationCauseType type = request.getCause().getType();
        if (type == RecalculationCauseType.MANUAL) {
            return manual(request, preview, actor);
        }
        if (TARIFF_CAUSES.contains(type)) {
            return tariffVersion(request, preview, actor);
        }
        if (READING_CAUSES.contains(type)) {
            return reading(request, preview, actor);
        }
        throw invalid("cause.type", ProblemCodes.UNSUPPORTED);
    }

    private Recalculation manual(RecalculationRequest request, boolean preview, AccessProfile actor) {
        Manual manual = validate(request, preview);
        Target target = Target.of(manual);
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        return transaction.execute(status -> {
            if (preview) {
                // the outermost transaction: rolling it back never throws UnexpectedRollbackException
                status.setRollbackOnly();
            }
            chargingLock.lock(tenantId);
            Planned planned = plan(tenantId, target);
            if (preview) {
                return result(target, planned, true, false, null, Map.of(), null);
            }
            checkDigest(manual.digest(), planned);
            if (planned.plan().isEmpty()) {
                throw conflict(ProblemCodes.NOTHING_TO_RECALCULATE, "Nothing to recalculate.");
            }
            return book(tenantId, target, planned, actor, Objects.requireNonNull(manual.reason()), null, null);
        });
    }

    /**
     * A tariff version added, corrected or deleted together with its recalculation (feature 012 §2.4): needs
     * {@code TARIFFS_MANAGE} besides {@code RECALCULATION_RUN} (403). Under the charging lock, the change is applied
     * through {@link TariffVersionChanges} (its locks and validation, field problems under
     * {@code cause.tariffVersion.version.}), then the plan is made over the plots of the tariff and the months the old
     * and new validity of the version touch ({@link #tariffTarget}); a preview rolls both back. A booking: 409
     * {@code PREVIEW_OUTDATED} when the digest differs; a plan without lines keeps the change and books no
     * recalculation (no reason needed, {@code booked} false, the change's audit entry alone); otherwise a reason is
     * required and the recalculation is booked as a manual one, with cause {@code TARIFF_VERSION}, the version as
     * {@code cause_ref_id}, and the change's audit entry (with {@code recalculationId}) in the batch of
     * {@code RECALCULATION_BOOKED}.
     */
    private Recalculation tariffVersion(RecalculationRequest request, boolean preview, AccessProfile actor) {
        if (!actor.can(Permission.TARIFFS_MANAGE)) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, ProblemCodes.PERMISSION_DENIED,
                    "You do not have permission to perform this action.");
        }
        TariffCause cause = validateTariff(request, preview);
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        return transaction.execute(status -> {
            if (preview) {
                status.setRollbackOnly();
            }
            chargingLock.lock(tenantId);
            TariffVersionChanges.Change change = apply(cause, actor);
            Target target = tariffTarget(cause, change, preview);
            Planned planned = plan(tenantId, target);
            if (preview) {
                return result(target, planned, true, false, null, Map.of(), null);
            }
            checkDigest(cause.digest(), planned);
            if (planned.plan().isEmpty()) {
                if (change.audit() != null) {
                    audit.publishBatch(tenantId, List.of(change.audit()));
                }
                return result(target, planned, false, true, null, Map.of(), null);
            }
            if (cause.reason() == null || cause.reason().isEmpty()) {
                throw invalid("reason", ProblemCodes.REQUIRED);
            }
            return book(tenantId, target, planned, actor, cause.reason(), change.versionId(), change.audit());
        });
    }

    /**
     * A reading entered, corrected or deleted together with its recalculation (feature 012 §2.5): needs
     * {@code READINGS_ENTER} besides {@code RECALCULATION_RUN} (403). Under the charging lock, the change is applied
     * through {@link ReadingChanges} (its locks and rules, no use check, no point event; field problems under
     * {@code cause.reading.}), then the plan is made over the meter's intervals closed by a point the change can alter
     * and the runs that read the meter on the days it reaches ({@link #readingTarget}); a preview rolls both back. A
     * booking: 409 {@code PREVIEW_OUTDATED} when the digest differs; a plan without lines keeps the change and books no
     * recalculation (no reason needed, {@code booked} false, the change's audit entry alone), and since no point event
     * follows the change, its queue outcomes are applied as the listener would (an interval that cannot be charged
     * queued, an item whose interval is gone {@code OBSOLETE}); otherwise a reason is required and the recalculation is
     * booked with cause {@code READING}, the meter as {@code cause_ref_id}, and the change's audit entry (with
     * {@code recalculationId}) in the batch of {@code RECALCULATION_BOOKED}.
     */
    private Recalculation reading(RecalculationRequest request, boolean preview, AccessProfile actor) {
        if (!actor.can(Permission.READINGS_ENTER)) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, ProblemCodes.PERMISSION_DENIED,
                    "You do not have permission to perform this action.");
        }
        ReadingCause cause = validateReading(request, preview);
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        return transaction.execute(status -> {
            if (preview) {
                status.setRollbackOnly();
            }
            chargingLock.lock(tenantId);
            ReadingChanges.Change change = apply(cause, actor);
            Target target = readingTarget(cause, change, preview);
            Planned planned = plan(tenantId, target);
            if (preview) {
                return result(target, planned, true, false, null, Map.of(), null);
            }
            checkDigest(cause.digest(), planned);
            if (planned.plan().isEmpty()) {
                applyQueue(tenantId, planned, clock.instant());
                if (change.audit() != null) {
                    audit.publishBatch(tenantId, List.of(change.audit()));
                }
                return result(target, planned, false, true, null, Map.of(), null);
            }
            if (cause.reason() == null || cause.reason().isEmpty()) {
                throw invalid("reason", ProblemCodes.REQUIRED);
            }
            return book(tenantId, target, planned, actor, cause.reason(), change.meterId(), change.audit());
        });
    }

    private static void checkDigest(@Nullable String digest, Planned planned) {
        if (digest != null && !digest.equals(planned.digest())) {
            throw conflict(ProblemCodes.PREVIEW_OUTDATED,
                    "The charges changed since the preview. Preview again before booking.");
        }
    }

    // ---- validation

    private Manual validate(RecalculationRequest request, boolean preview) {
        if (request.getCause().getType() != RecalculationCauseType.MANUAL) {
            throw invalid("cause.type", ProblemCodes.UNSUPPORTED);
        }
        RecalculationManualScope scope = request.getCause().getManual();
        if (scope == null) {
            throw invalid("cause.manual", ProblemCodes.REQUIRED);
        }
        YearMonth from = month(scope.getFrom(), "cause.manual.from");
        YearMonth to = month(scope.getTo(), "cause.manual.to");
        if (to.isBefore(from)) {
            throw invalid("cause.manual.to", ProblemCodes.INVALID);
        }
        if (to.isAfter(YearMonth.now(clock))) {
            throw invalid("cause.manual.to", ProblemCodes.DATE_IN_FUTURE);
        }
        if (from.plusMonths(MAX_MONTHS).isBefore(to.plusMonths(1))) {
            throw conflict(ProblemCodes.RECALCULATION_RANGE_TOO_LONG,
                    "A manual recalculation spans at most " + MAX_MONTHS + " months.");
        }
        String reason = reason(request, preview, true);
        return new Manual(from, to, distinct(scope.getChargeTypeIds()), distinct(scope.getPlotIds()), reason,
                request.getDigest());
    }

    /**
     * The trimmed reason; a booking's is at most 500 characters ({@code TOO_LONG}) and, when {@code required}, not
     * blank ({@code REQUIRED}). A preview needs none.
     */
    private static @Nullable String reason(RecalculationRequest request, boolean preview, boolean required) {
        String reason = request.getReason() == null ? null : request.getReason().strip();
        if (!preview) {
            if (required && (reason == null || reason.isEmpty())) {
                throw invalid("reason", ProblemCodes.REQUIRED);
            }
            if (reason != null && reason.length() > MAX_REASON) {
                throw invalid("reason", ProblemCodes.TOO_LONG);
            }
        }
        return reason;
    }

    /**
     * {@code cause.tariffVersion} is required, its {@code version} for an add or an update, its {@code versionId} for
     * an update or a deletion ({@code REQUIRED}); a booking's reason is checked for its length here and required only
     * when the plan has lines.
     */
    private static TariffCause validateTariff(RecalculationRequest request, boolean preview) {
        RecalculationCauseType type = request.getCause().getType();
        RecalculationTariffVersionChange change = request.getCause().getTariffVersion();
        if (change == null) {
            throw invalid("cause.tariffVersion", ProblemCodes.REQUIRED);
        }
        List<FieldProblem> problems = new ArrayList<>();
        if (type != RecalculationCauseType.TARIFF_VERSION_ADD && change.getVersionId() == null) {
            problems.add(new FieldProblem("cause.tariffVersion.versionId", ProblemCodes.REQUIRED));
        }
        if (type != RecalculationCauseType.TARIFF_VERSION_DELETE && change.getVersion() == null) {
            problems.add(new FieldProblem("cause.tariffVersion.version", ProblemCodes.REQUIRED));
        }
        throwIfAny(problems);
        String reason = reason(request, preview, false);
        return new TariffCause(type, change.getTariffId(),
                type == RecalculationCauseType.TARIFF_VERSION_ADD ? null : change.getVersionId(),
                type == RecalculationCauseType.TARIFF_VERSION_DELETE ? null : change.getVersion(),
                reason == null || reason.isEmpty() ? null : reason, request.getDigest());
    }

    /**
     * Applies the change in the running transaction. A version that is not one of the tariff {@code tariffId} (not
     * found, deleted, of another tariff or cooperative) is 404 before anything changes; the change's field problems
     * are reported under {@code cause.tariffVersion.version.}.
     */
    private TariffVersionChanges.Change apply(TariffCause cause, AccessProfile actor) {
        if (cause.versionId() != null && tariffBook.snapshot().tariff(cause.tariffId()).stream()
                .flatMap(tariff -> tariff.versions().stream()).noneMatch(v -> v.id().equals(cause.versionId()))) {
            throw ApiProblemException.notFound();
        }
        try {
            return switch (cause.type()) {
                case TARIFF_VERSION_ADD -> tariffVersions.add(cause.tariffId(),
                        Objects.requireNonNull(cause.version()), actor);
                case TARIFF_VERSION_UPDATE -> tariffVersions.update(Objects.requireNonNull(cause.versionId()),
                        Objects.requireNonNull(cause.version()), actor);
                case TARIFF_VERSION_DELETE -> tariffVersions.delete(Objects.requireNonNull(cause.versionId()), actor);
                default -> throw invalid("cause.type", ProblemCodes.UNSUPPORTED);
            };
        } catch (ApiProblemException e) {
            if (e.errors().isEmpty()) {
                throw e;
            }
            throw new ApiProblemException(e.status(), e.code(), e.getMessage(), e.errors().stream()
                    .map(problem -> new FieldProblem(VERSION_FIELDS + problem.field(), problem.code())).toList());
        }
    }

    /**
     * The scope of a tariff version cause: the charge type of the tariff and its plots (every chargeable plot the
     * tariff covers, {@link #plotScope}); the months the old and new validity of the version touch, from the
     * cooperative's first billing month (nothing was charged before it) to the current month at the latest; and, for
     * the interval charges and runs, the days from the first of those months to the end of the current month (an
     * interval or a run that covers a changed day may close after the version's validity). Nothing when the change
     * touches no such month (a version that starts in the future, or before anything was charged and ends before it).
     */
    private Target tariffTarget(TariffCause cause, TariffVersionChanges.Change change, boolean preview) {
        YearMonth current = YearMonth.now(clock);
        List<BillingMonthRepository.MonthStatus> states = months.statesInOrder();
        List<YearMonth> touched = new ArrayList<>();
        IntervalCharging.Window window = null;
        if (!states.isEmpty()) {
            YearMonth first = YearMonth.from(states.getFirst().month());
            YearMonth from = YearMonth.from(change.from());
            YearMonth to = change.to() == null ? current : YearMonth.from(change.to());
            from = from.isBefore(first) ? first : from;
            to = to.isAfter(current) ? current : to;
            for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
                touched.add(month);
            }
            if (!touched.isEmpty()) {
                window = new IntervalCharging.Window(touched.getFirst().atDay(1), current.atEndOfMonth());
            }
        }
        List<UUID> types = List.of(change.chargeTypeId());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("tariffId", change.tariffId().toString());
        if (cause.versionId() != null) {
            values.put("versionId", cause.versionId().toString());
        }
        VersionTerms terms = change.terms();
        if (terms != null) {
            values.put("validFrom", terms.validFrom().toString());
            values.put("basis", terms.basis().name());
            values.put("frequency", terms.frequency().name());
            values.put("rate", terms.rate().value().setScale(4).toPlainString());
            values.put("dueDate", String.valueOf(terms.dueDate()));
            values.put("note", String.valueOf(change.note()));
        }
        Map<String, Object> stored = storedScope(cause.type(), touched, types, List.of());
        stored.put("tariffId", change.tariffId().toString());
        // a new version's id is generated anew by the booking: the preview does not show it
        if (!(preview && cause.type() == RecalculationCauseType.TARIFF_VERSION_ADD)) {
            stored.put("tariffVersionId", change.versionId().toString());
        }
        return new Target(cause.type(), touched, window, types, List.of(), change.tariffId(),
                new RecalculationDigest.Cause(cause.type().getValue(), values), stored, null);
    }

    /**
     * {@code cause.reading} is required; its {@code meterId} for an entry, its {@code readingId} for a correction or a
     * deletion, its {@code reading} (date and value) for an entry or a correction ({@code REQUIRED}); a booking's
     * reason is checked for its length here and required only when the plan has lines.
     */
    private static ReadingCause validateReading(RecalculationRequest request, boolean preview) {
        RecalculationCauseType type = request.getCause().getType();
        RecalculationReadingChange change = request.getCause().getReading();
        if (change == null) {
            throw invalid("cause.reading", ProblemCodes.REQUIRED);
        }
        List<FieldProblem> problems = new ArrayList<>();
        if (type == RecalculationCauseType.READING_ENTER && change.getMeterId() == null) {
            problems.add(new FieldProblem("cause.reading.meterId", ProblemCodes.REQUIRED));
        }
        if (type != RecalculationCauseType.READING_ENTER && change.getReadingId() == null) {
            problems.add(new FieldProblem("cause.reading.readingId", ProblemCodes.REQUIRED));
        }
        if (type != RecalculationCauseType.READING_DELETE && change.getReading() == null) {
            problems.add(new FieldProblem("cause.reading.reading", ProblemCodes.REQUIRED));
        }
        throwIfAny(problems);
        String reason = reason(request, preview, false);
        return new ReadingCause(type, type == RecalculationCauseType.READING_ENTER ? change.getMeterId() : null,
                type == RecalculationCauseType.READING_ENTER ? null : change.getReadingId(),
                type == RecalculationCauseType.READING_DELETE ? null : change.getReading(),
                reason == null || reason.isEmpty() ? null : reason, request.getDigest());
    }

    /**
     * Applies the reading change in the running transaction. Its field problems are reported under
     * {@code cause.reading.} ({@code meterId}) and {@code cause.reading.reading.} ({@code takenOn}, {@code value}).
     */
    private ReadingChanges.Change apply(ReadingCause cause, AccessProfile actor) {
        try {
            return switch (cause.type()) {
                case READING_ENTER -> readingChanges.enter(Objects.requireNonNull(cause.meterId()),
                        Objects.requireNonNull(cause.reading()).getTakenOn(), cause.reading().getValue(), actor);
                case READING_CORRECT -> readingChanges.correct(Objects.requireNonNull(cause.readingId()),
                        Objects.requireNonNull(cause.reading()).getTakenOn(), cause.reading().getValue(), actor);
                case READING_DELETE -> readingChanges.delete(Objects.requireNonNull(cause.readingId()), actor);
                default -> throw invalid("cause.type", ProblemCodes.UNSUPPORTED);
            };
        } catch (ApiProblemException e) {
            if (e.errors().isEmpty()) {
                throw e;
            }
            throw new ApiProblemException(e.status(), e.code(), e.getMessage(), e.errors().stream()
                    .map(problem -> new FieldProblem("meterId".equals(problem.field())
                            ? READING_FIELDS + problem.field()
                            : READING_FIELDS + "reading." + problem.field(), problem.code()))
                    .toList());
        }
    }

    /**
     * The scope of a reading cause: the meter's intervals closed by a point from the first day the change reaches to
     * the last point whose interval it alters ({@link ReadingChanges.Change#through}) when it is a plot meter of a
     * chargeable plot, and the booked runs that read the meter on a day the change reaches; the charge type of the
     * meter's resource, and for electricity also {@code COMMON_CONSUMERS} (the runs). No tariff charge is compared. The
     * digest's cause is the meter and the old and new date and value (never a reading's id: a correction's new row has
     * a new id on every call); the stored scope names the meter, the reading corrected or deleted, or the reading
     * entered (on booking only), and the plot.
     */
    private Target readingTarget(ReadingCause cause, ReadingChanges.Change change, boolean preview) {
        UUID plotId = null;
        if (change.kind() == MeterKind.PLOT && change.plotId() != null) {
            PlotFacts plot = plots.findAll(List.of(change.plotId())).get(change.plotId());
            if (plot != null && !plot.archived() && !plot.commonLand()) {
                plotId = plot.id();
            }
        }
        Set<String> codes = change.resource() == MeterResource.WATER ? Set.of(WATER)
                : Set.of(ELECTRICITY, COMMON_CONSUMERS);
        List<UUID> types = chargeTypes.all().stream()
                .filter(type -> type.code() != null && codes.contains(type.code()))
                .sorted(Comparator.comparingInt(ChargeTypeFacts::position).thenComparing(ChargeTypeFacts::id))
                .map(ChargeTypeFacts::id).toList();
        List<YearMonth> shown = new ArrayList<>();
        for (YearMonth month = YearMonth.from(change.from()); !month.isAfter(YearMonth.from(change.through()));
                month = month.plusMonths(1)) {
            shown.add(month);
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("meterId", change.meterId().toString());
        putReading(values, "before", change.before());
        putReading(values, "after", change.after());
        Map<String, Object> stored = storedScope(cause.type(), shown, types,
                plotId == null ? List.of() : List.of(plotId));
        stored.put("meterId", change.meterId().toString());
        UUID readingId = cause.type() == RecalculationCauseType.READING_ENTER
                ? (preview ? null : change.readingId())
                : cause.readingId();
        if (readingId != null) {
            stored.put("readingId", readingId.toString());
        }
        return new Target(cause.type(), List.of(), new IntervalCharging.Window(change.from(), change.through()),
                types, List.of(), null, new RecalculationDigest.Cause(cause.type().getValue(), values), stored,
                new ReadingScope(change.meterId(), plotId, change.from(), change.to(), shown));
    }

    private static void putReading(Map<String, String> values, String prefix, @Nullable Reading reading) {
        if (reading != null) {
            values.put(prefix + "TakenOn", reading.takenOn().toString());
            values.put(prefix + "Value", reading.value().toString());
        }
    }

    private static YearMonth month(String value, String field) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid(field, ProblemCodes.INVALID);
        }
    }

    private static List<UUID> distinct(@Nullable List<UUID> ids) {
        return ids == null ? List.of() : List.copyOf(new LinkedHashSet<>(ids));
    }

    // ---- the plan (preview and booking)

    /** The plan of the target; the caller holds the charging lock (and has applied the change, if any). */
    private Planned plan(UUID tenantId, Target target) {
        // the plots first (read, not locked), then their meters in id order: the meter locks come before the queue lock
        PlotScope plotScope = plotScope(target);
        IntervalCharging.Window window = target.window();
        ReadingScope reading = target.reading();
        List<MeterPoints> lockedMeters;
        if (window == null) {
            lockedMeters = List.of();
        } else if (reading != null) {
            // a reading cause: the meter itself, when its plot is in the scope
            lockedMeters = plotScope.plots().isEmpty() ? List.of()
                    : intervals.lockPoints(tenantId, reading.meterId()).stream().toList();
        } else {
            lockedMeters = lockMeters(tenantId, plotScope.plots(), target.chargeTypeIds());
        }
        // the runs: their locks and the shared network after the plot meters, before the queue lock
        RunScope runScope = window == null ? RunScope.none() : lockRuns(tenantId, target, window);
        writer.lockQueue(tenantId);
        Snapshot snapshot = writer.lockTariffs();
        months.lockAllForShare();

        Scope scope = scope(target, snapshot, plotScope);
        Set<UUID> typeIds = scope.types().stream().map(ChargeTypeEntry::id).collect(Collectors.toSet());
        Map<UUID, PlotFacts> plotById = scope.plots().stream()
                .collect(Collectors.toMap(PlotFacts::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Set<YearMonth> inScope = new HashSet<>(scope.months());

        // the expected charges: the month run's, then the intervals of the plots' meters closed in the window
        List<Expected> expected = new ArrayList<>(scope.months().isEmpty() ? List.of()
                : monthRun.expected(snapshot, scope.plots(), scope.types(), scope.months()));
        Map<String, ScopedInterval> scopedIntervals = new LinkedHashMap<>();
        Set<UUID> meterIds = new HashSet<>();
        for (MeterPoints points : lockedMeters) {
            UUID plotId = points.plotId();
            if (window == null || plotId == null || !plotById.containsKey(plotId)) {
                continue;
            }
            meterIds.add(points.meterId());
            intervals.expected(points, snapshot, window).stream()
                    .filter(entry -> typeIds.contains(entry.chargeTypeId())).forEach(expected::add);
            for (MeterInterval interval : IntervalCharging.intervals(points)) {
                if (window.contains(interval)) {
                    scopedIntervals.put(NotChargedItemRecord.intervalKey(points.meterId(), interval.to().key()),
                            new ScopedInterval(plotId, interval));
                }
            }
        }
        Map<UUID, YearMonth> monthOf = new HashMap<>();
        List<MonthState> states = new ArrayList<>();
        for (BillingMonthRepository.MonthStatus state : months.statesInOrder()) {
            YearMonth month = YearMonth.from(state.month());
            monthOf.put(state.id(), month);
            states.add(new MonthState(month, state.status() == BillingMonthRecord.Status.CLOSED));
        }

        // the booked chains: the tariff charges of the scope (by their key's month) and the interval charges of the
        // locked meters (by the date of the reading that closes the interval), their reversals and adjustments
        Map<UUID, ChargeRecord> originals = new LinkedHashMap<>();
        if (!plotById.isEmpty() && !typeIds.isEmpty()) {
            for (List<UUID> chunk : chunks(plotById.keySet())) {
                for (ChargeRecord charge : charges.findOfPlotsAndTypes(chunk, typeIds, ChargeKind.REGULAR,
                        ChargeSource.TARIFF)) {
                    keyMonth(charge.getSourceKey()).filter(inScope::contains)
                            .ifPresent(month -> originals.put(charge.getId(), charge));
                }
                if (meterIds.isEmpty()) {
                    continue;
                }
                for (ChargeSource source : List.of(ChargeSource.CONSUMPTION, ChargeSource.LOSSES)) {
                    for (ChargeRecord charge : charges.findOfPlotsAndTypes(chunk, typeIds, ChargeKind.REGULAR,
                            source)) {
                        // a losses charge of a run has its run: runs are recalculated as runs
                        if (charge.getRunId() == null && charge.getMeterId() != null
                                && meterIds.contains(charge.getMeterId()) && window != null
                                && window.contains(charge.getAccruedOn())) {
                            originals.put(charge.getId(), charge);
                        }
                    }
                }
            }
        }
        // the runs of the scope's charge types: planned again over their range, the run itself ignored
        Map<UUID, LossesRuns.Planned> lossesPlans = new LinkedHashMap<>();
        Map<UUID, CommonConsumerPlanning.Plan> commonConsumerPlans = new LinkedHashMap<>();
        // a run follows its own billing month, whatever month a charge of it is booked in
        Map<UUID, YearMonth> runMonths = new HashMap<>();
        runScope.losses().forEach(run -> runMonths.put(run.getId(),
                Objects.requireNonNull(monthOf.get(run.getBillingMonthId()))));
        runScope.commonConsumers().forEach(run -> runMonths.put(run.getId(),
                Objects.requireNonNull(monthOf.get(run.getBillingMonthId()))));
        UUID electricity = typeOf(snapshot, ELECTRICITY);
        if (electricity != null && typeIds.contains(electricity)) {
            for (LossesRunRecord run : runScope.losses()) {
                LossesRuns.Planned again = lossesRuns.planAgain(run, runScope.networks().get(run.getId()), snapshot);
                lossesPlans.put(run.getId(), again);
                expected.addAll(ExpectedCharges.ofLossesRun(again.plan(), run.getId(), electricity));
                runScope.charges().get(run.getId()).forEach(charge -> originals.put(charge.getId(), charge));
            }
        }
        UUID commonConsumers = typeOf(snapshot, COMMON_CONSUMERS);
        if (commonConsumers != null && typeIds.contains(commonConsumers)) {
            for (CommonConsumerRunRecord run : runScope.commonConsumers()) {
                CommonConsumerPlanning.Plan again = commonConsumerRuns.planAgain(run,
                        runScope.networks().get(run.getId()), snapshot);
                commonConsumerPlans.put(run.getId(), again);
                expected.addAll(ExpectedCharges.ofCommonConsumerRun(again, run.getId(), commonConsumers));
                runScope.charges().get(run.getId()).forEach(charge -> originals.put(charge.getId(), charge));
            }
        }
        List<BookedCharge> booked = new ArrayList<>();
        originals.values().forEach(charge -> booked.add(booked(charge, monthOf)));
        for (List<UUID> chunk : chunks(originals.keySet())) {
            charges.findReversalsAndAdjustmentsOf(chunk).forEach(charge -> booked.add(booked(charge, monthOf)));
        }

        Map<String, NotChargedItemRecord> openItems = new LinkedHashMap<>();
        for (NotChargedItemRecord item : items.findByKindAndStatus(Kind.TARIFF_MONTH, Status.OPEN)) {
            if (plotById.containsKey(item.getPlotId()) && typeIds.contains(item.getChargeTypeId())
                    && inScope.contains(item.getMonth())) {
                openItems.put(item.getItemKey(), item);
            }
        }
        for (NotChargedItemRecord item : items.findByKindAndStatus(Kind.INTERVAL, Status.OPEN)) {
            if (item.getMeterId() != null && meterIds.contains(item.getMeterId())
                    && plotById.containsKey(item.getPlotId()) && typeIds.contains(item.getChargeTypeId())
                    && item.getToDate() != null && window != null && window.contains(item.getToDate())) {
                openItems.put(item.getItemKey(), item);
            }
        }
        Set<UUID> water = snapshot.chargeTypes().stream().filter(type -> WATER.equals(type.code()))
                .map(ChargeTypeEntry::id).collect(Collectors.toSet());

        Plan plan = RecalculationPlanner.plan(new RecalculationPlanner.Input(booked, expected, states,
                LocalDate.now(clock), openItems.keySet(), target.kind(), water, runMonths));
        String digest = RecalculationDigest.of(target.digestCause(),
                new RecalculationDigest.Scope(scope.months(), target.chargeTypeIds(), target.plotIds()), plan);
        Map<ChargeIdentity, Expected> expectedById = new HashMap<>();
        expected.forEach(entry -> expectedById.put(entry.identity(), entry));
        // a run line may be of a plot outside the chosen ones: its number is shown too
        Map<UUID, PlotFacts> shown = new LinkedHashMap<>(plotById);
        Set<UUID> missing = plan.lines().stream().map(Line::plotId).filter(id -> !shown.containsKey(id))
                .collect(Collectors.toSet());
        if (!missing.isEmpty()) {
            shown.putAll(plots.findAll(missing));
        }
        return new Planned(plan, digest, snapshot, originals, expectedById, openItems, scopedIntervals, shown,
                lossesPlans, commonConsumerPlans, runMonths);
    }

    private static @Nullable UUID typeOf(Snapshot snapshot, String code) {
        return snapshot.chargeTypes().stream().filter(type -> code.equals(type.code())).map(ChargeTypeEntry::id)
                .findFirst().orElse(null);
    }

    /**
     * The booked runs whose range ends in the window, of the chosen charge types (losses runs are electricity charges,
     * common consumer runs {@code COMMON_CONSUMERS}; every type when none is chosen); with plots chosen, only the runs
     * with a charge of one of them (a run is recalculated as a whole: its other plots are shown too). For a reading
     * cause, instead of the runs ending in the window, the booked runs that read its meter on a day the change reaches
     * ({@code meter_value_use}, as {@link LedgerReadingUsage} asks). When there are any: the advisory lock of the
     * losses runs, then that of the common consumer runs (each after the charging lock, which is taken already), then
     * every electricity meter {@code FOR SHARE} with the network of each run's range
     * ({@link MeterNetwork#lockSharedNetwork}), the order of a run's booking.
     */
    private RunScope lockRuns(UUID tenantId, Target target, IntervalCharging.Window window) {
        Set<String> codes = new HashSet<>();
        if (target.chargeTypeIds().isEmpty()) {
            codes.addAll(List.of(ELECTRICITY, COMMON_CONSUMERS));
        } else {
            Map<UUID, ChargeTypeFacts> typeById = chargeTypes.all().stream()
                    .collect(Collectors.toMap(ChargeTypeFacts::id, Function.identity()));
            target.chargeTypeIds().stream().map(typeById::get).filter(Objects::nonNull).map(ChargeTypeFacts::code)
                    .filter(Objects::nonNull).forEach(codes::add);
        }
        LocalDate from = window.from();
        LocalDate to = window.to();
        Map<UUID, List<ChargeRecord>> runCharges = new HashMap<>();
        Set<UUID> chosenPlots = new HashSet<>(target.plotIds());
        Predicate<UUID> touches = runId -> {
            List<ChargeRecord> charges = this.charges.findByRunIdAndKind(runId, ChargeKind.REGULAR);
            runCharges.put(runId, charges);
            return chosenPlots.isEmpty() || charges.stream().anyMatch(c -> chosenPlots.contains(c.getPlotId()));
        };
        ReadingScope reading = target.reading();
        List<LossesRunRecord> losses = !codes.contains(ELECTRICITY) ? List.of()
                : (reading == null ? lossesRunRecords.findBookedEndingBetween(from, to)
                        : lossesRunRecords.findBookedReadingMeter(reading.meterId(), reading.from(), reading.to()))
                        .stream().filter(run -> touches.test(run.getId())).toList();
        List<CommonConsumerRunRecord> consumers = !codes.contains(COMMON_CONSUMERS) ? List.of()
                : (reading == null ? commonConsumerRunRecords.findBookedEndingBetween(from, to)
                        : commonConsumerRunRecords.findBookedReadingMeter(reading.meterId(), reading.from(),
                                reading.to()))
                        .stream().filter(run -> touches.test(run.getId())).toList();
        if (losses.isEmpty() && consumers.isEmpty()) {
            return RunScope.none();
        }
        if (!losses.isEmpty()) {
            lossesRuns.lockRuns(tenantId);
        }
        if (!consumers.isEmpty()) {
            commonConsumerRuns.lockRuns(tenantId);
        }
        Map<String, Network> byRange = new HashMap<>();
        Map<UUID, Network> networks = new HashMap<>();
        for (LossesRunRecord run : losses) {
            networks.put(run.getId(), byRange.computeIfAbsent(run.label(),
                    k -> network.lockSharedNetwork(run.getFromDate(), run.getToDate())));
        }
        for (CommonConsumerRunRecord run : consumers) {
            networks.put(run.getId(), byRange.computeIfAbsent(run.label(),
                    k -> network.lockSharedNetwork(run.getFromDate(), run.getToDate())));
        }
        return new RunScope(losses, consumers, runCharges, networks);
    }

    /**
     * Locks the plot meters of the plots whose resource the chosen charge types charge (both resources when every
     * type is chosen), in id order: each {@code FOR SHARE} with its points and its advisory lock
     * ({@link IntervalCharging#lockPoints}), as the listener of reading changes does.
     */
    private List<MeterPoints> lockMeters(UUID tenantId, List<PlotFacts> scopePlots, List<UUID> chosenTypes) {
        Set<MeterResource> resources = EnumSet.noneOf(MeterResource.class);
        if (chosenTypes.isEmpty()) {
            resources.addAll(List.of(MeterResource.ELECTRICITY, MeterResource.WATER));
        } else {
            Map<UUID, ChargeTypeFacts> typeById = chargeTypes.all().stream()
                    .collect(Collectors.toMap(ChargeTypeFacts::id, Function.identity()));
            for (UUID id : chosenTypes) {
                ChargeTypeFacts type = typeById.get(id);
                if (type != null && ELECTRICITY.equals(type.code())) {
                    resources.add(MeterResource.ELECTRICITY);
                } else if (type != null && WATER.equals(type.code())) {
                    resources.add(MeterResource.WATER);
                }
            }
        }
        if (resources.isEmpty() || scopePlots.isEmpty()) {
            return List.of();
        }
        Set<UUID> meterIds = new TreeSet<>();
        for (List<UUID> chunk : chunks(scopePlots.stream().map(PlotFacts::id).toList())) {
            meterIds.addAll(meters.plotMeterIds(chunk, resources));
        }
        List<MeterPoints> locked = new ArrayList<>();
        for (UUID meterId : meterIds) {
            intervals.lockPoints(tenantId, meterId).ifPresent(locked::add);
        }
        return locked;
    }

    /**
     * The charge types of the scope checked under the tariff locks, then the plots resolved before. The charge type of
     * a tariff version cause is in the scope only while it charges (active, not archived): an inactive type charges
     * nothing, and its booked charges are left to a manual recalculation.
     */
    private Scope scope(Target target, Snapshot snapshot, PlotScope plotScope) {
        List<ChargeTypeEntry> types = new ArrayList<>();
        if (target.chargeTypeIds().isEmpty()) {
            snapshot.chargeTypes().stream().filter(ChargeTypeEntry::charges).forEach(types::add);
        } else if (!target.manual()) {
            target.chargeTypeIds().stream().map(snapshot::chargeType).flatMap(Optional::stream)
                    .filter(ChargeTypeEntry::charges).forEach(types::add);
        } else {
            List<FieldProblem> problems = new ArrayList<>();
            for (int i = 0; i < target.chargeTypeIds().size(); i++) {
                Optional<ChargeTypeEntry> type = snapshot.chargeType(target.chargeTypeIds().get(i));
                String field = "cause.manual.chargeTypeIds[" + i + "]";
                if (type.isEmpty()) {
                    problems.add(new FieldProblem(field, ProblemCodes.NOT_FOUND));
                } else if (!type.get().charges()) {
                    problems.add(new FieldProblem(field, ProblemCodes.INVALID));
                } else {
                    types.add(type.get());
                }
            }
            throwIfAny(problems);
        }
        throwIfAny(plotScope.problems());
        return new Scope(target.months(), types, plotScope.plots());
    }

    /**
     * The plots chosen ({@code UNKNOWN_PLOT}, {@code PLOT_ARCHIVED}, {@code PLOT_COMMON_LAND}) or every chargeable one;
     * for a tariff version cause the chargeable plots the tariff covers (its scope, read with the change applied; the
     * tariff row is locked by the change), none when no month is touched; for a reading cause the plot of its meter
     * when it is chargeable ({@link #readingTarget}).
     */
    private PlotScope plotScope(Target target) {
        ReadingScope reading = target.reading();
        if (reading != null) {
            return new PlotScope(reading.plotId() == null ? List.of()
                    : List.copyOf(plots.findAll(List.of(reading.plotId())).values()), List.of());
        }
        if (target.tariffId() != null) {
            if (target.months().isEmpty()) {
                return new PlotScope(List.of(), List.of());
            }
            TariffEntry tariff = tariffBook.snapshot().tariff(target.tariffId()).orElseThrow();
            return new PlotScope(plots.findChargeable().stream().filter(plot -> tariff.covers(plot.id())).toList(),
                    List.of());
        }
        if (target.plotIds().isEmpty()) {
            return new PlotScope(plots.findChargeable(), List.of());
        }
        Map<UUID, PlotFacts> found = plots.findAll(target.plotIds());
        List<FieldProblem> problems = new ArrayList<>();
        List<PlotFacts> chosen = new ArrayList<>();
        for (int i = 0; i < target.plotIds().size(); i++) {
            PlotFacts plot = found.get(target.plotIds().get(i));
            String field = "cause.manual.plotIds[" + i + "]";
            if (plot == null) {
                problems.add(new FieldProblem(field, ProblemCodes.UNKNOWN_PLOT));
            } else if (plot.archived()) {
                problems.add(new FieldProblem(field, ProblemCodes.PLOT_ARCHIVED));
            } else if (plot.commonLand()) {
                problems.add(new FieldProblem(field, ProblemCodes.PLOT_COMMON_LAND));
            } else {
                chosen.add(plot);
            }
        }
        return new PlotScope(chosen, problems);
    }

    /** The month of a month-run key {@code TARIFF:{tariff}:{plot}:{YYYY-MM}} (any {@code #…} suffix stripped). */
    private static Optional<YearMonth> keyMonth(@Nullable String sourceKey) {
        if (sourceKey == null) {
            return Optional.empty();
        }
        String[] parts = ChargeIdentity.baseKey(sourceKey).split(":");
        if (parts.length != 4 || !"TARIFF".equals(parts[0])) {
            return Optional.empty(); // not a charge of the month run: it has no identity and is left alone
        }
        try {
            return Optional.of(YearMonth.parse(parts[3]));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static BookedCharge booked(ChargeRecord charge, Map<UUID, YearMonth> monthOf) {
        return BookedCharge.of(charge, Objects.requireNonNull(monthOf.get(charge.getBillingMonthId())));
    }

    private static List<List<UUID>> chunks(Collection<UUID> ids) {
        List<UUID> all = new ArrayList<>(ids);
        List<List<UUID>> chunks = new ArrayList<>();
        for (int from = 0; from < all.size(); from += CHUNK) {
            chunks.add(all.subList(from, Math.min(all.size(), from + CHUNK)));
        }
        return chunks;
    }

    // ---- the booking

    /**
     * Books the plan (it has lines) with the reason; {@code causeRefId} is the version or reading of a cause-driven
     * recalculation and {@code change} the audit entry of its change, published in the batch of
     * {@code RECALCULATION_BOOKED} with the recalculation's id.
     */
    private Recalculation book(UUID tenantId, Target target, Planned planned, AccessProfile actor, String reason,
            @Nullable UUID causeRefId, @Nullable AuditEvent change) {
        Instant now = clock.instant();
        UUID createdBy = actor.memberId();
        UUID recalculationId = UUID.randomUUID();
        List<Line> lines = ordered(planned.plan().lines(), planned.plotById());
        Set<UUID> plotIds = lines.stream().map(Line::plotId).collect(Collectors.toCollection(LinkedHashSet::new));

        // 0. the plot ledger locks of the plots of the lines and of every plot a run reversed as a whole charged, in
        // one sorted call before the first month is booked in: the reversals and adjustments below may take back money
        // paid on their charges (feature 015 §2.3), so a payment of these plots waits for this booking
        Set<UUID> touched = new LinkedHashSet<>(plotIds);
        List<UUID> wholeRuns = planned.plan().runs().stream().filter(run -> run.action() != RunAction.PER_LINE)
                .map(RunOutcome::runId).toList();
        if (!wholeRuns.isEmpty()) {
            touched.addAll(charges.plotIdsOfRuns(wholeRuns));
        }
        plotLocks.lockAll(tenantId, touched);

        entityManager.persist(RecalculationRecord.booked(recalculationId, tenantId, target.kind(), causeRefId,
                target.storedScope(), reason, planned.plan().total(), lines.size(), plotIds.size(), planned.digest(),
                createdBy, now));
        entityManager.flush();

        Map<YearMonth, BillingMonthRecord> booking = new HashMap<>();
        Function<LocalDate, BillingMonthRecord> monthFor = date -> booking.computeIfAbsent(YearMonth.from(date),
                m -> billingMonths.openForBooking(tenantId, date));

        // 1. the runs of open months whose lines differ: reversed as a whole, and booked again for the same range
        Map<UUID, RunAction> runActions = new HashMap<>();
        planned.plan().runs().forEach(run -> runActions.put(run.runId(), run.action()));
        Predicate<Line> wholeRun = line -> line.runId() != null
                && runActions.getOrDefault(line.runId(), RunAction.PER_LINE) != RunAction.PER_LINE;
        Map<Line, UUID> reversals = new HashMap<>();
        Map<Line, UUID> created = new HashMap<>();
        for (RunOutcome run : planned.plan().runs()) {
            if (run.action() != RunAction.PER_LINE) {
                rebookRun(tenantId, planned, run, lines, monthFor, recalculationId, reason, createdBy, now, reversals,
                        created);
            }
        }

        // 2. the reversals, in the reversed charge's month
        for (Line line : lines) {
            if (wholeRun.test(line)) {
                continue;
            }
            if (line.action() == Action.REVERSE_AND_RECHARGE || line.action() == Action.REVERSE) {
                ChargeRecord original = original(planned, line);
                BillingMonthRecord month = monthFor.apply(Objects.requireNonNull(line.original()).billingMonth()
                        .atDay(1));
                ChargeRecord reversal = ChargeRecord.recalculationReversalOf(original, month.getId(), recalculationId,
                        reason, ChargeViews.plainCalculation(original.getAmount().negate()), createdBy, now);
                entityManager.persist(reversal);
                reversals.put(line, reversal.getId());
            }
        }
        entityManager.flush();

        // 3. the new regular charges, under the base key unless a charge has it already
        Map<Line, Charge> recharges = new LinkedHashMap<>();
        for (Line line : lines) {
            if (line.recharge() != null && !wholeRun.test(line)) {
                recharges.put(line, line.recharge());
            }
        }
        Set<String> existing = recharges.isEmpty() ? Set.of() : new HashSet<>(charges.existingSourceKeys(
                recharges.keySet().stream().map(RecalculationService::sourceKey).toList()));
        for (Map.Entry<Line, Charge> entry : recharges.entrySet()) {
            Line line = entry.getKey();
            Charge charge = entry.getValue();
            BillingMonthRecord month = bookedIn(monthFor.apply(charge.accruedOn()), line);
            String base = sourceKey(line);
            String key = existing.contains(base) ? base + "#r" + recalculationId : base;
            ChargeRecord record = switch (charge.planned()) {
                case OfTariff tariff -> writer.bookRecalculated(tenantId, tariff.charge(), month.getId(), key,
                        recalculationId, createdBy, now);
                case OfInterval interval -> intervals.bookRecalculated(tenantId, interval.charge(), month.getId(),
                        key, recalculationId, createdBy, now);
                // a plot new to a run of a closed month: a charge of the original run
                case OfLossesRun run -> lossesRuns.bookLine(tenantId, Objects.requireNonNull(line.runId()),
                        run.plan(), run.charge(), line.chargeTypeId(), month.getId(), key, recalculationId, createdBy,
                        now);
                case OfCommonConsumerRun run -> commonConsumerRuns.bookLine(tenantId,
                        Objects.requireNonNull(line.runId()), run.plan(), run.charge(), line.chargeTypeId(),
                        month.getId(), key, recalculationId, createdBy, now);
            };
            created.put(line, record.getId());
        }

        // 4. the adjustments, in the earliest open month, with the original's service period and debt date
        Map<Line, UUID> adjustments = new HashMap<>();
        for (Line line : lines) {
            if (line.action() == Action.ADJUST) {
                ChargeRecord original = original(planned, line);
                BillingMonthRecord month = bookedIn(monthFor.apply(original.getAccruedOn()), line);
                ChargeRecord adjustment = ChargeRecord.adjustment(original, month.getId(), recalculationId,
                        line.difference(), reason, ChargeViews.plainCalculation(line.difference()), createdBy, now);
                entityManager.persist(adjustment);
                entityManager.flush();
                Expected expected = planned.expected().get(line.identity());
                if (expected != null && expected.result() instanceof Charge charge) {
                    // the parts of the expected charge; an interval's adjustment also reads the interval's points
                    switch (charge.planned()) {
                        case OfTariff tariff -> writer.writeParts(tenantId, adjustment.getId(), tariff.charge());
                        case OfInterval interval -> intervals.writePartsAndUse(tenantId, adjustment.getId(),
                                interval.charge());
                        case OfLossesRun run -> lossesRuns.writePartsAndUse(tenantId, adjustment.getId(),
                                run.plan(), run.charge());
                        case OfCommonConsumerRun run -> commonConsumerRuns.writePartsAndUse(tenantId,
                                adjustment.getId(), run.plan(), run.charge());
                    }
                }
                adjustments.put(line, adjustment.getId());
            }
        }
        entityManager.flush();

        // 4a. the money the reversals and lowering adjustments no longer leave on their charges is taken back, by the
        // actor: everything on a reversed charge, the excess of a lowered one from its latest allocations
        releaser.settleCharges(tenantId, touched, createdBy);

        // 5. the queue
        List<NotChargedItem> notCharged = applyQueue(tenantId, planned, now);

        // 6. the lines
        Map<Line, RecalculationLineRecord.Created> createdByLine = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            RecalculationLineRecord.Created ids = new RecalculationLineRecord.Created(reversals.get(line),
                    created.get(line), adjustments.get(line));
            createdByLine.put(line, ids);
            entityManager.persist(RecalculationLineRecord.of(tenantId, recalculationId, line.plotId(),
                    line.chargeTypeId(), line.serviceFrom(), line.serviceTo(),
                    line.original() == null ? null : line.original().id(), line.booked(), line.adjustments(),
                    line.expected(), line.difference(), line.action(),
                    line.reasons().stream().map(RecalculationChangeReason::getValue).toList(), line.runId(), ids,
                    i + 1));
        }
        entityManager.flush();

        // 7. the audit entries (the change that caused it first) and the plot statuses
        List<AuditEvent> entries = new ArrayList<>();
        if (change != null) {
            entries.add(withRecalculation(change, recalculationId));
        }
        entries.add(audit.recalculationEvent(actor, recalculationId,
                target.shownMonths().getFirst() + "/" + target.shownMonths().getLast(),
                auditChanges(target, planned, lines, reason)));
        audit.publishBatch(tenantId, entries);
        // every plot whose charges changed: the status is refreshed and released money is placed again
        writer.publishBooked(tenantId, touched);

        Recalculation result = result(target, planned, false, !target.manual(), recalculationId, createdByLine,
                notCharged);
        result.setReason(reason);
        result.setCreatedAt(now.atOffset(ZoneOffset.UTC));
        if (createdBy != null) {
            result.setCreatedByName(actorNames.displayNames(List.of(createdBy)).get(createdBy));
        }
        return result;
    }

    /** The audit entry of the change with the field {@code recalculationId}: the recalculation booked with it. */
    private static AuditEvent withRecalculation(AuditEvent change, UUID recalculationId) {
        List<FieldChange> changes = new ArrayList<>(change.changes());
        changes.add(new FieldChange("recalculationId", null, recalculationId.toString()));
        return new AuditEvent(change.eventId(), change.tenantId(), change.occurredAt(), change.actor(),
                change.action(), change.entityType(), change.entityId(), change.entityLabel(), changes);
    }

    private static ChargeRecord original(Planned planned, Line line) {
        return Objects.requireNonNull(planned.originals().get(Objects.requireNonNull(line.original()).id()));
    }

    /**
     * The source key 011 gives the expected charge of a line: the base key of the charge a recalculation books (a plot's
     * charge of a run of a closed month: the key of the original run).
     */
    private static String sourceKey(Line line) {
        Charge charge = Objects.requireNonNull(line.recharge());
        return switch (charge.planned()) {
            case OfTariff tariff -> tariff.charge().sourceKey();
            case OfInterval interval -> interval.charge().sourceKey();
            case OfLossesRun run -> LossesRuns.sourceKey(Objects.requireNonNull(line.runId()), run.charge().plotId());
            case OfCommonConsumerRun run -> CommonConsumerRuns.sourceKey(Objects.requireNonNull(line.runId()),
                    run.charge().consumerKey(), run.charge().plotId());
        };
    }

    /**
     * A run of an open month whose lines differ: every live charge reversed (source {@code RECALCULATION}, the
     * recalculation's id and reason, in the run's month), the run and its nodes or lines marked reversed, and, for
     * {@code REBOOK}, a new run of the same range and nodes or consumers booked with the lines' expected charges (with
     * the recalculation's id; its value uses and queue items as a run's booking writes them). Records the reversal and
     * the new charge of each line. The reversal clears the persistence context.
     */
    private void rebookRun(UUID tenantId, Planned planned, RunOutcome run, List<Line> lines,
            Function<LocalDate, BillingMonthRecord> monthFor, UUID recalculationId, String reason,
            @Nullable UUID createdBy, Instant now, Map<Line, UUID> reversals, Map<Line, UUID> created) {
        UUID runId = run.runId();
        List<Line> runLines = lines.stream().filter(line -> runId.equals(line.runId())).toList();
        UUID monthId = monthFor.apply(Objects.requireNonNull(planned.runMonths().get(runId)).atDay(1)).getId();
        boolean losses = planned.lossesPlans().containsKey(runId);
        LossesRuns.Reversed reversed = losses
                ? lossesRuns.reverseLocked(lossesRunRecords.findByIdForUpdate(runId).orElseThrow(), monthId, reason,
                        ChargeSource.RECALCULATION, recalculationId, createdBy, now)
                : commonConsumerRuns.reverseLocked(commonConsumerRunRecords.findByIdForUpdate(runId).orElseThrow(),
                        monthId, reason, ChargeSource.RECALCULATION, recalculationId, createdBy, now);
        for (Line line : runLines) {
            if (line.original() != null) {
                reversals.put(line, Objects.requireNonNull(reversed.reversalOf().get(line.original().id()),
                        "a live charge of the run is reversed with it"));
            }
        }
        if (run.action() != RunAction.REBOOK) {
            return;
        }
        List<Line> recharged = runLines.stream().filter(line -> line.recharge() != null).toList();
        YearMonth bookedIn;
        if (losses) {
            List<LossesPlanning.PlannedCharge> toBook = recharged.stream()
                    .map(line -> ((OfLossesRun) Objects.requireNonNull(line.recharge()).planned()).charge()).toList();
            LossesRuns.Booked booked = lossesRuns.book(tenantId, planned.lossesPlans().get(runId), toBook,
                    planned.snapshot(), createdBy, now, recalculationId);
            bookedIn = booked.month().yearMonth();
            recharged.forEach(line -> created.put(line, booked.chargeIds().get(line.plotId())));
        } else {
            List<CommonConsumerPlanning.PlannedCharge> toBook = recharged.stream()
                    .map(line -> ((OfCommonConsumerRun) Objects.requireNonNull(line.recharge()).planned()).charge())
                    .toList();
            CommonConsumerRuns.Booked booked = commonConsumerRuns.book(tenantId,
                    planned.commonConsumerPlans().get(runId), toBook, planned.snapshot(), createdBy, now,
                    recalculationId);
            bookedIn = booked.month().yearMonth();
            for (int i = 0; i < recharged.size(); i++) {
                created.put(recharged.get(i), booked.chargeIds().get(CommonConsumerRunViews.chargeKey(
                        toBook.get(i).consumerKey(), toBook.get(i).plotId())));
            }
        }
        for (Line line : recharged) {
            if (!bookedIn.equals(line.bookedIn())) {
                throw conflict(ProblemCodes.PREVIEW_OUTDATED,
                        "The charges changed since the preview. Preview again before booking.");
            }
        }
    }

    /**
     * The month a charge or adjustment is booked in; the planner said the same, since every month is locked
     * {@code FOR SHARE} while the plan is made.
     */
    private static BillingMonthRecord bookedIn(BillingMonthRecord month, Line line) {
        if (!month.yearMonth().equals(line.bookedIn())) {
            throw conflict(ProblemCodes.PREVIEW_OUTDATED,
                    "The charges changed since the preview. Preview again before booking.");
        }
        return month;
    }

    /**
     * Applies the plan's queue outcomes: an item that cannot be charged is opened (or kept open with the reason and
     * tariffs of this plan), a charged one is {@code RESOLVED}, one with nothing left {@code OBSOLETE}.
     *
     * @return the items opened or kept open
     */
    private List<NotChargedItem> applyQueue(UUID tenantId, Planned planned, Instant now) {
        for (QueueOutcome outcome : planned.plan().queue()) {
            // the reversal of a run clears the persistence context: an item read by the plan is read again
            NotChargedItemRecord open = attached(planned.openItems().get(outcome.itemKey()));
            ScopedInterval scoped = planned.intervals().get(outcome.itemKey());
            if (outcome.status() == Status.OPEN && scoped != null) {
                // a meter interval: the item keeps the interval's first point and day of this plan
                MeterInterval interval = scoped.interval();
                Reason reason = Objects.requireNonNull(outcome.reason());
                if (open != null) {
                    open.intervalFailedAgain(reason, outcome.tariffIds(), interval.from().key(),
                            interval.firstDay(), now);
                } else {
                    NotChargedItemRecord item = NotChargedItemRecord.interval(tenantId, scoped.plotId(),
                            chargeTypeOf(planned, outcome), interval.meterId(), interval.from().key(),
                            interval.to().key(), interval.firstDay(), interval.to().on(), reason,
                            outcome.tariffIds(), now);
                    items.save(item);
                    planned.openItems().put(outcome.itemKey(), item);
                }
            } else if (outcome.status() == Status.OPEN) {
                if (open != null) {
                    open.failedAgain(Objects.requireNonNull(outcome.reason()), outcome.tariffIds(), now);
                } else {
                    TariffMonthKey key = TariffMonthKey.parse(outcome.itemKey());
                    NotChargedItemRecord item = NotChargedItemRecord.tariffMonth(tenantId, key.plotId(),
                            key.chargeTypeId(), key.month(), Objects.requireNonNull(outcome.reason()),
                            outcome.tariffIds(), now);
                    items.save(item);
                    planned.openItems().put(outcome.itemKey(), item);
                }
            } else if (open != null) {
                open.close(outcome.status(), now);
            }
        }
        items.flush();
        return notCharged(planned);
    }

    private @Nullable NotChargedItemRecord attached(@Nullable NotChargedItemRecord item) {
        if (item == null || entityManager.contains(item)) {
            return item;
        }
        return items.findById(item.getId()).orElseThrow();
    }

    /** The charge type of the identity a queue outcome is about. */
    private static UUID chargeTypeOf(Planned planned, QueueOutcome outcome) {
        return Objects.requireNonNull(planned.expected().get(Objects.requireNonNull(outcome.identity())))
                .chargeTypeId();
    }

    /** The parts of a {@code TARIFF_MONTH:{plot}:{chargeType}:{YYYY-MM}} item key. */
    private record TariffMonthKey(UUID plotId, UUID chargeTypeId, YearMonth month) {

        static TariffMonthKey parse(String key) {
            String[] parts = key.split(":");
            return new TariffMonthKey(UUID.fromString(parts[1]), UUID.fromString(parts[2]),
                    YearMonth.parse(parts[3]));
        }
    }

    // ---- the answer

    /** The lines by plot (natural order of the numbers), then service period, then charge type. */
    private List<Line> ordered(List<Line> lines, Map<UUID, PlotFacts> plotById) {
        Map<UUID, Integer> plotRank = rank(plots.inNaturalOrder(
                lines.stream().map(Line::plotId).collect(Collectors.toSet())));
        Map<UUID, Integer> typeRank = rank(chargeTypes.all().stream()
                .sorted(Comparator.comparingInt(ChargeTypeFacts::position).thenComparing(ChargeTypeFacts::id))
                .map(ChargeTypeFacts::id).toList());
        return lines.stream().sorted(Comparator
                .comparing((Line line) -> plotRank.getOrDefault(line.plotId(), Integer.MAX_VALUE))
                .thenComparing(Line::serviceFrom)
                .thenComparing(line -> typeRank.getOrDefault(line.chargeTypeId(), Integer.MAX_VALUE))
                .thenComparing(Line::identity)
                .thenComparing(Line::action)).toList();
    }

    private static Map<UUID, Integer> rank(List<UUID> ids) {
        Map<UUID, Integer> rank = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            rank.put(ids.get(i), i);
        }
        return rank;
    }

    /**
     * The answer: a preview ({@code preview}), a booked recalculation ({@code id} present) or, for a cause-driven
     * change whose plan has no lines, the saved change alone ({@code changeApplied}, nothing booked).
     */
    private Recalculation result(Target target, Planned planned, boolean preview, boolean changeApplied,
            @Nullable UUID id, Map<Line, RecalculationLineRecord.Created> created,
            @Nullable List<NotChargedItem> notCharged) {
        List<Line> lines = ordered(planned.plan().lines(), planned.plotById());
        Map<UUID, ChargeTypeFacts> typeById = chargeTypes.all().stream()
                .collect(Collectors.toMap(ChargeTypeFacts::id, Function.identity()));
        Map<UUID, Money> byType = new LinkedHashMap<>();
        lines.forEach(line -> byType.merge(line.chargeTypeId(), line.difference(), Money::plus));
        List<RecalculationLine> shown = lines.stream().map(line -> line(line, planned.plotById(), typeById,
                created.get(line))).toList();
        int plotCount = (int) lines.stream().map(Line::plotId).distinct().count();
        Recalculation result = new Recalculation(preview, id != null, changeApplied,
                RecalculationCauseKind.fromValue(target.kind().name()), RecalculationQueries.scope(target.storedScope()),
                shown, RecalculationQueries.totals(byType, typeById), planned.plan().total().toString(), plotCount,
                notCharged != null ? notCharged : notCharged(planned), planned.digest());
        result.setCauseType(target.causeType());
        result.setId(id);
        return result;
    }

    private static RecalculationLine line(Line line, Map<UUID, PlotFacts> plotById,
            Map<UUID, ChargeTypeFacts> typeById, RecalculationLineRecord.@Nullable Created created) {
        List<UUID> createdIds = created == null ? List.of()
                : Stream.of(created.reversalChargeId(), created.newChargeId(), created.adjustmentChargeId())
                        .filter(Objects::nonNull).toList();
        RecalculationLine dto = new RecalculationLine(line.plotId(), plotById.get(line.plotId()).number(),
                ChargeViews.typeRef(typeById.get(line.chargeTypeId())), line.serviceFrom(), line.serviceTo(),
                line.booked().toString(), line.adjustments().toString(), line.effective().toString(),
                line.expected().toString(), line.difference().toString(),
                RecalculationAction.fromValue(line.action().name()), line.reasons(), createdIds);
        dto.setOriginalChargeId(line.original() == null ? null : line.original().id());
        dto.setBookedIn(line.bookedIn().toString());
        dto.setRunId(line.runId());
        if (line.notCharged() != null) {
            dto.setNotChargedReason(NotChargedReason.fromValue(line.notCharged().name()));
        }
        if (created != null) {
            dto.setReversalChargeId(created.reversalChargeId());
            dto.setNewChargeId(created.newChargeId());
            dto.setAdjustmentChargeId(created.adjustmentChargeId());
        }
        return dto;
    }

    /**
     * The "not charged" items the plan opens or keeps open; none when the plan has no lines (it books nothing, the
     * queue included).
     */
    private List<NotChargedItem> notCharged(Planned planned) {
        if (planned.plan().isEmpty()) {
            return List.of();
        }
        List<QueueOutcome> open = planned.plan().queue().stream().filter(q -> q.status() == Status.OPEN).toList();
        if (open.isEmpty()) {
            return List.of();
        }
        Set<UUID> plotIds = new HashSet<>();
        open.forEach(q -> {
            ScopedInterval scoped = planned.intervals().get(q.itemKey());
            plotIds.add(scoped != null ? scoped.plotId() : TariffMonthKey.parse(q.itemKey()).plotId());
        });
        NotChargedViews.Context context = itemViews.context(plotIds, planned.snapshot());
        return open.stream().map(q -> {
            ScopedInterval scoped = planned.intervals().get(q.itemKey());
            if (scoped != null) {
                MeterInterval interval = scoped.interval();
                return itemViews.interval(scoped.plotId(), chargeTypeOf(planned, q), interval.meterId(),
                        interval.firstDay(), interval.to().on(), Objects.requireNonNull(q.reason()), q.tariffIds(),
                        planned.openItems().get(q.itemKey()), context);
            }
            TariffMonthKey key = TariffMonthKey.parse(q.itemKey());
            List<TariffEntry> tariffs = q.tariffIds().stream().map(planned.snapshot()::tariff)
                    .flatMap(Optional::stream).toList();
            return itemViews.conflict(key.plotId(), key.chargeTypeId(), key.month(), tariffs,
                    planned.openItems().get(q.itemKey()), context);
        }).toList();
    }

    /**
     * The scope as stored and shown: ids and codes under the keys of the contract's {@code RecalculationScope}, plus
     * {@code causeType}. The charge types and plots are the chosen ones; none chosen means all (for a tariff version
     * cause: the plots of the tariff, which the caller adds as {@code tariffId}).
     */
    private static Map<String, Object> storedScope(RecalculationCauseType causeType, List<YearMonth> months,
            List<UUID> chargeTypeIds, List<UUID> plotIds) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("causeType", causeType.getValue());
        scope.put("months", months.stream().map(YearMonth::toString).toList());
        scope.put("chargeTypeIds", chargeTypeIds.stream().map(UUID::toString).toList());
        scope.put("plotIds", plotIds.stream().map(UUID::toString).toList());
        return scope;
    }

    /**
     * The values of {@code RECALCULATION_BOOKED}: codes and parameters only. The cause, the months {@code from} and
     * {@code to}, the chosen charge types ({@code base:<CODE>} or the stored name) and plots (numbers) when some were
     * chosen, the reason as typed, the number of lines and the total difference.
     */
    private List<FieldChange> auditChanges(Target target, Planned planned, List<Line> lines, String reason) {
        List<FieldChange> changes = new ArrayList<>();
        changes.add(new FieldChange("cause", null, target.causeType().getValue()));
        changes.add(new FieldChange("from", null, target.shownMonths().getFirst().toString()));
        changes.add(new FieldChange("to", null, target.shownMonths().getLast().toString()));
        if (!target.chargeTypeIds().isEmpty()) {
            Map<UUID, ChargeTypeFacts> typeById = chargeTypes.all().stream()
                    .collect(Collectors.toMap(ChargeTypeFacts::id, Function.identity()));
            changes.add(new FieldChange("chargeTypes", null, target.chargeTypeIds().stream()
                    .map(typeById::get).filter(Objects::nonNull).map(ChargeTypeFacts::auditName)
                    .collect(Collectors.joining(", "))));
        }
        if (!target.plotIds().isEmpty()) {
            changes.add(new FieldChange("plots", null, plots.inNaturalOrder(target.plotIds()).stream()
                    .map(planned.plotById()::get).filter(Objects::nonNull).map(PlotFacts::number)
                    .collect(Collectors.joining(", "))));
        }
        changes.add(new FieldChange("reason", null, reason));
        changes.add(new FieldChange("count", null, Integer.toString(lines.size())));
        changes.add(new FieldChange("total", null, planned.plan().total().toString()));
        return changes;
    }

    private static void throwIfAny(List<FieldProblem> problems) {
        if (!problems.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", problems);
        }
    }

    private static ApiProblemException invalid(String field, String code) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                "The request is not valid.", List.of(new FieldProblem(field, code)));
    }

    private static ApiProblemException conflict(String code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }
}
