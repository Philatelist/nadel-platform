package app.nadel.ledger.internal;

import app.nadel.audit.ActorNames;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import app.nadel.ledger.internal.LossesCalculator.NodeResult;
import app.nadel.ledger.internal.LossesCalculator.Outcome;
import app.nadel.ledger.internal.LossesCalculator.ValueUse;
import app.nadel.ledger.internal.LossesPlanning.NodePart;
import app.nadel.ledger.internal.LossesPlanning.Overlap;
import app.nadel.ledger.internal.LossesPlanning.Plan;
import app.nadel.ledger.internal.LossesPlanning.PlannedCharge;
import app.nadel.ledger.internal.LossesPlanning.PlannedInput;
import app.nadel.ledger.internal.LossesPlanning.PlannedNode;
import app.nadel.ledger.internal.LossesRunViews.Lookups;
import app.nadel.ledger.internal.LossesRunViews.StoredCharge;
import app.nadel.ledger.internal.NotChargedItemRecord.Kind;
import app.nadel.ledger.internal.NotChargedItemRecord.Status;
import app.nadel.ledger.internal.ReadingChargeCalculator.PricedPart;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterNetwork;
import app.nadel.meters.MeterNetwork.Network;
import app.nadel.meters.MeterNetwork.NetworkChain;
import app.nadel.meters.Quantity;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.settings.CooperativeSettings;
import app.nadel.settings.LossesMode;
import app.nadel.tariffs.DaySet;
import app.nadel.tariffs.TariffBook;
import app.nadel.tariffs.TariffBook.ChargeTypeEntry;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.LossesRunDetails;
import nadel.contract.model.LossesRunRequest;
import nadel.contract.model.LossesRunResult;
import nadel.contract.model.LossesRunSummary;
import nadel.contract.model.RunReversal;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proportional losses runs (feature 011 §2.5, {@code /losses-runs}): the preview and the booking of a run over a date
 * range, the history, the details of a run, the reversal of a whole run, and the retry of a "not charged" item of a node
 * ({@code LOSSES_NODE}).
 * <p>
 * A run reads the electricity network as it was over the range ({@link MeterNetwork}), works out the losses of every
 * node ({@link LossesCalculator}) and prices them per plot ({@link LossesPlanning}). The rules, in order: {@code to}
 * before {@code from} is 400 {@code INVALID}, after today in Europe/Kyiv 400 {@code DATE_IN_FUTURE}; an id of
 * {@code nodeIds} that is not a node of the range is 400 {@code NOT_FOUND}; the losses mode is not
 * {@code PROPORTIONAL}: 409 {@code LOSSES_MODE_NOT_PROPORTIONAL}. A booking then takes, in this order, the cooperative's
 * advisory lock of losses runs (one booking, reversal or retry at a time), every electricity meter {@code FOR SHARE}
 * ({@link MeterNetwork#lockSharedNetwork}: a reading change waits, then sees the run's usage), the queue lock, the charge
 * types and tariffs {@code FOR SHARE} and the billing month {@code FOR SHARE}
 * ({@link BillingMonths#openForBooking}), the lock order of the month run; a digest that differs from the plan's is
 * 409 {@code PREVIEW_OUTDATED}; a selected node that a booked run charged for a day of the range is 409
 * {@code LOSSES_RANGE_OVERLAP} (the exclusion constraint {@code losses_run_node_no_overlap_ex} is the backstop).
 * <p>
 * A booking writes the run, its nodes (with their numbers, warnings and plot shares), one {@code LOSSES} charge per plot
 * in the month of {@code to} or the earliest open month after it (key {@code LOSSES_RUN:{run}:{plot}}, debt from
 * {@code to}) with one part per node and tariff version, a {@code meter_value_use} row per value the charged nodes read,
 * an open "not charged" item per unknown or skipped node, closes the open items of the nodes it charged once every day
 * of an item is charged, else narrows the item to the days left (and closes the item of a node now known to be
 * negative, for the same range), writes the audit entry {@code LOSSES_RUN_BOOKED} and publishes
 * {@code ChargesBooked}.
 */
@Service
class LossesRuns {

    private static final String RUN_LOCK = "ledger.losses-runs:";
    private static final String OVERLAP_CONSTRAINT = "losses_run_node_no_overlap_ex";
    private static final String REVERSED_ONCE_INDEX = "charge_reversed_once_uq";
    private static final Map<MeterKind, Integer> KIND_ORDER = Map.of(MeterKind.SUBSTATION, 0, MeterKind.QUARTER, 1);

    private final MeterNetwork network;
    private final TariffBook book;
    private final TariffChargeWriter writer;
    private final PlotCatalog plots;
    private final CooperativeSettings settings;
    private final BillingMonths billingMonths;
    private final BillingMonthRepository months;
    private final LossesRunRepository runs;
    private final LossesRunNodeRepository nodes;
    private final ChargeRepository charges;
    private final ChargePartRepository parts;
    private final NotChargedItemRepository items;
    private final ActorNames actorNames;
    private final LedgerAuditPublisher audit;
    private final ChargingLock chargingLock;
    private final EntityManager entityManager;
    private final Clock clock;

    LossesRuns(MeterNetwork network, TariffBook book, TariffChargeWriter writer, PlotCatalog plots,
            CooperativeSettings settings, BillingMonths billingMonths, BillingMonthRepository months,
            LossesRunRepository runs, LossesRunNodeRepository nodes, ChargeRepository charges,
            ChargePartRepository parts, NotChargedItemRepository items, ActorNames actorNames,
            LedgerAuditPublisher audit, ChargingLock chargingLock, EntityManager entityManager,
            Clock clock) {
        this.network = network;
        this.book = book;
        this.writer = writer;
        this.plots = plots;
        this.settings = settings;
        this.billingMonths = billingMonths;
        this.months = months;
        this.runs = runs;
        this.nodes = nodes;
        this.charges = charges;
        this.parts = parts;
        this.items = items;
        this.actorNames = actorNames;
        this.audit = audit;
        this.chargingLock = chargingLock;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** A plan with what the views and a booking need about it. */
    record Planned(Plan plan, Map<UUID, NetworkChain> chains) {
    }

    // ---- preview and booking

    @Transactional
    LossesRunResult run(LossesRunRequest request, boolean preview, AccessProfile actor) {
        LocalDate from = Objects.requireNonNull(request.getFrom());
        LocalDate to = Objects.requireNonNull(request.getTo());
        if (to.isBefore(from)) {
            throw invalid("to", ProblemCodes.INVALID);
        }
        if (to.isAfter(LocalDate.now(clock))) {
            throw invalid("to", ProblemCodes.DATE_IN_FUTURE);
        }
        requireProportional();
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        List<UUID> nodeIds = request.getNodeIds() == null ? List.of() : request.getNodeIds();
        if (preview) {
            Planned planned = plan(network.network(from, to), from, to, nodeIds, book.snapshot(), Set.of(), false);
            return LossesRunViews.result(planned.plan(), true, null, Map.of(), billingMonths.bookingMonth(to),
                    planned.chains(), lookups(planned));
        }

        lockRuns(tenantId);
        Network locked = network.lockSharedNetwork(from, to);
        writer.lockQueue(tenantId);
        Snapshot snapshot = writer.lockTariffs();
        Planned planned = plan(locked, from, to, nodeIds, snapshot, Set.of(), false);
        String digest = request.getDigest();
        if (digest != null && !digest.equals(planned.plan().digest())) {
            throw conflict(ProblemCodes.PREVIEW_OUTDATED, "The run would now book something else than the preview.");
        }
        if (!planned.plan().overlapping().isEmpty()) {
            throw overlap();
        }
        Booked booked = bookAndAudit(tenantId, planned, snapshot, actor, clock.instant());
        return LossesRunViews.result(planned.plan(), false, booked.run().getId(), booked.chargeIds(),
                booked.month().yearMonth(), planned.chains(), lookups(planned));
    }

    /** What a booking wrote: the run, the month of its charges and the charge of each plot. */
    record Booked(LossesRunRecord run, BillingMonthRecord month, Map<UUID, UUID> chargeIds) {
    }

    /**
     * The plan of the run over the network: every node worked out, the selected ones in their order (substations,
     * then quarter meters, each by name), the booked runs that charged them already (except {@code ignoreRunIds}: a
     * recalculation plans a booked run again without it overlapping itself), and the plot facts for pricing. An id of
     * {@code nodeIds} that is not a node of the range is 400 {@code NOT_FOUND}, or left out when {@code dropUnknown}
     * (then no node is selected when none is left).
     */
    private Planned plan(Network net, LocalDate from, LocalDate to, List<UUID> nodeIds, Snapshot snapshot,
            Set<UUID> ignoreRunIds, boolean dropUnknown) {
        Map<UUID, NetworkChain> chains = new LinkedHashMap<>();
        Map<UUID, UUID> chainOfMeter = new HashMap<>();
        for (NetworkChain chain : net.chains()) {
            chains.put(chain.key(), chain);
            chain.meterIds().forEach(meter -> chainOfMeter.put(meter, chain.key()));
        }
        List<NodeResult> results = LossesCalculator.calculate(net.chains(), from, to);
        Map<UUID, NodeResult> byKey = results.stream()
                .collect(Collectors.toMap(NodeResult::nodeKey, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Set<UUID> selected = new LinkedHashSet<>();
        List<FieldProblem> problems = new ArrayList<>();
        for (int i = 0; i < nodeIds.size(); i++) {
            UUID key = chainOfMeter.get(nodeIds.get(i));
            if (key == null || !byKey.containsKey(key)) {
                if (!dropUnknown) {
                    problems.add(new FieldProblem("nodeIds[" + i + "]", ProblemCodes.NOT_FOUND));
                }
            } else {
                selected.add(key);
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", problems);
        }
        boolean all = nodeIds.isEmpty();
        Collator collator = Collator.getInstance(Locale.forLanguageTag("uk"));
        List<NodeResult> chosen = results.stream().filter(r -> all || selected.contains(r.nodeKey()))
                .sorted(Comparator.comparing((NodeResult r) -> KIND_ORDER.getOrDefault(chains.get(r.nodeKey()).kind(),
                        9))
                        .thenComparing(r -> Objects.requireNonNullElse(chains.get(r.nodeKey()).name(), ""), collator)
                        .thenComparing(NodeResult::nodeKey))
                .toList();

        Map<UUID, Overlap> overlaps = new HashMap<>();
        if (!chosen.isEmpty()) {
            for (LossesRunNodeRecord row : nodes.chargedOverlapping(
                    chosen.stream().map(NodeResult::nodeKey).toList(), from, to)) {
                if (!ignoreRunIds.contains(row.getRunId())) {
                    overlaps.putIfAbsent(row.getNodeMeterId(), overlapOf(row));
                }
            }
        }
        List<PlannedInput> inputs = chosen.stream()
                .map(r -> new PlannedInput(chains.get(r.nodeKey()), r, overlaps.get(r.nodeKey()))).toList();
        Set<UUID> plotIds = new HashSet<>();
        inputs.stream().filter(PlannedInput::charges).forEach(input -> plotIds.addAll(input.result().shares()
                .keySet()));
        Map<UUID, TariffResolver.PlotFacts> facts = new HashMap<>();
        plots.findAll(plotIds).forEach((id, plot) -> facts.put(id, MonthRun.facts(plot)));
        UUID electricity = snapshot.chargeTypes().stream().filter(type -> "ELECTRICITY".equals(type.code()))
                .map(ChargeTypeEntry::id).findFirst().orElse(null);
        return new Planned(LossesPlanning.plan(from, to, inputs, snapshot, electricity, facts), chains);
    }

    private Overlap overlapOf(LossesRunNodeRecord row) {
        LossesRunRecord run = runs.findById(row.getRunId()).orElseThrow();
        return new Overlap(run.getId(), run.getFromDate(), run.getToDate());
    }

    /** Writes the run with every charge of the plan ({@link #book}), audits it and publishes. */
    private Booked bookAndAudit(UUID tenantId, Planned planned, Snapshot snapshot, AccessProfile actor, Instant now) {
        Plan plan = planned.plan();
        Booked booked = book(tenantId, planned, plan.charges(), snapshot, actor.memberId(), now, null);
        LossesRunRecord run = booked.run();
        audit.publishLossesRun(actor, LedgerAuditPublisher.Action.LOSSES_RUN_BOOKED, run.getId(), run.label(),
                List.of(new FieldChange("from", null, plan.from().toString()),
                        new FieldChange("to", null, plan.to().toString()),
                        new FieldChange("billingMonth", null, booked.month().yearMonth().toString()),
                        new FieldChange("nodes", null, Long.toString(plan.nodes().stream()
                                .filter(PlannedNode::charges).count())),
                        new FieldChange("count", null, Integer.toString(plan.charges().size())),
                        new FieldChange("total", null, plan.total().toString())));
        Set<UUID> plotIds = plan.charges().stream().map(PlannedCharge::plotId).collect(Collectors.toSet());
        if (!plotIds.isEmpty()) {
            writer.publishBooked(tenantId, plotIds);
        }
        return booked;
    }

    /**
     * Writes a run of the plan: the run (its total the sum of {@code charges}), its nodes, the given charges (the
     * plan's, or those of a recalculation's lines; with {@code recalculationId} when a recalculation books the run
     * again), their parts, the value uses of the charged nodes and the queue items. Neither audits nor publishes: the
     * caller does (a recalculation in its own audit entry). The run's month is the month of {@code to}, or the earliest
     * open month after it. Under the locks of a booking; 409 {@code LOSSES_RANGE_OVERLAP} when a charged node meets a
     * booked run's days.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Booked book(UUID tenantId, Planned planned, List<PlannedCharge> charges, Snapshot snapshot,
            @Nullable UUID createdBy, Instant now, @Nullable UUID recalculationId) {
        Plan plan = planned.plan();
        BillingMonthRecord month = billingMonths.openForBooking(tenantId, plan.to());
        Money runTotal = charges.stream().map(PlannedCharge::amount).reduce(Money.ZERO, Money::plus);
        LossesRunRecord run = LossesRunRecord.booked(tenantId, plan.from(), plan.to(), month.getId(), runTotal,
                createdBy, now);
        entityManager.persist(run);
        List<PlannedNode> planNodes = plan.nodes();
        for (int i = 0; i < planNodes.size(); i++) {
            entityManager.persist(nodeRow(tenantId, run.getId(), plan, planNodes.get(i), i, planned.chains()));
        }
        try {
            entityManager.flush();
        } catch (PersistenceException e) {
            if (ConstraintViolations.violates(e, OVERLAP_CONSTRAINT)) {
                throw overlap();
            }
            throw e;
        }

        UUID electricity = snapshot.chargeTypes().stream().filter(type -> "ELECTRICITY".equals(type.code()))
                .map(ChargeTypeEntry::id).findFirst().orElse(null);
        Map<UUID, UUID> chargeIds = new LinkedHashMap<>();
        List<ChargeRecord> written = new ArrayList<>();
        for (PlannedCharge charge : charges) {
            ChargeRecord record = ChargeRecord.lossesRun(tenantId, charge.plotId(), Objects.requireNonNull(electricity),
                    month.getId(), charge.tariff().id(), run.getId(), charge.amount(), plan.from(), plan.to(),
                    sourceKey(run.getId(), charge.plotId()), charge.calculation(run.getId()), recalculationId,
                    createdBy, now);
            entityManager.persist(record);
            written.add(record);
            chargeIds.put(charge.plotId(), record.getId());
        }
        entityManager.flush();
        for (int i = 0; i < charges.size(); i++) {
            writeParts(tenantId, written.get(i).getId(), charges.get(i));
        }
        entityManager.flush();
        writeUses(tenantId, run.getId(), planNodes);
        updateQueue(tenantId, plan, electricity, now);
        return new Booked(run, month, chargeIds);
    }

    /** The source key of a plot's charge of a run: {@code LOSSES_RUN:{run}:{plot}}. */
    static String sourceKey(UUID runId, UUID plotId) {
        return "LOSSES_RUN:" + runId + ":" + plotId;
    }

    /** One part per node and tariff version of the charge. */
    private void writeParts(UUID tenantId, UUID chargeId, PlannedCharge charge) {
        List<UUID> versionIds = charge.versionIds();
        for (int p = 0; p < charge.parts().size(); p++) {
            NodePart nodePart = charge.parts().get(p);
            PricedPart part = nodePart.part();
            entityManager.persist(ChargePartRecord.ofNode(tenantId, chargeId, versionIds.get(p), part.days(),
                    new BigDecimal(part.quantity().numerator()), new BigDecimal(part.quantity().denominator()),
                    part.terms().rate().value(), nodePart.nodeKey()));
        }
    }

    private static LossesRunNodeRecord nodeRow(UUID tenantId, UUID runId, Plan plan, PlannedNode node, int position,
            Map<UUID, NetworkChain> chains) {
        NodeResult result = node.result();
        Quantity losses = result.losses();
        return LossesRunNodeRecord.of(tenantId, runId, node.chain().key(), node.chain().kind(), node.chain().name(),
                plan.from(), plan.to(), position, result.outcome(),
                result.consumption() == null ? null : result.consumption().toMeterValue().value(),
                result.children() == null ? null : result.children().toMeterValue().value(),
                losses == null ? null : new BigDecimal(LossesRunViews.kwh(losses)),
                losses == null || result.consumption() == null ? null
                        : LossesRunViews.pct(losses, result.consumption()),
                result.calculated(), node.amount().value(), LossesRunViews.warning(node, chains),
                LossesRunViews.plots(node));
    }

    /** One {@code meter_value_use} row per value the charged nodes read, in one statement. */
    private void writeUses(UUID tenantId, UUID runId, List<PlannedNode> planNodes) {
        Set<ValueUse> uses = new LinkedHashSet<>();
        planNodes.stream().filter(PlannedNode::charges).forEach(node -> uses.addAll(node.result().uses()));
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
     * Opens an item per unknown or skipped node (or keeps the open one), settles the open items of a charged node whose
     * range meets the run's ({@link #settle}: those days are charged now) and closes the open item of a negative node
     * for the same range ({@code OBSOLETE}: nothing is left to charge).
     */
    private void updateQueue(UUID tenantId, Plan plan, @Nullable UUID electricity, Instant now) {
        for (PlannedNode node : plan.nodes()) {
            UUID key = node.chain().key();
            String itemKey = NotChargedItemRecord.lossesNodeKey(key, plan.from(), plan.to());
            switch (node.result().outcome()) {
                case UNKNOWN, SKIPPED_ABOVE -> {
                    if (items.findOpen(itemKey).isEmpty() && electricity != null) {
                        items.save(NotChargedItemRecord.lossesNode(tenantId, key, electricity, plan.from(), plan.to(),
                                now));
                    }
                }
                case CHARGED -> {
                    for (NotChargedItemRecord item : items.findByKindAndStatusAndMeterId(Kind.LOSSES_NODE, Status.OPEN,
                            key)) {
                        if (item.getFromDate() != null && item.getToDate() != null
                                && !item.getFromDate().isAfter(plan.to()) && !item.getToDate().isBefore(plan.from())) {
                            settle(tenantId, item, now);
                        }
                    }
                }
                case NEGATIVE -> items.findOpen(itemKey).ifPresent(item -> item.close(Status.OBSOLETE, now));
            }
        }
        items.flush();
    }

    /**
     * Settles an open item of a node some of whose days booked runs charged (the caller's run among them, flushed): the
     * item is {@code RESOLVED} when the booked runs that charged the node cover every day of its range. When some days
     * are left, the item is narrowed to them: it is {@code RESOLVED} (its charged days are charged) and an open item
     * {@code LOSSES_NODE:{node}:{from}:{to}} takes each run of days left (unless one is open already), so a retry works
     * them out on their own. An item none of whose days is charged stays as it is.
     */
    private void settle(UUID tenantId, NotChargedItemRecord item, Instant now) {
        LocalDate from = Objects.requireNonNull(item.getFromDate());
        LocalDate to = Objects.requireNonNull(item.getToDate());
        UUID node = Objects.requireNonNull(item.getMeterId());
        // oldest range first; two booked charged ranges of a node never overlap (losses_run_node_no_overlap_ex)
        List<DaySet.Range> left = UnchargedDays.left(from, to, nodes.chargedOverlapping(List.of(node), from, to)
                .stream().map(charged -> new DaySet.Range(charged.getFromDate(), charged.getToDate())).toList());
        if (left.equals(List.of(new DaySet.Range(from, to)))) {
            return;
        }
        item.close(Status.RESOLVED, now);
        for (DaySet.Range days : left) {
            if (items.findOpen(NotChargedItemRecord.lossesNodeKey(node, days.from(), days.to())).isEmpty()) {
                items.save(NotChargedItemRecord.lossesNode(tenantId, node, Objects.requireNonNull(
                        item.getChargeTypeId()), days.from(), days.to(), now));
            }
        }
    }

    // ---- a recalculation of a booked run (feature 012)

    /**
     * The run as 011 would book it today: the plan of its range over the nodes it charged, priced with
     * {@code snapshot}, with the run itself ignored so it does not overlap itself ({@code ignoreRunIds}); a node that is
     * no longer a node of the range is left out (its plots then have no expected charge from it). The nodes the run did
     * not charge (unknown, skipped, negative) stay with their "not charged" items and the retry.
     *
     * @param net the network of the run's range, locked by the caller ({@link MeterNetwork#lockSharedNetwork})
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Planned planAgain(LossesRunRecord run, Network net, Snapshot snapshot) {
        List<UUID> charged = nodes.findByRunIdIn(List.of(run.getId())).stream()
                .filter(node -> node.getOutcome() == Outcome.CHARGED)
                .sorted(Comparator.comparing(LossesRunNodeRecord::getPosition))
                .map(LossesRunNodeRecord::getNodeMeterId).toList();
        if (charged.isEmpty()) {
            return new Planned(new Plan(run.getFromDate(), run.getToDate(), List.of(), List.of()), Map.of());
        }
        Planned planned = plan(net, run.getFromDate(), run.getToDate(), charged, snapshot, Set.of(run.getId()), true);
        if (planned.plan().nodes().isEmpty()) {
            return new Planned(new Plan(run.getFromDate(), run.getToDate(), List.of(), List.of()), Map.of());
        }
        return planned;
    }

    /**
     * Writes one plot's charge of the run {@code runId} booked by a recalculation without booking the run again (a plot
     * new to a run of a closed month): the planned charge under {@code sourceKey} in the billing month, its parts and
     * the values it reads ({@link #writePartsAndUse}). Flushes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    ChargeRecord bookLine(UUID tenantId, UUID runId, Plan plan, PlannedCharge charge, UUID chargeTypeId,
            UUID billingMonthId, String sourceKey, UUID recalculationId, @Nullable UUID createdBy, Instant now) {
        ChargeRecord record = ChargeRecord.lossesRun(tenantId, charge.plotId(), chargeTypeId, billingMonthId,
                charge.tariff().id(), runId, charge.amount(), plan.from(), plan.to(), sourceKey,
                charge.calculation(runId), recalculationId, createdBy, now);
        entityManager.persist(record);
        entityManager.flush();
        writePartsAndUse(tenantId, record.getId(), plan, charge);
        return record;
    }

    /**
     * Writes the parts of the planned charge and the values of the nodes it shares in ({@code meter_value_use} rows of
     * the charge) for {@code chargeId}: a charge or an adjustment of a run booked by a recalculation, whose parts are
     * those of the expected charge. Flushes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void writePartsAndUse(UUID tenantId, UUID chargeId, Plan plan, PlannedCharge charge) {
        writeParts(tenantId, chargeId, charge);
        entityManager.flush();
        Set<UUID> nodeKeys = charge.parts().stream().map(NodePart::nodeKey).collect(Collectors.toSet());
        Set<ValueUse> uses = new LinkedHashSet<>();
        plan.nodes().stream().filter(node -> node.charges() && nodeKeys.contains(node.chain().key()))
                .forEach(node -> uses.addAll(node.result().uses()));
        writeChargeUses(entityManager, tenantId, chargeId, uses);
    }

    /** One {@code meter_value_use} row of the charge per value, in one statement. */
    static void writeChargeUses(EntityManager entityManager, UUID tenantId, UUID chargeId, Set<ValueUse> uses) {
        if (uses.isEmpty()) {
            return;
        }
        String meterIds = uses.stream().map(use -> use.meterId().toString()).collect(Collectors.joining(","));
        String dates = uses.stream().map(use -> use.on().toString()).collect(Collectors.joining(","));
        entityManager.createNativeQuery("insert into meter_value_use (tenant_id, meter_id, used_on, charge_id) "
                + "select :tenant, u.meter_id, u.used_on, :charge from unnest(cast(string_to_array(:meters, ',') "
                + "as uuid[]), cast(string_to_array(:dates, ',') as date[])) as u(meter_id, used_on)")
                .setParameter("tenant", tenantId)
                .setParameter("charge", chargeId)
                .setParameter("meters", meterIds)
                .setParameter("dates", dates)
                .executeUpdate();
    }

    // ---- the history

    @Transactional(readOnly = true)
    List<LossesRunSummary> list(AccessProfile actor) {
        List<LossesRunRecord> all = runs.findAllNewestFirst();
        if (all.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = all.stream().map(LossesRunRecord::getId).toList();
        Map<UUID, List<LossesRunNodeRecord>> nodesByRun = nodes.findByRunIdIn(ids).stream()
                .collect(Collectors.groupingBy(LossesRunNodeRecord::getRunId));
        Map<UUID, Integer> counts = new HashMap<>();
        for (Object[] row : charges.countByRun(ids, ChargeKind.REGULAR)) {
            counts.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        Map<UUID, BillingMonthRecord> monthById = months.findAll().stream()
                .collect(Collectors.toMap(BillingMonthRecord::getId, Function.identity()));
        Lookups lookups = new Lookups(Map.of(), Map.of(), names(all, actor));
        return all.stream().map(run -> LossesRunViews.summary(run, nodesByRun.getOrDefault(run.getId(), List.of()),
                counts.getOrDefault(run.getId(), 0), monthById.get(run.getBillingMonthId()), actor, lookups))
                .toList();
    }

    @Transactional(readOnly = true)
    LossesRunDetails details(UUID id, AccessProfile actor) {
        LossesRunRecord run = runs.findById(id).orElseThrow(ApiProblemException::notFound);
        return details(run, actor);
    }

    private LossesRunDetails details(LossesRunRecord run, AccessProfile actor) {
        List<LossesRunNodeRecord> runNodes = nodes.findByRunIdIn(List.of(run.getId()));
        List<ChargeRecord> runCharges = charges.findByRunIdAndKind(run.getId(), ChargeKind.REGULAR);
        List<UUID> chargeIds = runCharges.stream().map(ChargeRecord::getId).toList();
        Set<UUID> reversed = chargeIds.isEmpty() ? Set.of() : new HashSet<>(charges.reversedAmong(chargeIds));
        Map<UUID, Quantity> kwh = new HashMap<>();
        if (!chargeIds.isEmpty()) {
            for (ChargePartRecord part : parts.findByChargeIdIn(chargeIds)) {
                kwh.merge(part.getChargeId(), Quantity.ofFraction(part.getUnitsNum().toBigIntegerExact(),
                        part.getUnitsDen().toBigIntegerExact()), Quantity::plus);
            }
        }
        List<StoredCharge> stored = runCharges.stream().map(c -> new StoredCharge(c.getId(), c.getPlotId(),
                kwh.getOrDefault(c.getId(), Quantity.ZERO), c.getAmount(), reversed.contains(c.getId()))).toList();
        Set<UUID> plotIds = new HashSet<>();
        runCharges.forEach(c -> plotIds.add(c.getPlotId()));
        for (LossesRunNodeRecord node : runNodes) {
            node.getPlots().forEach(plot -> plotIds.add(UUID.fromString(plot.get("plotId").toString())));
            Map<String, Object> warning = node.getWarning();
            if (warning != null && warning.get("plotId") != null) {
                plotIds.add(UUID.fromString(warning.get("plotId").toString()));
            }
        }
        BillingMonthRecord month = months.findById(run.getBillingMonthId()).orElseThrow();
        return LossesRunViews.details(run, runNodes, stored, month, actor,
                new Lookups(numbers(plotIds), rank(plotIds), names(List.of(run), actor)));
    }

    // ---- reversal

    @Transactional
    LossesRunDetails reverse(UUID id, RunReversal input, AccessProfile actor) {
        String reason = input.getReason() == null ? "" : input.getReason().strip();
        if (reason.isEmpty()) {
            throw invalid("reason", ProblemCodes.REQUIRED);
        }
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        lockRuns(tenantId);
        LossesRunRecord run = runs.findByIdForUpdate(id).orElseThrow(ApiProblemException::notFound);
        if (run.getStatus() == LossesRunRecord.Status.REVERSED) {
            throw conflict(ProblemCodes.RUN_ALREADY_REVERSED, "The run has already been reversed.");
        }
        BillingMonthRecord month = months.findByIdForShare(run.getBillingMonthId()).orElseThrow();
        if (month.isClosed()) {
            throw conflict(ProblemCodes.RUN_IN_CLOSED_MONTH,
                    "The charges of the run are booked in a closed month; they are corrected by an adjustment.");
        }
        Instant now = clock.instant();
        BillingMonthRecord booking = billingMonths.openForBooking(tenantId, month.yearMonth().atDay(1));
        String label = run.label();
        String runFrom = run.getFromDate().toString();
        String runTo = run.getToDate().toString();
        Reversed reversed = reverseLocked(run, booking.getId(), reason, ChargeSource.MANUAL_REVERSAL, null,
                actor.memberId(), now);
        audit.publishLossesRun(actor, LedgerAuditPublisher.Action.LOSSES_RUN_REVERSED, id, label, List.of(
                new FieldChange("from", null, runFrom),
                new FieldChange("to", null, runTo),
                new FieldChange("count", null, Integer.toString(reversed.reversalOf().size())),
                new FieldChange("total", null, reversed.total().toString()),
                new FieldChange("reason", null, reason)));
        if (!reversed.plotIds().isEmpty()) {
            writer.publishBooked(tenantId, reversed.plotIds());
        }
        return details(runs.findById(id).orElseThrow(), actor);
    }

    /**
     * What a reversal of a run wrote.
     *
     * @param reversalOf the reversal of each charge reversed, by the charge's id
     * @param total      the sum of the reversals
     * @param plotIds    the plots of the charges reversed
     */
    record Reversed(Map<UUID, UUID> reversalOf, Money total, Set<UUID> plotIds) {
    }

    /**
     * Reverses every charge of the booked run that is not reversed yet in {@code billingMonthId} (the run's month, open)
     * with the reason: source {@code MANUAL_REVERSAL} for a reversal by hand, {@code RECALCULATION} with the
     * recalculation's id for a recalculation (feature 012); then marks the run and its nodes reversed, which frees their
     * days for another run. The caller holds the run's locks and the run {@code FOR UPDATE}, and has checked that it is
     * booked and its month open. Clears the persistence context (the nodes are marked by a statement): entities read
     * before are detached afterwards. 409 {@code CHARGE_ALREADY_REVERSED} when a charge was reversed meanwhile.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Reversed reverseLocked(LossesRunRecord run, UUID billingMonthId, String reason, ChargeSource source,
            @Nullable UUID recalculationId, @Nullable UUID by, Instant now) {
        UUID id = run.getId();
        List<ChargeRecord> runCharges = charges.findByRunIdAndKind(id, ChargeKind.REGULAR);
        Set<UUID> reversed = runCharges.isEmpty() ? Set.of()
                : new HashSet<>(charges.reversedAmong(runCharges.stream().map(ChargeRecord::getId).toList()));
        Map<UUID, UUID> reversalOf = new LinkedHashMap<>();
        Money total = Money.ZERO;
        Set<UUID> plotIds = new HashSet<>();
        for (ChargeRecord charge : runCharges) {
            if (reversed.contains(charge.getId())) {
                continue;
            }
            ChargeRecord reversal = ChargeRecord.reversalOf(charge, billingMonthId, source, recalculationId, reason,
                    ChargeViews.plainCalculation(charge.getAmount().negate()), by, now);
            entityManager.persist(reversal);
            reversalOf.put(charge.getId(), reversal.getId());
            total = total.plus(reversal.getAmount());
            plotIds.add(charge.getPlotId());
        }
        try {
            entityManager.flush();
        } catch (PersistenceException e) {
            if (ConstraintViolations.violates(e, REVERSED_ONCE_INDEX)) {
                throw conflict(ProblemCodes.CHARGE_ALREADY_REVERSED, "A charge of the run has just been reversed.");
            }
            throw e;
        }
        run.reverse(by, reason, now);
        nodes.markReversed(id); // flushes the run first, then clears the persistence context
        return new Reversed(reversalOf, total, plotIds);
    }

    // ---- the retry of a losses node item

    /** The status of a retried item, the run it booked (if any) and the node's name (for the audit label). */
    record Retried(Status status, @Nullable UUID runId, @Nullable String nodeName) {
    }

    /**
     * Retries an open {@code LOSSES_NODE} item: a run of its node alone over its range, booked as {@link #run} books
     * one when the node is charged now. Under the locks of a booking (the item {@code FOR UPDATE} after the queue lock).
     * The item is {@code RESOLVED} when the node is charged now or was charged by other runs meanwhile (for some of its
     * days only: narrowed to the days left, see {@link #settle}), {@code OBSOLETE}
     * when nothing is left to charge (the losses are negative, the node is not in the tree for the range any more, or
     * the losses mode is not proportional), and stays open while a value is still unknown.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Retried retry(NotChargedItemRecord item, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        LocalDate from = Objects.requireNonNull(item.getFromDate());
        LocalDate to = Objects.requireNonNull(item.getToDate());
        UUID node = Objects.requireNonNull(item.getMeterId());
        lockRuns(tenantId);
        Network locked = network.lockSharedNetwork(from, to);
        writer.lockQueue(tenantId);
        entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        String name = locked.chains().stream().filter(chain -> chain.key().equals(node)).map(NetworkChain::name)
                .filter(Objects::nonNull).findFirst().orElse(null);
        if (item.getStatus() != Status.OPEN) {
            return new Retried(Status.RESOLVED, null, name);
        }
        Instant now = clock.instant();
        Snapshot snapshot = writer.lockTariffs();
        Planned planned = null;
        if (settings.lossesMode() == LossesMode.PROPORTIONAL) {
            try {
                planned = plan(locked, from, to, List.of(node), snapshot, Set.of(), false);
            } catch (ApiProblemException notANode) {
                planned = null; // the node had no meter under it in the range any more
            }
        }
        if (planned == null) {
            return closed(item, Status.OBSOLETE, now, name);
        }
        PlannedNode only = planned.plan().nodes().getFirst();
        if (only.overlap() != null) {
            // charged by another run meanwhile: resolved, or narrowed to the days left
            settle(tenantId, item, now);
            items.flush();
            return new Retried(item.getStatus(), null, name);
        }
        Outcome outcome = only.result().outcome();
        if (outcome == Outcome.NEGATIVE) {
            return closed(item, Status.OBSOLETE, now, name);
        }
        if (outcome != Outcome.CHARGED) {
            return new Retried(Status.OPEN, null, name);
        }
        Booked booked = bookAndAudit(tenantId, planned, snapshot, actor, now);
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

    private void requireProportional() {
        if (settings.lossesMode() != LossesMode.PROPORTIONAL) {
            throw conflict(ProblemCodes.LOSSES_MODE_NOT_PROPORTIONAL,
                    "Losses are charged as a fixed percentage; proportional runs are off.");
        }
    }

    /**
     * The cooperative's charging lock ({@link ChargingLock}), then the advisory lock of its losses runs. A
     * recalculation that covers runs takes it after the charging lock and the plot meters and before the shared
     * network ({@link MeterNetwork#lockSharedNetwork}) and the queue lock, as the booking of a run does.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void lockRuns(UUID tenantId) {
        // every booking, reversal and retry books or reverses charges: the cooperative's charging lock comes first
        chargingLock.lock(tenantId);
        entityManager.createNativeQuery(
                "select count(*) from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) as l")
                .setParameter("key", RUN_LOCK + tenantId)
                .getSingleResult();
    }

    /** The plot numbers and their order for a plan: the plots charged, shared among, and of an unknown plot meter. */
    private Lookups lookups(Planned planned) {
        Set<UUID> plotIds = new HashSet<>();
        planned.plan().charges().forEach(charge -> plotIds.add(charge.plotId()));
        for (PlannedNode node : planned.plan().nodes()) {
            node.shares().forEach(share -> plotIds.add(share.plotId()));
            if (node.result().unknown() != null) {
                NetworkChain unknown = planned.chains().get(node.result().unknown().chainKey());
                if (unknown != null && unknown.plotId() != null) {
                    plotIds.add(unknown.plotId());
                }
            }
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
    private Map<UUID, String> names(List<LossesRunRecord> list, AccessProfile actor) {
        if (!actor.can(Permission.CHARGES_VIEW)) {
            return Map.of();
        }
        Set<UUID> members = new HashSet<>();
        for (LossesRunRecord run : list) {
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
        return conflict(ProblemCodes.LOSSES_RANGE_OVERLAP,
                "Losses for these days have already been calculated for a node of the run.");
    }

    private static ApiProblemException conflict(String code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }
}
