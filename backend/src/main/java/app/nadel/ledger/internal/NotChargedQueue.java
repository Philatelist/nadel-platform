package app.nadel.ledger.internal;

import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.ledger.internal.NotChargedItemRecord.Kind;
import app.nadel.ledger.internal.NotChargedItemRecord.Status;
import app.nadel.ledger.internal.TariffMonthPlanning.AlreadyCharged;
import app.nadel.ledger.internal.TariffMonthPlanning.Conflicting;
import app.nadel.ledger.internal.TariffMonthPlanning.NewCharge;
import app.nadel.ledger.internal.TariffMonthPlanning.Outcome;
import app.nadel.ledger.internal.TariffMonthPlanning.PlannedCharge;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import app.nadel.tariffs.TariffBook;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
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
 * /not-charged/{id}/retry} tries one again.
 * <p>
 * A retry of a {@code TARIFF_MONTH} item does for its plot, charge type and month what the month run does
 * ({@link TariffMonthPlanning}, under the same locks: the month's advisory lock, the charge type and the tariffs
 * {@code FOR SHARE}, the item {@code FOR UPDATE}). A new charge is booked in the month, or, when the month was closed
 * meanwhile, in the earliest open month after it ({@link BillingMonths#openForBooking}), keeping its own service
 * period and date; the item is then {@code RESOLVED}, also when the charge exists already. Nothing left to charge (the
 * plot left the scopes, the amount is 0.00, the plot or the type is no longer charged) makes it {@code OBSOLETE}. A
 * conflict that remains keeps it open with the tariffs of the retry. An item that is not open answers resolved and
 * changes nothing. Audit {@code NOT_CHARGED_RETRIED} (entity type {@code CHARGE}, about the plot) with the kind, the
 * reason, the charge type, the month and the outcome.
 */
@Service
class NotChargedQueue {

    private final NotChargedItemRepository items;
    private final NotChargedViews views;
    private final TariffBook book;
    private final TariffChargeWriter writer;
    private final BillingMonths billingMonths;
    private final ChargeRepository charges;
    private final PlotCatalog plots;
    private final ChargeTypeCatalog chargeTypes;
    private final LedgerAuditPublisher audit;
    private final EntityManager entityManager;
    private final Clock clock;

    NotChargedQueue(NotChargedItemRepository items, NotChargedViews views, TariffBook book, TariffChargeWriter writer,
            BillingMonths billingMonths, ChargeRepository charges, PlotCatalog plots, ChargeTypeCatalog chargeTypes,
            LedgerAuditPublisher audit, EntityManager entityManager, Clock clock) {
        this.items = items;
        this.views = views;
        this.book = book;
        this.writer = writer;
        this.billingMonths = billingMonths;
        this.charges = charges;
        this.plots = plots;
        this.chargeTypes = chargeTypes;
        this.audit = audit;
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
        if (item.getKind() != Kind.TARIFF_MONTH) {
            // reading intervals, losses nodes and common consumers are retried by their own slices (011 slices 4-7)
            return unresolved(item);
        }
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        YearMonth month = Objects.requireNonNull(item.getMonth());
        UUID plotId = Objects.requireNonNull(item.getPlotId());
        UUID chargeTypeId = Objects.requireNonNull(item.getChargeTypeId());
        writer.lockMonth(tenantId, month);
        entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        if (item.getStatus() != Status.OPEN) {
            return new NotChargedRetryResult(true);
        }
        Snapshot snapshot = writer.lockTariffs(List.of(chargeTypeId));
        Optional<PlotFacts> plot = plots.find(plotId);
        Outcome outcome = plot.map(p -> TariffMonthPlanning.plan(snapshot, chargeTypeId, MonthRun.facts(p), month,
                MonthRun.applicableDays(chargeTypeId, plotId, month),
                key -> !charges.existingSourceKeys(List.of(key)).isEmpty()))
                .orElse(new TariffMonthPlanning.Nothing());

        Instant now = clock.instant();
        Status outcomeStatus;
        if (outcome instanceof NewCharge newCharge) {
            PlannedCharge planned = newCharge.charge();
            BillingMonthRecord booking = billingMonths.openForBooking(tenantId, month.atDay(1));
            writer.book(tenantId, List.of(planned), booking.getId(), actor.memberId(), now);
            writer.publishBooked(tenantId, Set.of(plotId));
            outcomeStatus = Status.RESOLVED;
        } else if (outcome instanceof AlreadyCharged) {
            outcomeStatus = Status.RESOLVED;
        } else if (outcome instanceof Conflicting conflicting) {
            item.failedAgain(NotChargedItemRecord.Reason.CONFLICTING_TARIFFS,
                    conflicting.tariffs().stream().map(TariffEntry::id).toList(), now);
            outcomeStatus = Status.OPEN;
        } else {
            outcomeStatus = Status.OBSOLETE;
        }
        if (outcomeStatus != Status.OPEN) {
            item.close(outcomeStatus, now);
        }
        items.flush();

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
