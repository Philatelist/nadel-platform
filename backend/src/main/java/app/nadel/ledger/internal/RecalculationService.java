package app.nadel.ledger.internal;

import app.nadel.audit.ActorNames;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
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
import app.nadel.meters.MeterCatalog;
import app.nadel.meters.MeterNetwork.MeterPoints;
import app.nadel.meters.MeterResource;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import app.nadel.tariffs.TariffBook.ChargeTypeEntry;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
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
import nadel.contract.model.RecalculationManualScope;
import nadel.contract.model.RecalculationRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Previews and books recalculations ({@code POST /recalculations?preview=}, feature 012 §2.1, §2.2 and §2.6). It handles
 * the cause {@code MANUAL} over the charges of recurring tariffs (the month run's charges) and the charges of plot meter
 * intervals (consumption, fixed-percentage losses, electricity and water); any other cause type is 400
 * {@code VALIDATION_FAILED} with {@code cause.type} {@code UNSUPPORTED} until slices 6 and 7, and the lines of losses
 * and common consumer runs join with slice 5.
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
 * types), the queue lock, the charge types and tariffs {@code FOR SHARE} ({@link TariffChargeWriter#lockTariffs}),
 * every billing month {@code FOR SHARE} (no month closes under the plan). Then the scope: the months {@code from} to
 * {@code to}; the charge types chosen (unknown 400 {@code NOT_FOUND}, inactive or archived 400 {@code INVALID}) or every
 * active one; the plots chosen ({@code UNKNOWN_PLOT}, {@code PLOT_ARCHIVED}, {@code PLOT_COMMON_LAND}) or every
 * chargeable one. The expected charges come from the month run's plan loop ({@link MonthRun#expected}) and from the
 * listener's planning of the meters' intervals whose closing reading is dated in the months
 * ({@link IntervalCharging#expected}; a charge type that is not active, water before it is switched on, is not in the
 * scope and charges nothing). The booked chains are the tariff charges of the scope (by the month of their source key)
 * and the interval charges of the locked meters (by their debt date, the date of the reading that closes the interval;
 * not the charges of runs), with their reversals and adjustments; {@link RecalculationPlanner} compares them; the
 * digest is {@link RecalculationDigest}.
 * <p>
 * A booking whose digest differs from the one of the plan now is 409 {@code PREVIEW_OUTDATED}; a plan without lines is
 * 409 {@code NOTHING_TO_RECALCULATE} (a preview of it answers with no lines, "No changes"). A plan without lines books
 * nothing at all, not even its queue outcomes: the queue is left to the month run, the listener and "Повторити", and
 * the preview of such a plan lists no "not charged" items. Otherwise, in this order: the {@code recalculation} row; the
 * reversals (source {@code RECALCULATION}, the reason, in the reversed charge's month); the new {@code REGULAR} charges
 * with their parts, and for an interval the two points it reads ({@code meter_value_use}) (the base key, or
 * {@code base#r{recalculation}} when a charge has the base key; in the month 011 would book them in); the adjustments
 * ({@link ChargeRecord#adjustment}, the difference, the original's service period and {@code accrued_on}, booked in the
 * earliest open month, with the parts of the expected charge and, for an interval, its two points); the queue (an item
 * that cannot be charged opened or refreshed, an interval item with the first point of its interval now, a charged one
 * {@code RESOLVED}, one with nothing left {@code OBSOLETE}); the {@code recalculation_line} rows; one
 * {@code AuditBatch} with {@code RECALCULATION_BOOKED}; {@code ChargesBooked} for the plots of the lines. The reason is
 * user content: never logged.
 */
@Service
class RecalculationService {

    /** The longest manual range, in months. */
    static final int MAX_MONTHS = 24;

    private static final int MAX_REASON = 500;
    private static final int CHUNK = 1000;
    private static final String WATER = "WATER";
    private static final String ELECTRICITY = "ELECTRICITY";

    private final TransactionTemplate transaction;
    private final ChargingLock chargingLock;
    private final TariffChargeWriter writer;
    private final MonthRun monthRun;
    private final IntervalCharging intervals;
    private final MeterCatalog meters;
    private final PlotCatalog plots;
    private final ChargeTypeCatalog chargeTypes;
    private final ChargeRepository charges;
    private final BillingMonthRepository months;
    private final BillingMonths billingMonths;
    private final NotChargedItemRepository items;
    private final NotChargedViews itemViews;
    private final LedgerAuditPublisher audit;
    private final ActorNames actorNames;
    private final EntityManager entityManager;
    private final Clock clock;

    RecalculationService(PlatformTransactionManager transactionManager, ChargingLock chargingLock,
            TariffChargeWriter writer, MonthRun monthRun, IntervalCharging intervals, MeterCatalog meters,
            PlotCatalog plots, ChargeTypeCatalog chargeTypes,
            ChargeRepository charges, BillingMonthRepository months, BillingMonths billingMonths,
            NotChargedItemRepository items, NotChargedViews itemViews, LedgerAuditPublisher audit,
            ActorNames actorNames, EntityManager entityManager, Clock clock) {
        this.transaction = new TransactionTemplate(transactionManager);
        this.chargingLock = chargingLock;
        this.writer = writer;
        this.monthRun = monthRun;
        this.intervals = intervals;
        this.meters = meters;
        this.plots = plots;
        this.chargeTypes = chargeTypes;
        this.charges = charges;
        this.months = months;
        this.billingMonths = billingMonths;
        this.items = items;
        this.itemViews = itemViews;
        this.audit = audit;
        this.actorNames = actorNames;
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

    /** The scope as resolved under the locks. */
    private record Scope(List<YearMonth> months, List<ChargeTypeEntry> types, List<PlotFacts> plots) {
    }

    /** The plots of the scope, read before their meters are locked, and the problems of the chosen ones. */
    private record PlotScope(List<PlotFacts> plots, List<FieldProblem> problems) {
    }

    /** A meter interval of the scope, for its "not charged" item ({@code INTERVAL:{meter}:{toKey}}). */
    private record ScopedInterval(UUID plotId, MeterInterval interval) {
    }

    /** What the plan is made of, and what the booking writes from. */
    private record Planned(Plan plan, String digest, Snapshot snapshot, Map<UUID, ChargeRecord> originals,
            Map<ChargeIdentity, Expected> expected, Map<String, NotChargedItemRecord> openItems,
            Map<String, ScopedInterval> intervals, Map<UUID, PlotFacts> plotById) {
    }

    /**
     * @throws ApiProblemException 400 for an invalid request, 409 {@code RECALCULATION_RANGE_TOO_LONG},
     *                             {@code PREVIEW_OUTDATED} or {@code NOTHING_TO_RECALCULATE}
     */
    Recalculation run(RecalculationRequest request, boolean preview, AccessProfile actor) {
        Manual manual = validate(request, preview);
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        return transaction.execute(status -> {
            if (preview) {
                // the outermost transaction: rolling it back never throws UnexpectedRollbackException
                status.setRollbackOnly();
            }
            Planned planned = plan(tenantId, manual);
            if (preview) {
                return result(manual, planned, true, null, Map.of(), null);
            }
            if (manual.digest() != null && !manual.digest().equals(planned.digest())) {
                throw conflict(ProblemCodes.PREVIEW_OUTDATED,
                        "The charges changed since the preview. Preview again before booking.");
            }
            if (planned.plan().isEmpty()) {
                throw conflict(ProblemCodes.NOTHING_TO_RECALCULATE, "Nothing to recalculate.");
            }
            return book(tenantId, manual, planned, actor);
        });
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
        String reason = request.getReason() == null ? null : request.getReason().strip();
        if (!preview) {
            if (reason == null || reason.isEmpty()) {
                throw invalid("reason", ProblemCodes.REQUIRED);
            }
            if (reason.length() > MAX_REASON) {
                throw invalid("reason", ProblemCodes.TOO_LONG);
            }
        }
        return new Manual(from, to, distinct(scope.getChargeTypeIds()), distinct(scope.getPlotIds()), reason,
                request.getDigest());
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

    private Planned plan(UUID tenantId, Manual manual) {
        chargingLock.lock(tenantId);
        // the plots first (read, not locked), then their meters in id order: the meter locks come before the queue lock
        PlotScope plotScope = plotScope(manual);
        List<MeterPoints> lockedMeters = lockMeters(tenantId, plotScope.plots(), manual.chargeTypeIds());
        writer.lockQueue(tenantId);
        Snapshot snapshot = writer.lockTariffs();
        months.lockAllForShare();

        Scope scope = scope(manual, snapshot, plotScope);
        Set<UUID> typeIds = scope.types().stream().map(ChargeTypeEntry::id).collect(Collectors.toSet());
        Map<UUID, PlotFacts> plotById = scope.plots().stream()
                .collect(Collectors.toMap(PlotFacts::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Set<YearMonth> inScope = new HashSet<>(scope.months());
        IntervalCharging.Window window = new IntervalCharging.Window(scope.months().getFirst().atDay(1),
                scope.months().getLast().atEndOfMonth());

        // the expected charges: the month run's, then the intervals of the plots' meters closed in the window
        List<Expected> expected = new ArrayList<>(monthRun.expected(snapshot, scope.plots(), scope.types(),
                scope.months()));
        Map<String, ScopedInterval> scopedIntervals = new LinkedHashMap<>();
        Set<UUID> meterIds = new HashSet<>();
        for (MeterPoints points : lockedMeters) {
            UUID plotId = points.plotId();
            if (plotId == null || !plotById.containsKey(plotId)) {
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
                                && meterIds.contains(charge.getMeterId()) && window.contains(charge.getAccruedOn())) {
                            originals.put(charge.getId(), charge);
                        }
                    }
                }
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
                    && item.getToDate() != null && window.contains(item.getToDate())) {
                openItems.put(item.getItemKey(), item);
            }
        }
        Set<UUID> water = snapshot.chargeTypes().stream().filter(type -> WATER.equals(type.code()))
                .map(ChargeTypeEntry::id).collect(Collectors.toSet());

        Plan plan = RecalculationPlanner.plan(new RecalculationPlanner.Input(booked, expected, states,
                LocalDate.now(clock), openItems.keySet(), RecalculationRecord.Cause.MANUAL, water));
        String digest = RecalculationDigest.of(RecalculationDigest.Cause.manual(),
                new RecalculationDigest.Scope(scope.months(), manual.chargeTypeIds(), manual.plotIds()), plan);
        Map<ChargeIdentity, Expected> expectedById = new HashMap<>();
        expected.forEach(entry -> expectedById.put(entry.identity(), entry));
        return new Planned(plan, digest, snapshot, originals, expectedById, openItems, scopedIntervals, plotById);
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

    /** The charge types of the scope checked under the tariff locks, then the plots resolved before. */
    private Scope scope(Manual manual, Snapshot snapshot, PlotScope plotScope) {
        List<ChargeTypeEntry> types = new ArrayList<>();
        if (manual.chargeTypeIds().isEmpty()) {
            snapshot.chargeTypes().stream().filter(ChargeTypeEntry::charges).forEach(types::add);
        } else {
            List<FieldProblem> problems = new ArrayList<>();
            for (int i = 0; i < manual.chargeTypeIds().size(); i++) {
                Optional<ChargeTypeEntry> type = snapshot.chargeType(manual.chargeTypeIds().get(i));
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
        return new Scope(manual.months(), types, plotScope.plots());
    }

    /** The plots chosen ({@code UNKNOWN_PLOT}, {@code PLOT_ARCHIVED}, {@code PLOT_COMMON_LAND}) or every chargeable one. */
    private PlotScope plotScope(Manual manual) {
        if (manual.plotIds().isEmpty()) {
            return new PlotScope(plots.findChargeable(), List.of());
        }
        Map<UUID, PlotFacts> found = plots.findAll(manual.plotIds());
        List<FieldProblem> problems = new ArrayList<>();
        List<PlotFacts> chosen = new ArrayList<>();
        for (int i = 0; i < manual.plotIds().size(); i++) {
            PlotFacts plot = found.get(manual.plotIds().get(i));
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

    private Recalculation book(UUID tenantId, Manual manual, Planned planned, AccessProfile actor) {
        Instant now = clock.instant();
        UUID createdBy = actor.memberId();
        String reason = Objects.requireNonNull(manual.reason());
        UUID recalculationId = UUID.randomUUID();
        List<Line> lines = ordered(planned.plan().lines(), planned.plotById());
        Set<UUID> plotIds = lines.stream().map(Line::plotId).collect(Collectors.toCollection(LinkedHashSet::new));

        entityManager.persist(RecalculationRecord.booked(recalculationId, tenantId, RecalculationRecord.Cause.MANUAL,
                null, storedScope(manual), reason, planned.plan().total(), lines.size(), plotIds.size(),
                planned.digest(), createdBy, now));
        entityManager.flush();

        Map<YearMonth, BillingMonthRecord> booking = new HashMap<>();
        Function<LocalDate, BillingMonthRecord> monthFor = date -> booking.computeIfAbsent(YearMonth.from(date),
                m -> billingMonths.openForBooking(tenantId, date));

        // 1. the reversals, in the reversed charge's month
        Map<Line, UUID> reversals = new HashMap<>();
        for (Line line : lines) {
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

        // 2. the new regular charges, under the base key unless a charge has it already
        Map<Line, Charge> recharges = new LinkedHashMap<>();
        for (Line line : lines) {
            if (line.recharge() != null) {
                recharges.put(line, line.recharge());
            }
        }
        Set<String> existing = recharges.isEmpty() ? Set.of() : new HashSet<>(charges.existingSourceKeys(
                recharges.values().stream().map(RecalculationService::sourceKey).toList()));
        Map<Line, UUID> created = new HashMap<>();
        for (Map.Entry<Line, Charge> entry : recharges.entrySet()) {
            Charge charge = entry.getValue();
            BillingMonthRecord month = bookedIn(monthFor.apply(charge.accruedOn()), entry.getKey());
            String base = sourceKey(charge);
            String key = existing.contains(base) ? base + "#r" + recalculationId : base;
            ChargeRecord record = switch (charge.planned()) {
                case OfTariff tariff -> writer.bookRecalculated(tenantId, tariff.charge(), month.getId(), key,
                        recalculationId, createdBy, now);
                case OfInterval interval -> intervals.bookRecalculated(tenantId, interval.charge(), month.getId(),
                        key, recalculationId, createdBy, now);
                case OfLossesRun _, OfCommonConsumerRun _ -> throw runsNotYet();
            };
            created.put(entry.getKey(), record.getId());
        }

        // 3. the adjustments, in the earliest open month, with the original's service period and debt date
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
                        case OfLossesRun _, OfCommonConsumerRun _ -> throw runsNotYet();
                    }
                }
                adjustments.put(line, adjustment.getId());
            }
        }
        entityManager.flush();

        // 4. the queue
        List<NotChargedItem> notCharged = applyQueue(tenantId, planned, now);

        // 5. the lines
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

        // 6. the audit entry and the plot statuses
        audit.publishBatch(tenantId, List.of(audit.recalculationEvent(actor, recalculationId,
                manual.from() + "/" + manual.to(), auditChanges(manual, planned, lines, reason))));
        writer.publishBooked(tenantId, plotIds);

        Recalculation result = result(manual, planned, false, recalculationId, createdByLine, notCharged);
        result.setReason(reason);
        result.setCreatedAt(now.atOffset(ZoneOffset.UTC));
        if (createdBy != null) {
            result.setCreatedByName(actorNames.displayNames(List.of(createdBy)).get(createdBy));
        }
        return result;
    }

    private static ChargeRecord original(Planned planned, Line line) {
        return Objects.requireNonNull(planned.originals().get(Objects.requireNonNull(line.original()).id()));
    }

    /** The source key 011 gives the expected charge: the base key of the charge a recalculation books. */
    private static String sourceKey(Charge charge) {
        return switch (charge.planned()) {
            case OfTariff tariff -> tariff.charge().sourceKey();
            case OfInterval interval -> interval.charge().sourceKey();
            case OfLossesRun _, OfCommonConsumerRun _ -> throw runsNotYet();
        };
    }

    private static IllegalStateException runsNotYet() {
        return new IllegalStateException("The lines of losses and common consumer runs are booked from slice 5");
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
            NotChargedItemRecord open = planned.openItems().get(outcome.itemKey());
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

    private Recalculation result(Manual manual, Planned planned, boolean preview, @Nullable UUID id,
            Map<Line, RecalculationLineRecord.Created> created, @Nullable List<NotChargedItem> notCharged) {
        List<Line> lines = ordered(planned.plan().lines(), planned.plotById());
        Map<UUID, ChargeTypeFacts> typeById = chargeTypes.all().stream()
                .collect(Collectors.toMap(ChargeTypeFacts::id, Function.identity()));
        Map<UUID, Money> byType = new LinkedHashMap<>();
        lines.forEach(line -> byType.merge(line.chargeTypeId(), line.difference(), Money::plus));
        List<RecalculationLine> shown = lines.stream().map(line -> line(line, planned.plotById(), typeById,
                created.get(line))).toList();
        int plotCount = (int) lines.stream().map(Line::plotId).distinct().count();
        Recalculation result = new Recalculation(preview, !preview, false, RecalculationCauseKind.MANUAL,
                RecalculationQueries.scope(storedScope(manual)), shown, RecalculationQueries.totals(byType, typeById),
                planned.plan().total().toString(), plotCount,
                notCharged != null ? notCharged : notCharged(planned), planned.digest());
        result.setCauseType(RecalculationCauseType.MANUAL);
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
     * {@code causeType}. The charge types and plots are the chosen ones; none chosen means all.
     */
    private static Map<String, Object> storedScope(Manual manual) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("causeType", RecalculationCauseType.MANUAL.getValue());
        scope.put("months", manual.months().stream().map(YearMonth::toString).toList());
        scope.put("chargeTypeIds", manual.chargeTypeIds().stream().map(UUID::toString).toList());
        scope.put("plotIds", manual.plotIds().stream().map(UUID::toString).toList());
        return scope;
    }

    /**
     * The values of {@code RECALCULATION_BOOKED}: codes and parameters only. The cause, the months {@code from} and
     * {@code to}, the chosen charge types ({@code base:<CODE>} or the stored name) and plots (numbers) when some were
     * chosen, the reason as typed, the number of lines and the total difference.
     */
    private List<FieldChange> auditChanges(Manual manual, Planned planned, List<Line> lines, String reason) {
        List<FieldChange> changes = new ArrayList<>();
        changes.add(new FieldChange("cause", null, RecalculationCauseType.MANUAL.getValue()));
        changes.add(new FieldChange("from", null, manual.from().toString()));
        changes.add(new FieldChange("to", null, manual.to().toString()));
        if (!manual.chargeTypeIds().isEmpty()) {
            Map<UUID, ChargeTypeFacts> typeById = chargeTypes.all().stream()
                    .collect(Collectors.toMap(ChargeTypeFacts::id, Function.identity()));
            changes.add(new FieldChange("chargeTypes", null, manual.chargeTypeIds().stream()
                    .map(typeById::get).filter(Objects::nonNull).map(ChargeTypeFacts::auditName)
                    .collect(Collectors.joining(", "))));
        }
        if (!manual.plotIds().isEmpty()) {
            changes.add(new FieldChange("plots", null, plots.inNaturalOrder(manual.plotIds()).stream()
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
