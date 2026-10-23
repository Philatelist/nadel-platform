package app.nadel.ledger.internal;

import app.nadel.audit.ActorNames;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import app.nadel.ledger.internal.CommonConsumerPlanning.ConsumerInput;
import app.nadel.ledger.internal.CommonConsumerPlanning.Outcome;
import app.nadel.ledger.internal.CommonConsumerPlanning.Overlap;
import app.nadel.ledger.internal.CommonConsumerPlanning.Plan;
import app.nadel.ledger.internal.CommonConsumerPlanning.PlannedCharge;
import app.nadel.ledger.internal.CommonConsumerPlanning.PlannedLine;
import app.nadel.ledger.internal.CommonConsumerPlanning.PlannedShare;
import app.nadel.ledger.internal.CommonConsumerRunViews.StoredCharge;
import app.nadel.ledger.internal.LossesCalculator.ValueUse;
import app.nadel.ledger.internal.LossesRunViews.Lookups;
import app.nadel.ledger.internal.NotChargedItemRecord.Kind;
import app.nadel.ledger.internal.NotChargedItemRecord.Status;
import app.nadel.ledger.internal.ReadingChargeCalculator.PricedPart;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterNetwork;
import app.nadel.meters.MeterNetwork.Network;
import app.nadel.meters.MeterNetwork.NetworkChain;
import app.nadel.meters.WaterConnections;
import app.nadel.meters.WaterConnections.ConnectedPeriod;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.settings.CommonConsumerSplit;
import app.nadel.settings.CooperativeSettings;
import app.nadel.tariffs.DaySet;
import app.nadel.tariffs.TariffBook;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.text.Collator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.CommonConsumerRunDetails;
import nadel.contract.model.CommonConsumerRunRequest;
import nadel.contract.model.CommonConsumerRunResult;
import nadel.contract.model.CommonConsumerRunSummary;
import nadel.contract.model.RunReversal;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Common consumer runs (feature 011 §2.6, {@code /common-consumer-runs}): the preview and the booking of a run over a
 * date range, the history, the details of a run, the reversal of a whole run, and the retry of a "not charged" item of
 * a consumer ({@code COMMON_CONSUMER}).
 * <p>
 * A run reads the common consumer meters of the electricity network over the range ({@link MeterNetwork}; a
 * replacement keeps the consumer, keyed by the first meter of its chain), measures each consumer as the losses runs
 * measure a node ({@link LossesCalculator#measure}: end-of-day values), finds how it is shared
 * ({@link CommonConsumerSettings}, else the cooperative's {@code commonConsumerDefaultSplit}) and among which plots (the
 * chargeable plots: active and not common land; for {@code CONNECTED_PLOTS} those connected to any meter of the chain,
 * {@link WaterConnections#connectedToSource}, on their connected days), then plans the charges
 * ({@link CommonConsumerPlanning}). The rules, in order: {@code to} before {@code from} is 400 {@code INVALID}, after
 * today in Europe/Kyiv 400 {@code DATE_IN_FUTURE}; an id of {@code consumerIds} that is not a consumer of the range is
 * 400 {@code NOT_FOUND}. A booking then takes, in this order, the cooperative's advisory lock of common consumer runs
 * (one booking, reversal or retry at a time), every electricity meter {@code FOR SHARE}
 * ({@link MeterNetwork#lockSharedNetwork}), the queue lock, the charge types and tariffs {@code FOR SHARE} and the
 * billing month {@code FOR SHARE} ({@link BillingMonths#openForBooking}), the lock order of the month run and the losses
 * runs; no tariff for the run is 409 {@code NO_COMMON_CONSUMERS_TARIFF}; a digest that differs from the plan's is 409
 * {@code PREVIEW_OUTDATED}; a selected consumer that a booked run charged for a day of the range is 409
 * {@code COMMON_CONSUMER_RANGE_OVERLAP} (the exclusion constraint {@code common_consumer_run_line_no_overlap_ex} is the
 * backstop).
 * <p>
 * A booking writes the run, its lines (one per consumer, with its numbers, warning and plot shares), one
 * {@code COMMON_CONSUMER} charge per plot per consumer in the month of {@code to} or the earliest open month after it
 * (key {@code COMMON_CONSUMER_RUN:{run}:{consumer}:{plot}}, debt from {@code to}) with one part per tariff version, a
 * {@code meter_value_use} row per value a charged consumer read, an open "not charged" item per unknown consumer, closes
 * the open items of the consumers it charged once every day of an item is charged, else narrows the item to the days
 * left (and closes the item of a consumer that is no longer charged for the same range), writes
 * the audit entry {@code COMMON_CONSUMER_RUN_BOOKED} and publishes {@code ChargesBooked}.
 */
@Service
class CommonConsumerRuns {

    private static final String RUN_LOCK = "ledger.common-consumer-runs:";
    private static final String OVERLAP_CONSTRAINT = "common_consumer_run_line_no_overlap_ex";
    private static final String REVERSED_ONCE_INDEX = "charge_reversed_once_uq";

    private final MeterNetwork network;
    private final WaterConnections water;
    private final TariffBook book;
    private final TariffChargeWriter writer;
    private final PlotCatalog plots;
    private final CooperativeSettings settings;
    private final CommonConsumerSettings billing;
    private final BillingMonths billingMonths;
    private final BillingMonthRepository months;
    private final CommonConsumerRunRepository runs;
    private final CommonConsumerRunLineRepository lines;
    private final ChargeRepository charges;
    private final NotChargedItemRepository items;
    private final ActorNames actorNames;
    private final LedgerAuditPublisher audit;
    private final EntityManager entityManager;
    private final Clock clock;

    CommonConsumerRuns(MeterNetwork network, WaterConnections water, TariffBook book, TariffChargeWriter writer,
            PlotCatalog plots, CooperativeSettings settings, CommonConsumerSettings billing,
            BillingMonths billingMonths, BillingMonthRepository months, CommonConsumerRunRepository runs,
            CommonConsumerRunLineRepository lines, ChargeRepository charges, NotChargedItemRepository items,
            ActorNames actorNames, LedgerAuditPublisher audit, EntityManager entityManager, Clock clock) {
        this.network = network;
        this.water = water;
        this.book = book;
        this.writer = writer;
        this.plots = plots;
        this.settings = settings;
        this.billing = billing;
        this.billingMonths = billingMonths;
        this.months = months;
        this.runs = runs;
        this.lines = lines;
        this.charges = charges;
        this.items = items;
        this.actorNames = actorNames;
        this.audit = audit;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    // ---- preview and booking

    @Transactional
    CommonConsumerRunResult run(CommonConsumerRunRequest request, boolean preview, AccessProfile actor) {
        LocalDate from = Objects.requireNonNull(request.getFrom());
        LocalDate to = Objects.requireNonNull(request.getTo());
        if (to.isBefore(from)) {
            throw invalid("to", ProblemCodes.INVALID);
        }
        if (to.isAfter(LocalDate.now(clock))) {
            throw invalid("to", ProblemCodes.DATE_IN_FUTURE);
        }
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        List<UUID> consumerIds = request.getConsumerIds() == null ? List.of() : request.getConsumerIds();
        if (preview) {
            Plan plan = plan(network.network(from, to), from, to, consumerIds, book.snapshot());
            return CommonConsumerRunViews.result(plan, true, null, Map.of(), billingMonths.bookingMonth(to),
                    lookups(plan));
        }

        lockRuns(tenantId);
        Network locked = network.lockSharedNetwork(from, to);
        writer.lockQueue(tenantId);
        Snapshot snapshot = writer.lockTariffs();
        Plan plan = plan(locked, from, to, consumerIds, snapshot);
        if (plan.noTariff()) {
            throw conflict(ProblemCodes.NO_COMMON_CONSUMERS_TARIFF,
                    "There is no per-kWh tariff of the common consumers charge type for the range.");
        }
        String digest = request.getDigest();
        if (digest != null && !digest.equals(plan.digest())) {
            throw conflict(ProblemCodes.PREVIEW_OUTDATED, "The run would now book something else than the preview.");
        }
        if (!plan.overlapping().isEmpty()) {
            throw overlap();
        }
        Booked booked = book(tenantId, plan, snapshot, actor, clock.instant());
        return CommonConsumerRunViews.result(plan, false, booked.run().getId(), booked.chargeIds(),
                booked.month().yearMonth(), lookups(plan));
    }

    /** What a booking wrote; {@code chargeIds} by {@link CommonConsumerRunViews#chargeKey}. */
    private record Booked(CommonConsumerRunRecord run, BillingMonthRecord month, Map<String, UUID> chargeIds) {
    }

    /**
     * The plan of the run: every common consumer of the network in the range (or the selected ones), by name, with its
     * split, the plots that may share it and the booked run that charged it already.
     */
    private Plan plan(Network net, LocalDate from, LocalDate to, List<UUID> consumerIds, Snapshot snapshot) {
        Map<UUID, NetworkChain> chainOfMeter = new HashMap<>();
        List<NetworkChain> consumers = new ArrayList<>();
        for (NetworkChain chain : net.chains()) {
            if (chain.kind() == MeterKind.COMMON_CONSUMER) {
                consumers.add(chain);
                chain.meterIds().forEach(meter -> chainOfMeter.put(meter, chain));
            }
        }
        Set<UUID> selected = new LinkedHashSet<>();
        List<FieldProblem> problems = new ArrayList<>();
        for (int i = 0; i < consumerIds.size(); i++) {
            NetworkChain chain = chainOfMeter.get(consumerIds.get(i));
            if (chain == null) {
                problems.add(new FieldProblem("consumerIds[" + i + "]", ProblemCodes.NOT_FOUND));
            } else {
                selected.add(chain.key());
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", problems);
        }
        Collator collator = Collator.getInstance(Locale.forLanguageTag("uk"));
        List<NetworkChain> chosen = consumers.stream()
                .filter(chain -> selected.isEmpty() || selected.contains(chain.key()))
                .sorted(Comparator.comparing((NetworkChain chain) -> Objects.requireNonNullElse(chain.name(), ""),
                        collator).thenComparing(NetworkChain::key))
                .toList();

        Map<UUID, Overlap> overlaps = new HashMap<>();
        Map<UUID, CommonConsumerSettings.Billing> stored = Map.of();
        if (!chosen.isEmpty()) {
            for (CommonConsumerRunLineRecord row : lines.chargedOverlapping(
                    chosen.stream().map(NetworkChain::key).toList(), from, to)) {
                overlaps.putIfAbsent(row.getConsumerMeterId(), overlapOf(row));
            }
            stored = billing.of(chosen.stream().flatMap(chain -> chain.meterIds().stream()).toList());
        }
        List<PlotFacts> chargeable = plots.findChargeable();
        Map<UUID, TariffResolver.PlotFacts> facts = new LinkedHashMap<>();
        chargeable.forEach(plot -> facts.put(plot.id(), MonthRun.facts(plot)));
        CommonConsumerSplit fallback = settings.commonConsumerDefaultSplit();
        List<ConsumerInput> inputs = new ArrayList<>();
        for (NetworkChain chain : chosen) {
            CommonConsumerSettings.Billing own = CommonConsumerSettings.ofChain(chain.meterIds(), stored);
            CommonConsumerSplit split = own.split() == null ? fallback : own.split();
            inputs.add(new ConsumerInput(chain, split, LossesCalculator.measure(chain, from, to),
                    eligible(chain, split, own.users(), chargeable, from, to), overlaps.get(chain.key())));
        }
        return CommonConsumerPlanning.plan(from, to, inputs, snapshot, facts);
    }

    /** The plots that may share a consumer with their days, in the natural order of the plot numbers. */
    private Map<UUID, DaySet> eligible(NetworkChain chain, CommonConsumerSplit split, Set<UUID> users,
            List<PlotFacts> chargeable, LocalDate from, LocalDate to) {
        Map<UUID, DaySet> eligible = new LinkedHashMap<>();
        DaySet range = DaySet.of(from, to);
        switch (split) {
            case EQUAL_PER_PLOT -> chargeable.forEach(plot -> eligible.put(plot.id(), range));
            case SERVICE_USERS -> chargeable.stream().filter(plot -> users.contains(plot.id()))
                    .forEach(plot -> eligible.put(plot.id(), range));
            case CONNECTED_PLOTS -> {
                Map<UUID, List<DaySet.Range>> connected = new HashMap<>();
                for (UUID meter : chain.meterIds()) {
                    water.connectedToSource(meter, from, to).forEach((plot, periods) -> {
                        for (ConnectedPeriod period : periods) {
                            connected.computeIfAbsent(plot, k -> new ArrayList<>())
                                    .add(new DaySet.Range(period.from(), period.to()));
                        }
                    });
                }
                for (PlotFacts plot : chargeable) {
                    List<DaySet.Range> ranges = connected.get(plot.id());
                    if (ranges != null) {
                        eligible.put(plot.id(), DaySet.of(ranges));
                    }
                }
            }
            case NOT_CHARGED -> {
                // nobody shares it
            }
        }
        return eligible;
    }

    private Overlap overlapOf(CommonConsumerRunLineRecord row) {
        CommonConsumerRunRecord run = runs.findById(row.getRunId()).orElseThrow();
        return new Overlap(run.getId(), run.getFromDate(), run.getToDate());
    }

    /** Writes the run, its lines, charges, parts, value uses and queue items; audits and publishes. */
    private Booked book(UUID tenantId, Plan plan, Snapshot snapshot, AccessProfile actor, Instant now) {
        BillingMonthRecord month = billingMonths.openForBooking(tenantId, plan.to());
        CommonConsumerRunRecord run = CommonConsumerRunRecord.booked(tenantId, plan.from(), plan.to(), month.getId(),
                plan.total(), actor.memberId(), now);
        entityManager.persist(run);
        List<PlannedLine> planLines = plan.lines();
        for (int i = 0; i < planLines.size(); i++) {
            PlannedLine line = planLines.get(i);
            entityManager.persist(CommonConsumerRunLineRecord.of(tenantId, run.getId(), line.chain().key(),
                    line.chain().name(), plan.from(), plan.to(), i, line.split(), line.outcome(),
                    CommonConsumerRunViews.consumption(line.consumption()), line.calculated(), line.plotDays(),
                    line.amount().value(), CommonConsumerRunViews.warning(line), CommonConsumerRunViews.plots(line)));
        }
        try {
            entityManager.flush();
        } catch (PersistenceException e) {
            if (ConstraintViolations.violates(e, OVERLAP_CONSTRAINT)) {
                throw overlap();
            }
            throw e;
        }

        UUID type = Objects.requireNonNull(CommonConsumerPlanning.commonConsumersType(snapshot));
        Map<String, UUID> chargeIds = new LinkedHashMap<>();
        List<ChargeRecord> written = new ArrayList<>();
        for (PlannedCharge charge : plan.charges()) {
            ChargeRecord record = ChargeRecord.commonConsumerRun(tenantId, charge.plotId(), type, month.getId(),
                    charge.tariff().id(), run.getId(), charge.amount(), plan.from(), plan.to(),
                    "COMMON_CONSUMER_RUN:" + run.getId() + ":" + charge.consumerKey() + ":" + charge.plotId(),
                    charge.calculation(run.getId()), actor.memberId(), now);
            entityManager.persist(record);
            written.add(record);
            chargeIds.put(CommonConsumerRunViews.chargeKey(charge.consumerKey(), charge.plotId()), record.getId());
        }
        entityManager.flush();
        for (int i = 0; i < plan.charges().size(); i++) {
            PlannedCharge charge = plan.charges().get(i);
            List<UUID> versionIds = charge.versionIds();
            for (int p = 0; p < charge.parts().size(); p++) {
                PricedPart part = charge.parts().get(p);
                entityManager.persist(ChargePartRecord.of(tenantId, written.get(i).getId(), versionIds.get(p),
                        part.days(), new BigDecimal(part.quantity().numerator()),
                        new BigDecimal(part.quantity().denominator()), part.terms().rate().value()));
            }
        }
        entityManager.flush();
        writeUses(tenantId, run.getId(), planLines);
        updateQueue(tenantId, plan, type, now);

        Set<UUID> plotIds = plan.charges().stream().map(PlannedCharge::plotId).collect(Collectors.toSet());
        audit.publishCommonConsumerRun(actor, LedgerAuditPublisher.Action.COMMON_CONSUMER_RUN_BOOKED, run.getId(),
                run.label(), List.of(new FieldChange("from", null, plan.from().toString()),
                        new FieldChange("to", null, plan.to().toString()),
                        new FieldChange("billingMonth", null, month.yearMonth().toString()),
                        new FieldChange("consumers", null, Long.toString(planLines.stream()
                                .filter(PlannedLine::charges).count())),
                        new FieldChange("count", null, Integer.toString(plan.charges().size())),
                        new FieldChange("total", null, plan.total().toString())));
        if (!plotIds.isEmpty()) {
            writer.publishBooked(tenantId, plotIds);
        }
        return new Booked(run, month, chargeIds);
    }

    /** One {@code meter_value_use} row per value the charged consumers read, in one statement. */
    private void writeUses(UUID tenantId, UUID runId, List<PlannedLine> planLines) {
        Set<ValueUse> uses = new LinkedHashSet<>();
        planLines.stream().filter(PlannedLine::charges).forEach(line -> uses.addAll(line.uses()));
        if (uses.isEmpty()) {
            return;
        }
        String meterIds = uses.stream().map(use -> use.meterId().toString()).collect(Collectors.joining(","));
        String dates = uses.stream().map(use -> use.on().toString()).collect(Collectors.joining(","));
        entityManager.createNativeQuery("insert into meter_value_use (tenant_id, meter_id, used_on, run_id) "
                + "select :tenant, u.meter_id, u.used_on, :run from unnest(cast(string_to_array(:meters, ',') as uuid[]), "
                + "cast(string_to_array(:dates, ',') as date[])) as u(meter_id, used_on)")
                .setParameter("tenant", tenantId)
                .setParameter("run", runId)
                .setParameter("meters", meterIds)
                .setParameter("dates", dates)
                .executeUpdate();
    }

    /**
     * Opens an item per unknown consumer (or keeps the open one), settles the open items of a charged consumer whose
     * range meets the run's ({@link #settle}: those days are charged now) and closes the open item of a consumer that is
     * not charged by its split or has no plots, for the same range ({@code OBSOLETE}: nothing is left to charge).
     */
    private void updateQueue(UUID tenantId, Plan plan, @Nullable UUID type, Instant now) {
        for (PlannedLine line : plan.lines()) {
            UUID key = line.chain().key();
            String itemKey = NotChargedItemRecord.commonConsumerKey(key, plan.from(), plan.to());
            switch (line.outcome()) {
                case UNKNOWN -> {
                    if (items.findOpen(itemKey).isEmpty()) {
                        items.save(NotChargedItemRecord.commonConsumer(tenantId, key, type, plan.from(), plan.to(),
                                now));
                    }
                }
                case CHARGED -> {
                    for (NotChargedItemRecord item : items.findByKindAndStatusAndMeterId(Kind.COMMON_CONSUMER,
                            Status.OPEN, key)) {
                        if (item.getFromDate() != null && item.getToDate() != null
                                && !item.getFromDate().isAfter(plan.to()) && !item.getToDate().isBefore(plan.from())) {
                            settle(tenantId, item, now);
                        }
                    }
                }
                case NOT_CHARGED, NO_PLOTS -> items.findOpen(itemKey)
                        .ifPresent(item -> item.close(Status.OBSOLETE, now));
            }
        }
        items.flush();
    }

    /**
     * Settles an open item of a consumer some of whose days booked runs charged (the caller's run among them, flushed):
     * the item is {@code RESOLVED} when the booked runs that charged the consumer cover every day of its range. When some
     * days are left, the item is narrowed to them: it is {@code RESOLVED} (its charged days are charged) and an open item
     * {@code COMMON_CONSUMER:{consumer}:{from}:{to}} takes each run of days left (unless one is open already), so a retry
     * works them out on their own. An item none of whose days is charged stays as it is. As
     * {@code LossesRuns.settle} does for a node.
     */
    private void settle(UUID tenantId, NotChargedItemRecord item, Instant now) {
        LocalDate from = Objects.requireNonNull(item.getFromDate());
        LocalDate to = Objects.requireNonNull(item.getToDate());
        UUID consumer = Objects.requireNonNull(item.getMeterId());
        // oldest range first; two booked charged ranges of a consumer never overlap
        // (common_consumer_run_line_no_overlap_ex)
        List<DaySet.Range> left = UnchargedDays.left(from, to, lines.chargedOverlapping(List.of(consumer), from, to)
                .stream().map(charged -> new DaySet.Range(charged.getFromDate(), charged.getToDate())).toList());
        if (left.equals(List.of(new DaySet.Range(from, to)))) {
            return;
        }
        item.close(Status.RESOLVED, now);
        for (DaySet.Range days : left) {
            if (items.findOpen(NotChargedItemRecord.commonConsumerKey(consumer, days.from(), days.to())).isEmpty()) {
                items.save(NotChargedItemRecord.commonConsumer(tenantId, consumer, item.getChargeTypeId(),
                        days.from(), days.to(), now));
            }
        }
    }

    // ---- the history

    @Transactional(readOnly = true)
    List<CommonConsumerRunSummary> list(AccessProfile actor) {
        List<CommonConsumerRunRecord> all = runs.findAllNewestFirst();
        if (all.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<CommonConsumerRunLineRecord>> linesByRun = lines.findByRunIdIn(
                all.stream().map(CommonConsumerRunRecord::getId).toList()).stream()
                .collect(Collectors.groupingBy(CommonConsumerRunLineRecord::getRunId));
        Map<UUID, BillingMonthRecord> monthById = months.findAll().stream()
                .collect(Collectors.toMap(BillingMonthRecord::getId, month -> month));
        Lookups lookups = new Lookups(Map.of(), Map.of(), names(all, actor));
        return all.stream().map(run -> CommonConsumerRunViews.summary(run,
                linesByRun.getOrDefault(run.getId(), List.of()), monthById.get(run.getBillingMonthId()), actor,
                lookups)).toList();
    }

    @Transactional(readOnly = true)
    CommonConsumerRunDetails details(UUID id, AccessProfile actor) {
        CommonConsumerRunRecord run = runs.findById(id).orElseThrow(ApiProblemException::notFound);
        return details(run, actor);
    }

    private CommonConsumerRunDetails details(CommonConsumerRunRecord run, AccessProfile actor) {
        List<CommonConsumerRunLineRecord> runLines = lines.findByRunIdIn(List.of(run.getId()));
        List<ChargeRecord> runCharges = charges.findByRunIdAndKind(run.getId(), ChargeKind.REGULAR);
        Set<UUID> reversed = runCharges.isEmpty() ? Set.of()
                : new HashSet<>(charges.reversedAmong(runCharges.stream().map(ChargeRecord::getId).toList()));
        Map<String, StoredCharge> stored = new HashMap<>();
        String prefix = "COMMON_CONSUMER_RUN:" + run.getId() + ":";
        for (ChargeRecord charge : runCharges) {
            String key = charge.getSourceKey();
            if (key != null && key.startsWith(prefix)) {
                // the source key names the consumer and the plot: {consumer}:{plot}
                stored.put(key.substring(prefix.length()), new StoredCharge(charge.getId(),
                        reversed.contains(charge.getId())));
            }
        }
        Set<UUID> plotIds = new HashSet<>();
        for (CommonConsumerRunLineRecord line : runLines) {
            line.getPlots().forEach(plot -> plotIds.add(UUID.fromString(plot.get("plotId").toString())));
        }
        BillingMonthRecord month = months.findById(run.getBillingMonthId()).orElseThrow();
        return CommonConsumerRunViews.details(run, runLines, stored, month, actor,
                new Lookups(numbers(plotIds), rank(plotIds), names(List.of(run), actor)));
    }

    // ---- reversal

    @Transactional
    CommonConsumerRunDetails reverse(UUID id, RunReversal input, AccessProfile actor) {
        String reason = input.getReason() == null ? "" : input.getReason().strip();
        if (reason.isEmpty()) {
            throw invalid("reason", ProblemCodes.REQUIRED);
        }
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        lockRuns(tenantId);
        CommonConsumerRunRecord run = runs.findByIdForUpdate(id).orElseThrow(ApiProblemException::notFound);
        if (run.getStatus() == CommonConsumerRunRecord.Status.REVERSED) {
            throw conflict(ProblemCodes.RUN_ALREADY_REVERSED, "The run has already been reversed.");
        }
        BillingMonthRecord month = months.findByIdForShare(run.getBillingMonthId()).orElseThrow();
        if (month.isClosed()) {
            throw conflict(ProblemCodes.RUN_IN_CLOSED_MONTH,
                    "The charges of the run are booked in a closed month; they are corrected by an adjustment.");
        }
        Instant now = clock.instant();
        BillingMonthRecord booking = billingMonths.openForBooking(tenantId, month.yearMonth().atDay(1));
        List<ChargeRecord> runCharges = charges.findByRunIdAndKind(id, ChargeKind.REGULAR);
        Set<UUID> reversed = runCharges.isEmpty() ? Set.of()
                : new HashSet<>(charges.reversedAmong(runCharges.stream().map(ChargeRecord::getId).toList()));
        Money total = Money.ZERO;
        Set<UUID> plotIds = new HashSet<>();
        int count = 0;
        for (ChargeRecord charge : runCharges) {
            if (reversed.contains(charge.getId())) {
                continue;
            }
            ChargeRecord reversal = ChargeRecord.reversalOf(charge, booking.getId(), reason,
                    ChargeViews.plainCalculation(charge.getAmount().negate()), actor.memberId(), now);
            entityManager.persist(reversal);
            total = total.plus(reversal.getAmount());
            plotIds.add(charge.getPlotId());
            count++;
        }
        try {
            entityManager.flush();
        } catch (PersistenceException e) {
            if (ConstraintViolations.violates(e, REVERSED_ONCE_INDEX)) {
                throw conflict(ProblemCodes.CHARGE_ALREADY_REVERSED, "A charge of the run has just been reversed.");
            }
            throw e;
        }
        run.reverse(actor.memberId(), reason, now);
        lines.markReversed(id); // flushes the run first, then clears the persistence context
        audit.publishCommonConsumerRun(actor, LedgerAuditPublisher.Action.COMMON_CONSUMER_RUN_REVERSED, id,
                run.label(), List.of(
                        new FieldChange("from", null, run.getFromDate().toString()),
                        new FieldChange("to", null, run.getToDate().toString()),
                        new FieldChange("count", null, Integer.toString(count)),
                        new FieldChange("total", null, total.toString()),
                        new FieldChange("reason", null, reason)));
        if (!plotIds.isEmpty()) {
            writer.publishBooked(tenantId, plotIds);
        }
        return details(runs.findById(id).orElseThrow(), actor);
    }

    // ---- the retry of a common consumer item

    /** The status of a retried item, the run it booked (if any) and the consumer's name (for the audit label). */
    record Retried(Status status, @Nullable UUID runId, @Nullable String consumerName) {
    }

    /**
     * Retries an open {@code COMMON_CONSUMER} item: a run of its consumer alone over its range, booked as {@link #run}
     * books one when the consumer is charged now. Under the locks of a booking (the item {@code FOR UPDATE} after the
     * queue lock). The item is {@code RESOLVED} when the consumer is charged now or was charged by other runs
     * meanwhile (for some of its days only: narrowed to the days left, see {@link #settle}), {@code OBSOLETE} when nothing is left to charge (its split is {@code NOT_CHARGED}, no plot shares it,
     * or it is not a consumer of the range any more), and stays open while its value is still unknown or there is no
     * tariff to price it with.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Retried retry(NotChargedItemRecord item, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        LocalDate from = Objects.requireNonNull(item.getFromDate());
        LocalDate to = Objects.requireNonNull(item.getToDate());
        UUID consumer = Objects.requireNonNull(item.getMeterId());
        lockRuns(tenantId);
        Network locked = network.lockSharedNetwork(from, to);
        writer.lockQueue(tenantId);
        entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        String name = locked.chains().stream().filter(chain -> chain.key().equals(consumer)).map(NetworkChain::name)
                .filter(Objects::nonNull).findFirst().orElse(null);
        if (item.getStatus() != Status.OPEN) {
            return new Retried(Status.RESOLVED, null, name);
        }
        Instant now = clock.instant();
        Snapshot snapshot = writer.lockTariffs();
        Plan plan;
        try {
            plan = plan(locked, from, to, List.of(consumer), snapshot);
        } catch (ApiProblemException notAConsumer) {
            return closed(item, Status.OBSOLETE, now, name); // the meter worked on no day of the range any more
        }
        PlannedLine only = plan.lines().getFirst();
        if (only.overlap() != null) {
            // charged by another run meanwhile: resolved, or narrowed to the days left
            settle(tenantId, item, now);
            items.flush();
            return new Retried(item.getStatus(), null, name);
        }
        if (only.outcome() == Outcome.NOT_CHARGED || only.outcome() == Outcome.NO_PLOTS) {
            return closed(item, Status.OBSOLETE, now, name);
        }
        if (only.outcome() != Outcome.CHARGED || plan.noTariff()) {
            return new Retried(Status.OPEN, null, name);
        }
        Booked booked = book(tenantId, plan, snapshot, actor, now);
        if (item.getStatus() == Status.OPEN) {
            item.close(Status.RESOLVED, now);
            items.flush();
        }
        return new Retried(Status.RESOLVED, booked.run().getId(), name);
    }

    private Retried closed(NotChargedItemRecord item, Status status, Instant now, @Nullable String name) {
        item.close(status, now);
        items.flush();
        return new Retried(status, null, name);
    }

    // ---- helpers

    /** The transaction-scoped advisory lock of the cooperative's common consumer runs. */
    private void lockRuns(UUID tenantId) {
        entityManager.createNativeQuery(
                "select count(*) from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) as l")
                .setParameter("key", RUN_LOCK + tenantId)
                .getSingleResult();
    }

    /** The plot numbers and their order for a plan: the plots that share a consumer. */
    private Lookups lookups(Plan plan) {
        Set<UUID> plotIds = new HashSet<>();
        for (PlannedLine line : plan.lines()) {
            line.shares().stream().map(PlannedShare::plotId).forEach(plotIds::add);
        }
        return new Lookups(numbers(plotIds), rank(plotIds), Map.of());
    }

    private Map<UUID, String> numbers(Collection<UUID> plotIds) {
        if (plotIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> numbers = new HashMap<>();
        plots.findAll(plotIds).forEach((id, plot) -> numbers.put(id, plot.number()));
        return numbers;
    }

    private Map<UUID, Integer> rank(Collection<UUID> plotIds) {
        if (plotIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ordered = plots.inNaturalOrder(plotIds);
        Map<UUID, Integer> rank = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            rank.put(ordered.get(i), i);
        }
        return rank;
    }

    /** The current names of the authors of the runs, for a viewer with {@code CHARGES_VIEW}. */
    private Map<UUID, String> names(List<CommonConsumerRunRecord> list, AccessProfile actor) {
        if (!actor.can(Permission.CHARGES_VIEW)) {
            return Map.of();
        }
        Set<UUID> members = new HashSet<>();
        for (CommonConsumerRunRecord run : list) {
            if (run.getCreatedBy() != null) {
                members.add(run.getCreatedBy());
            }
            if (run.getReversedBy() != null) {
                members.add(run.getReversedBy());
            }
        }
        return members.isEmpty() ? Map.of() : actorNames.displayNames(members);
    }

    private static ApiProblemException invalid(String field, String code) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                "The request is not valid.", List.of(new FieldProblem(field, code)));
    }

    private static ApiProblemException overlap() {
        return conflict(ProblemCodes.COMMON_CONSUMER_RANGE_OVERLAP,
                "A common consumer of the run has already been charged for these days.");
    }

    private static ApiProblemException conflict(String code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }
}
