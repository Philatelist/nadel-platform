package app.nadel.ledger.internal;

import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.ledger.PaymentsChanged;
import app.nadel.ledger.internal.AllocationPlanner.Line;
import app.nadel.ledger.internal.AllocationPlanner.Paired;
import app.nadel.ledger.internal.ReleasePlanner.Release;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.AllocationLinesRequest;
import nadel.contract.model.PaymentCurrentAllocation;
import nadel.contract.model.PaymentPartProposal;
import nadel.contract.model.PaymentReallocationResult;
import nadel.contract.model.PaymentReversal;
import nadel.contract.model.PrepaymentAllocationResult;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrects the money of saved payments (feature 015 §2.5, §2.6, technical considerations 2.3-2.6): reverses a payment
 * ({@code POST /payments/{id}/reverse}), reallocates it ({@code POST /payments/{id}/reallocate?preview=}) and places a
 * plot's prepayment on its open charges by hand ({@code POST /plots/{id}/prepayment/allocate?preview=}). Nothing is
 * changed or deleted: a reversal is a {@code PAYMENT_REVERSAL} row, money is taken back by release rows
 * ({@link AllocationReleaser}) and placed again by new allocation rows.
 * <p>
 * A write takes the locks in the ledger's order (technical considerations 2.4): the plot {@code FOR SHARE}, the plot
 * ledger lock ({@link PlotLedgerLock}), then (a reversal) the billing month {@code FOR SHARE}; the ledger of the plot
 * is read under the plot ledger lock. A preview takes no lock and writes nothing. Each write publishes its audit entry
 * (entity type {@code PAYMENT} about the plot) and {@link PaymentsChanged} for the plot status. Reversal reasons are
 * user content: they go to the ledger and the audit entry, never to a log; logs carry ids and counts only.
 * <ul>
 * <li><b>Reverse</b>: a reason (400 {@code VALIDATION_FAILED} {@code reason} {@code REQUIRED}/{@code TOO_LONG}); the
 * payment of the cooperative (404 {@code NOT_FOUND}); not a reversal row and not reversed before (409
 * {@code PAYMENT_ALREADY_REVERSED}; two reversals at the same time meet at {@code payment_reversed_once_uq}, the same
 * 409). Allowed on an archived plot. Booked in {@code BillingMonths.openForBooking(received_on)}: the payment's own
 * month while it is open, otherwise the earliest open month. Every live allocation of the payment is released
 * ({@code PAYMENT_REVERSED}) before the reversal row; the other parts of a split group are untouched. The receipt
 * number stays on the payment. Audit {@code PAYMENT_REVERSED}.</li>
 * <li><b>Reallocate</b>: the payment (404; 409 {@code PAYMENT_ALREADY_REVERSED} for a reversed payment or a reversal
 * row); the plot's state as if every live allocation of the payment were released; the digest
 * ({@link PaymentDigest#reallocation}, 409 {@code PREVIEW_OUTDATED}); the proposal or the caller's lines checked
 * against that state and the payment's amount, as at entry; a save releases ({@code REALLOCATED}) and writes the new
 * lines (origin {@code REALLOCATION}). Audit {@code PAYMENT_REALLOCATED} with the old and new lines.</li>
 * <li><b>Allocate prepayment</b>: the plot of the cooperative (404 {@code PLOT_NOT_FOUND}; archived plots and common
 * land too: the money is already there); an empty list of lines is 400 {@code lines} {@code REQUIRED}; no prepayment or
 * no open charge is 409 {@code NOTHING_TO_ALLOCATE}; the digest ({@link PaymentDigest#prepayment}); the proposal or the
 * caller's lines checked against the prepayment (400 {@code ALLOCATION_EXCEEDS_PAYMENT} when they exceed it); each
 * line's money taken from the payments with money left, oldest first ({@link AllocationPlanner#pairWithPayments}),
 * origin {@code PREPAYMENT_MANUAL}, by the actor. Works whether automatic allocation is on or off. Audit
 * {@code PREPAYMENT_ALLOCATED}.</li>
 * </ul>
 */
@Service
class PaymentCorrections {

    private static final Logger log = LoggerFactory.getLogger(PaymentCorrections.class);
    private static final String REVERSED_ONCE_INDEX = "payment_reversed_once_uq";

    private final PaymentRepository payments;
    private final PlotCatalog plots;
    private final PlotLedgerLock plotLocks;
    private final PlotLedgerQueries ledgers;
    private final BillingMonths billingMonths;
    private final AllocationReleaser releaser;
    private final PaymentViews views;
    private final LedgerAuditPublisher audit;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final Clock clock;

    PaymentCorrections(PaymentRepository payments, PlotCatalog plots, PlotLedgerLock plotLocks,
            PlotLedgerQueries ledgers, BillingMonths billingMonths, AllocationReleaser releaser, PaymentViews views,
            LedgerAuditPublisher audit, ApplicationEventPublisher events, EntityManager entityManager, Clock clock) {
        this.payments = payments;
        this.plots = plots;
        this.plotLocks = plotLocks;
        this.ledgers = ledgers;
        this.billingMonths = billingMonths;
        this.releaser = releaser;
        this.views = views;
        this.audit = audit;
        this.events = events;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** Reverses the payment with the reason; the caller reads the card after the commit. */
    @Transactional
    void reverse(UUID paymentId, PaymentReversal input, AccessProfile actor) {
        String reasonProblem = PaymentRules.reasonProblem(input.getReason());
        if (reasonProblem != null) {
            throw PaymentService.invalid(List.of(new FieldProblem("reason", reasonProblem)));
        }
        String reason = Objects.requireNonNull(PaymentRules.text(input.getReason()));
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        PaymentRecord original = payment(paymentId);
        // an archived plot (or one turned into common land since) still has its payment reversed
        PlotFacts plot = plots.findAndLockShared(original.getPlotId()).orElseThrow(ApiProblemException::notFound);
        plotLocks.lockAll(tenantId, Set.of(plot.id()));
        if (payments.existsByReversesPaymentId(paymentId)) {
            throw alreadyReversed();
        }
        BillingMonthRecord month = billingMonths.openForBooking(tenantId, original.getReceivedOn());
        List<Release> releases = releaser.releasePayment(tenantId, plot.id(), paymentId,
                ReleaseCause.PAYMENT_REVERSED, actor.memberId());
        PaymentRecord reversal = PaymentRecord.reversalOf(original, month.getId(), reason, actor.memberId(),
                clock.instant());
        try {
            entityManager.persist(reversal);
            entityManager.flush();
        } catch (PersistenceException e) {
            if (ConstraintViolations.violates(e, REVERSED_ONCE_INDEX)) {
                throw alreadyReversed();
            }
            throw e;
        }
        List<FieldChange> changes = new ArrayList<>();
        changes.add(new FieldChange("amount", null, reversal.getAmount().toString()));
        if (original.getReceiptNumber() != null) {
            changes.add(new FieldChange("receiptNumber", null, original.getReceiptNumber().toString()));
        }
        changes.add(new FieldChange("billingMonth", null, month.yearMonth().toString()));
        changes.add(new FieldChange("reason", null, reason));
        audit.publishPayment(actor, LedgerAuditPublisher.Action.PAYMENT_REVERSED, plot.id(), plot.number(), changes,
                null);
        events.publishEvent(new PaymentsChanged(tenantId, Set.of(plot.id())));
        log.debug("Payment {} reversed, {} allocations released", paymentId, releases.size());
    }

    /** Previews ({@code preview}) or saves a new allocation of the payment. */
    @Transactional
    PaymentReallocationResult reallocate(UUID paymentId, AllocationLinesRequest request, boolean preview,
            AccessProfile actor) {
        List<Line> requested = PaymentService.lines(request.getLines());
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        PaymentRecord payment = payment(paymentId);
        PlotFacts plot = plotOf(payment.getPlotId(), preview).orElseThrow(ApiProblemException::notFound);
        if (!preview) {
            plotLocks.lockAll(tenantId, Set.of(plot.id()));
        }
        PlotLedgerState state = ledgers.load(Set.of(plot.id())).get(plot.id()).state();
        if (state.payment(paymentId).map(PaymentState::reversed).orElse(true)) {
            throw alreadyReversed();
        }
        List<Release> planned = ReleasePlanner.forPayment(paymentId, state.allocations(), ReleaseCause.REALLOCATED);
        PlotLedgerState released = state.afterReleases(planned);
        String digest = PaymentDigest.reallocation(paymentId, state.allocations(), released.charges());
        checkDigest(request.getDigest(), digest, preview);
        Money amount = payment.getAmount();
        List<Line> lines = requested == null ? AllocationPlanner.propose(amount, released.charges()).lines()
                : PaymentService.checked("", amount, requested, released);
        List<PaymentCurrentAllocation> current = views.current(paymentId, state);
        PaymentPartProposal proposal = views.part(plot.id(), plot.number(), amount, released, lines);
        if (preview) {
            return new PaymentReallocationResult(true, paymentId, current, proposal, digest);
        }

        List<Release> releases = releaser.releasePayment(tenantId, plot.id(), paymentId, ReleaseCause.REALLOCATED,
                actor.memberId());
        Instant now = clock.instant();
        for (Line line : lines) {
            entityManager.persist(AllocationRecord.allocate(tenantId, plot.id(), paymentId, line.chargeId(),
                    line.amount(), AllocationOrigin.REALLOCATION, actor.memberId(), now));
        }
        entityManager.flush();
        List<FieldChange> changes = new ArrayList<>();
        changes.add(new FieldChange("amount", null, amount.toString()));
        changes.add(new FieldChange("receivedOn", null, payment.getReceivedOn().toString()));
        if (payment.getReceiptNumber() != null) {
            changes.add(new FieldChange("receiptNumber", null, payment.getReceiptNumber().toString()));
        }
        List<Line> old = current.stream()
                .map(allocation -> new Line(allocation.getChargeId(), Money.parse(allocation.getAmount()))).toList();
        changes.add(new FieldChange("lines", views.auditLines(old), views.auditLines(lines)));
        audit.publishPayment(actor, LedgerAuditPublisher.Action.PAYMENT_REALLOCATED, plot.id(), plot.number(),
                changes, null);
        events.publishEvent(new PaymentsChanged(tenantId, Set.of(plot.id())));
        log.debug("Payment {} reallocated: {} allocations released, {} written", paymentId, releases.size(),
                lines.size());
        return new PaymentReallocationResult(false, paymentId, current, proposal, digest);
    }

    /** Previews ({@code preview}) or saves an allocation of the plot's prepayment by hand. */
    @Transactional
    PrepaymentAllocationResult allocatePrepayment(UUID plotId, AllocationLinesRequest request, boolean preview,
            AccessProfile actor) {
        List<Line> requested = PaymentService.lines(request.getLines());
        if (requested != null && requested.isEmpty()) {
            throw PaymentService.invalid(List.of(new FieldProblem("lines", ProblemCodes.REQUIRED)));
        }
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        PlotFacts plot = plotOf(plotId, preview).orElseThrow(LedgerAccess::plotNotFound);
        if (!preview) {
            plotLocks.lockAll(tenantId, Set.of(plot.id()));
        }
        PlotLedgerState state = ledgers.load(Set.of(plot.id())).get(plot.id()).state();
        Money prepayment = state.prepayment();
        if (prepayment.signum() == 0 || AllocationPlanner.openInOrder(state.charges()).isEmpty()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.NOTHING_TO_ALLOCATE,
                    "The plot has no prepayment or no open charge.");
        }
        String digest = PaymentDigest.prepayment(plot.id(), prepayment, state.charges());
        checkDigest(request.getDigest(), digest, preview);
        List<Line> lines = requested == null ? AllocationPlanner.propose(prepayment, state.charges()).lines()
                : PaymentService.checked("", prepayment, requested, state);
        PaymentPartProposal proposal = views.part(plot.id(), plot.number(), prepayment, state, lines);
        if (preview) {
            return new PrepaymentAllocationResult(true, proposal, digest);
        }

        List<Paired> paired = AllocationPlanner.pairWithPayments(lines, state.payments());
        Instant now = clock.instant();
        for (Paired part : paired) {
            entityManager.persist(AllocationRecord.allocate(tenantId, plot.id(), part.paymentId(), part.chargeId(),
                    part.amount(), AllocationOrigin.PREPAYMENT_MANUAL, actor.memberId(), now));
        }
        entityManager.flush();
        Money total = lines.stream().map(Line::amount).reduce(Money.ZERO, Money::plus);
        audit.publishPayment(actor, LedgerAuditPublisher.Action.PREPAYMENT_ALLOCATED, plot.id(), plot.number(),
                List.of(new FieldChange("lines", null, views.auditLines(lines)),
                        new FieldChange("total", null, total.toString())),
                null);
        events.publishEvent(new PaymentsChanged(tenantId, Set.of(plot.id())));
        log.debug("Prepayment of plot {} allocated: {} allocations from {} lines", plot.id(), paired.size(),
                lines.size());
        return new PrepaymentAllocationResult(false, proposal, digest);
    }

    /**
     * The payment (kind {@code PAYMENT}) of the cooperative: 404 {@code NOT_FOUND} when unknown or of another
     * cooperative, 409 {@code PAYMENT_ALREADY_REVERSED} for a reversal row.
     */
    private PaymentRecord payment(UUID paymentId) {
        PaymentRecord payment = payments.findById(paymentId).orElseThrow(ApiProblemException::notFound);
        if (payment.getKind() != PaymentKind.PAYMENT) {
            throw alreadyReversed();
        }
        return payment;
    }

    /** The plot; a write locks it {@code FOR SHARE} (a deletion or an archiving waits). */
    private Optional<PlotFacts> plotOf(UUID plotId, boolean preview) {
        return preview ? plots.find(plotId) : plots.findAndLockShared(plotId);
    }

    /** 409 {@code PREVIEW_OUTDATED} when a save carries the digest of a preview that no longer matches. */
    private static void checkDigest(@Nullable String given, String digest, boolean preview) {
        if (!preview && given != null && !given.equals(digest)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PREVIEW_OUTDATED,
                    "The payments or charges of the plot changed since the preview. Preview again before saving.");
        }
    }

    private static ApiProblemException alreadyReversed() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PAYMENT_ALREADY_REVERSED,
                "The payment has already been reversed.");
    }
}
