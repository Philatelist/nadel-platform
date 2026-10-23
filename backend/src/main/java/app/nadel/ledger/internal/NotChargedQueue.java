package app.nadel.ledger.internal;

import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.ledger.internal.NotChargedItemRecord.Kind;
import app.nadel.ledger.internal.NotChargedItemRecord.Status;
import app.nadel.ledger.internal.NotChargedRetries.Attempt;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import app.nadel.tariffs.TariffBook;
import app.nadel.tariffs.TariffBook.Snapshot;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.NotChargedItem;
import nadel.contract.model.NotChargedRetryResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The "not charged" queue (feature 011 §2.7): {@code GET /not-charged} lists the open items, {@code POST
 * /not-charged/{id}/retry} tries one again. A retry of any kind takes the cooperative's charging lock
 * ({@link ChargingLock}) first.
 * <p>
 * A retry of a {@code TARIFF_MONTH} item does for its plot, charge type and month what the month run does, on the
 * same path as the run's retries ({@link NotChargedRetries}, under the same locks: the month's advisory lock, the
 * queue lock, the item {@code FOR UPDATE}, the charge types and the tariffs {@code FOR SHARE}). A new charge is booked
 * in the month, or, when the month was closed meanwhile, in the earliest open month after it
 * ({@link BillingMonths#openForBooking}), keeping its own service period and date; the item is then {@code RESOLVED},
 * also when the charge exists already. Nothing left to charge (the plot left the scopes, the amount is 0.00, the plot
 * or the type is no longer charged) makes it {@code OBSOLETE}. A conflict that remains keeps it open with the tariffs
 * of the retry. An item that is not open answers resolved and changes nothing. A reading interval
 * ({@code INTERVAL}) is retried by {@link IntervalCharging#retry}, the losses of a node ({@code LOSSES_NODE}) by
 * {@link LossesRuns#retry}, a common consumer ({@code COMMON_CONSUMER}) by {@link CommonConsumerRuns#retry}. Audit
 * {@code NOT_CHARGED_RETRIED} (entity type {@code CHARGE}, about the plot; for a losses node or a common consumer
 * {@code METER}, about the meter) with the kind, the reason, the charge type, the month and the outcome.
 */
@Service
class NotChargedQueue {

    private final NotChargedItemRepository items;
    private final NotChargedViews views;
    private final TariffBook book;
    private final TariffChargeWriter writer;
    private final NotChargedRetries retries;
    private final ChargeRepository charges;
    private final PlotCatalog plots;
    private final ChargeTypeCatalog chargeTypes;
    private final LedgerAuditPublisher audit;
    private final IntervalCharging intervals;
    private final LossesRuns lossesRuns;
    private final CommonConsumerRuns commonConsumerRuns;
    private final WaterDays waterDays;
    private final ChargingLock chargingLock;
    private final EntityManager entityManager;
    private final Clock clock;

    NotChargedQueue(NotChargedItemRepository items, NotChargedViews views, TariffBook book, TariffChargeWriter writer,
            NotChargedRetries retries, ChargeRepository charges, PlotCatalog plots, ChargeTypeCatalog chargeTypes,
            LedgerAuditPublisher audit, IntervalCharging intervals, LossesRuns lossesRuns,
            CommonConsumerRuns commonConsumerRuns, WaterDays waterDays, ChargingLock chargingLock,
            EntityManager entityManager, Clock clock) {
        this.items = items;
        this.views = views;
        this.book = book;
        this.writer = writer;
        this.retries = retries;
        this.charges = charges;
        this.plots = plots;
        this.chargeTypes = chargeTypes;
        this.audit = audit;
        this.intervals = intervals;
        this.lossesRuns = lossesRuns;
        this.commonConsumerRuns = commonConsumerRuns;
        this.waterDays = waterDays;
        this.chargingLock = chargingLock;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** The open items: newest month first, then in the natural order of the plot numbers, then by charge type. */
    @Transactional(readOnly = true)
    List<NotChargedItem> list() {
        List<NotChargedItemRecord> open = items.findByStatus(Status.OPEN);
        if (open.isEmpty()) {
            return List.of();
        }
        Set<UUID> plotIds = open.stream().map(NotChargedItemRecord::getPlotId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, Integer> plotRank = rank(plots.inNaturalOrder(plotIds));
        Map<UUID, Integer> typeRank = rank(chargeTypes.all().stream().map(ChargeTypeFacts::id).toList());
        NotChargedViews.Context context = views.context(plotIds, book.snapshot());
        return open.stream()
                .sorted(Comparator.comparing(NotChargedQueue::day, Comparator.reverseOrder())
                        .thenComparing(item -> rankOf(plotRank, item.getPlotId()))
                        .thenComparing(item -> rankOf(typeRank, item.getChargeTypeId()))
                        .thenComparing(NotChargedItemRecord::getItemKey))
                .map(item -> views.view(item, context))
                .toList();
    }

    @Transactional
    NotChargedRetryResult retry(UUID id, AccessProfile actor) {
        NotChargedItemRecord item = items.findById(id).orElseThrow(ApiProblemException::notFound);
        if (item.getStatus() != Status.OPEN) {
            return new NotChargedRetryResult(true);
        }
        // every kind of retry books charges: the cooperative's charging lock first, before any other ledger lock
        chargingLock.lock(Objects.requireNonNull(actor.tenantId()));
        if (item.getKind() == Kind.INTERVAL) {
            return retryInterval(item, actor);
        }
        if (item.getKind() == Kind.LOSSES_NODE) {
            return retryLossesNode(item, actor);
        }
        if (item.getKind() == Kind.COMMON_CONSUMER) {
            return retryCommonConsumer(item, actor);
        }
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        YearMonth month = Objects.requireNonNull(item.getMonth());
        UUID plotId = Objects.requireNonNull(item.getPlotId());
        UUID chargeTypeId = Objects.requireNonNull(item.getChargeTypeId());
        writer.lockMonth(tenantId, month);
        writer.lockQueue(tenantId);
        entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        if (item.getStatus() != Status.OPEN) {
            return new NotChargedRetryResult(true);
        }
        Snapshot snapshot = writer.lockTariffs();
        Optional<PlotFacts> plot = plots.find(plotId);
        Attempt attempt = NotChargedRetries.plan(snapshot, List.of(item),
                plot.map(p -> Map.of(p.id(), p)).orElse(Map.of()),
                waterDays.forMonths(snapshot, List.of(plotId), List.of(month)),
                key -> !charges.existingSourceKeys(List.of(key)).isEmpty()).getFirst();
        Instant now = clock.instant();
        if (!retries.apply(tenantId, List.of(attempt), actor.memberId(), now).isEmpty()) {
            writer.publishBooked(tenantId, Set.of(plotId));
        }
        Status outcomeStatus = attempt.status();

        List<FieldChange> changes = new ArrayList<>(List.of(
                new FieldChange("kind", null, item.getKind().name()),
                new FieldChange("reason", null, item.getReason().name())));
        chargeTypes.find(chargeTypeId)
                .ifPresent(type -> changes.add(new FieldChange("chargeType", null, type.auditName())));
        changes.add(new FieldChange("month", null, month.toString()));
        changes.add(new FieldChange("outcome", null, outcomeStatus.name()));
        audit.publish(actor, LedgerAuditPublisher.Action.NOT_CHARGED_RETRIED, plotId,
                plot.map(PlotFacts::number).orElse(null), changes);

        return outcomeStatus == Status.OPEN ? unresolved(item, snapshot) : new NotChargedRetryResult(true);
    }

    /**
     * Retries a reading interval ({@link IntervalCharging#retry}: the meter, the item and the tariffs locked, the
     * interval ending at the item's point charged as the listener would). Audit {@code NOT_CHARGED_RETRIED} with the
     * kind, the reason, the charge type, the days of the interval and the outcome.
     */
    private NotChargedRetryResult retryInterval(NotChargedItemRecord item, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        Status outcomeStatus = intervals.retry(tenantId, item, actor.memberId(), clock.instant());
        UUID plotId = item.getPlotId();
        List<FieldChange> changes = new ArrayList<>(List.of(
                new FieldChange("kind", null, item.getKind().name()),
                new FieldChange("reason", null, item.getReason().name())));
        if (item.getChargeTypeId() != null) {
            chargeTypes.find(item.getChargeTypeId())
                    .ifPresent(type -> changes.add(new FieldChange("chargeType", null, type.auditName())));
        }
        if (item.getFromDate() != null) {
            changes.add(new FieldChange("from", null, item.getFromDate().toString()));
        }
        if (item.getToDate() != null) {
            changes.add(new FieldChange("to", null, item.getToDate().toString()));
        }
        changes.add(new FieldChange("outcome", null, outcomeStatus.name()));
        if (plotId != null) {
            audit.publish(actor, LedgerAuditPublisher.Action.NOT_CHARGED_RETRIED, plotId,
                    plots.find(plotId).map(PlotFacts::number).orElse(null), changes);
        }
        return outcomeStatus == Status.OPEN ? unresolved(item) : new NotChargedRetryResult(true);
    }

    /**
     * Retries the losses of a node ({@link LossesRuns#retry}: a run of that node alone over the item's range, booked
     * when the node is charged now). Audit {@code NOT_CHARGED_RETRIED} about the node meter (entity type {@code METER})
     * with the kind, the reason, the range, the outcome and the run booked; the booking itself writes
     * {@code LOSSES_RUN_BOOKED}.
     */
    private NotChargedRetryResult retryLossesNode(NotChargedItemRecord item, AccessProfile actor) {
        LossesRuns.Retried retried = lossesRuns.retry(item, actor);
        List<FieldChange> changes = new ArrayList<>(List.of(
                new FieldChange("kind", null, item.getKind().name()),
                new FieldChange("reason", null, item.getReason().name()),
                new FieldChange("from", null, String.valueOf(item.getFromDate())),
                new FieldChange("to", null, String.valueOf(item.getToDate())),
                new FieldChange("outcome", null, retried.status().name())));
        if (retried.runId() != null) {
            changes.add(new FieldChange("runId", null, retried.runId().toString()));
        }
        audit.publishMeter(actor, LedgerAuditPublisher.Action.NOT_CHARGED_RETRIED,
                Objects.requireNonNull(item.getMeterId()), retried.nodeName(), changes);
        return retried.status() == Status.OPEN ? unresolved(item) : new NotChargedRetryResult(true);
    }

    /**
     * Retries a common consumer ({@link CommonConsumerRuns#retry}: a run of that consumer alone over the item's range,
     * booked when the consumer is charged now). Audit {@code NOT_CHARGED_RETRIED} about the consumer meter (entity type
     * {@code METER}) with the kind, the reason, the range, the outcome and the run booked; the booking itself writes
     * {@code COMMON_CONSUMER_RUN_BOOKED}.
     */
    private NotChargedRetryResult retryCommonConsumer(NotChargedItemRecord item, AccessProfile actor) {
        CommonConsumerRuns.Retried retried = commonConsumerRuns.retry(item, actor);
        List<FieldChange> changes = new ArrayList<>(List.of(
                new FieldChange("kind", null, item.getKind().name()),
                new FieldChange("reason", null, item.getReason().name()),
                new FieldChange("from", null, String.valueOf(item.getFromDate())),
                new FieldChange("to", null, String.valueOf(item.getToDate())),
                new FieldChange("outcome", null, retried.status().name())));
        if (retried.runId() != null) {
            changes.add(new FieldChange("runId", null, retried.runId().toString()));
        }
        audit.publishMeter(actor, LedgerAuditPublisher.Action.NOT_CHARGED_RETRIED,
                Objects.requireNonNull(item.getMeterId()), retried.consumerName(), changes);
        return retried.status() == Status.OPEN ? unresolved(item) : new NotChargedRetryResult(true);
    }

    private NotChargedRetryResult unresolved(NotChargedItemRecord item) {
        return unresolved(item, book.snapshot());
    }

    private NotChargedRetryResult unresolved(NotChargedItemRecord item, Snapshot snapshot) {
        Set<UUID> plotIds = item.getPlotId() == null ? Set.of() : Set.of(item.getPlotId());
        NotChargedRetryResult result = new NotChargedRetryResult(false);
        result.setItem(views.view(item, views.context(plotIds, snapshot)));
        return result;
    }

    /** The day an item is about, for the order of the list: its month, else the last day of its range. */
    private static LocalDate day(NotChargedItemRecord item) {
        if (item.getMonth() != null) {
            return item.getMonth().atDay(1);
        }
        return item.getToDate() == null ? LocalDate.MIN : item.getToDate();
    }

    private static Map<UUID, Integer> rank(List<UUID> ordered) {
        Map<UUID, Integer> rank = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            rank.put(ordered.get(i), i);
        }
        return rank;
    }

    private static int rankOf(Map<UUID, Integer> rank, UUID id) {
        return id == null ? Integer.MAX_VALUE : rank.getOrDefault(id, Integer.MAX_VALUE);
    }
}
