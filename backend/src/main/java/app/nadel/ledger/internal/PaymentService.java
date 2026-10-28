package app.nadel.ledger.internal;

import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.ledger.PaymentSource;
import app.nadel.ledger.PaymentsChanged;
import app.nadel.ledger.internal.AllocationPlanner.Line;
import app.nadel.ledger.internal.AllocationPlanner.Violation;
import app.nadel.members.MemberDirectory;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.PaymentEntryRequest;
import nadel.contract.model.PaymentEntryResult;
import nadel.contract.model.PaymentLineInput;
import nadel.contract.model.PaymentPartProposal;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enters payments ({@code POST /payments?preview=}, feature 015 §2.1, technical considerations 2.3-2.6): money received
 * for one plot, as cash (with the cooperative's next receipt number) or a bank transfer entered by hand, placed on the
 * plot's open charges by the proposal ({@link AllocationPlanner#propose}) or by the caller's lines
 * ({@link AllocationPlanner#validate}); what is not placed stays as the plot's prepayment.
 * <p>
 * In this order: the values ({@link PaymentRules}: 400 {@code VALIDATION_FAILED}); the plot exists in the cooperative
 * (404 {@code PLOT_NOT_FOUND}), is not archived (409 {@code PLOT_ARCHIVED}) and not common land (409
 * {@code PLOT_COMMON_LAND}); the payer is a member of the cooperative (field {@code MEMBER_NOT_FOUND}; an archived member
 * may pay). A save then takes the locks in the ledger's order (technical considerations 2.4): the plot
 * {@code FOR SHARE} (so a deletion of the plot, which asks {@code LedgerPlotGuard}, runs before or after), the plot
 * ledger lock ({@link PlotLedgerLock}), the billing month {@code FOR SHARE} ({@link BillingMonths#openForBooking}: the
 * month of the received day, or the earliest open month after it) and, for cash, the receipt counter row
 * ({@link ReceiptCounter}). The ledger of the plot is read under the plot ledger lock; a save sent with the digest of
 * a preview that no longer matches ({@link PaymentDigest#entry}) is 409 {@code PREVIEW_OUTDATED} and writes nothing.
 * The lines are checked next (400 {@code VALIDATION_FAILED}, {@code ALLOCATION_EXCEEDS_PAYMENT},
 * {@code ALLOCATION_EXCEEDS_CHARGE}). A preview takes no lock and writes nothing.
 * <p>
 * A save writes the payment (its own group) and its allocations (origin {@code ENTRY}, by the actor), publishes the
 * audit entry {@code PAYMENT_RECORDED} with the payer note sealed, and {@link PaymentsChanged} for the plot status. The
 * payer note and the bank reference are user content; the note is personal data and is stored sealed
 * ({@link PaymentNoteCipher}). Logs ids and counts only.
 */
@Service
class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PlotCatalog plots;
    private final MemberDirectory members;
    private final PlotLedgerLock plotLocks;
    private final PlotLedgerQueries ledgers;
    private final BillingMonths billingMonths;
    private final ReceiptCounter receipts;
    private final PaymentNoteCipher notes;
    private final PaymentViews views;
    private final LedgerAuditPublisher audit;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final Clock clock;

    PaymentService(PlotCatalog plots, MemberDirectory members, PlotLedgerLock plotLocks, PlotLedgerQueries ledgers,
            BillingMonths billingMonths, ReceiptCounter receipts, PaymentNoteCipher notes, PaymentViews views,
            LedgerAuditPublisher audit, ApplicationEventPublisher events, EntityManager entityManager, Clock clock) {
        this.plots = plots;
        this.members = members;
        this.plotLocks = plotLocks;
        this.ledgers = ledgers;
        this.billingMonths = billingMonths;
        this.receipts = receipts;
        this.notes = notes;
        this.views = views;
        this.audit = audit;
        this.events = events;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * The values of a request that passed {@link PaymentRules}.
     *
     * @param lines the caller's lines; null to save the proposal
     */
    private record Entry(Money amount, LocalDate receivedOn, PaymentSource source, @Nullable String bankReference,
            @Nullable UUID payerMemberId, @Nullable String payerNote, UUID plotId, @Nullable List<Line> lines,
            @Nullable String digest) {
    }

    /** Previews ({@code preview}) or saves the payment of the request. */
    @Transactional
    PaymentEntryResult enter(PaymentEntryRequest request, boolean preview, AccessProfile actor) {
        Entry entry = validate(request);
        UUID tenantId = Objects.requireNonNull(actor.tenantId());

        PlotFacts plot = (preview ? plots.find(entry.plotId()) : plots.findAndLockShared(entry.plotId()))
                .orElseThrow(LedgerAccess::plotNotFound);
        if (plot.archived()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_ARCHIVED, "The plot is archived.");
        }
        if (plot.commonLand()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_COMMON_LAND,
                    "No payment is taken for common land.");
        }
        if (entry.payerMemberId() != null && members.find(Set.of(entry.payerMemberId())).isEmpty()) {
            throw invalid(List.of(new FieldProblem("payerMemberId", ProblemCodes.MEMBER_NOT_FOUND)));
        }

        if (!preview) {
            plotLocks.lockAll(tenantId, Set.of(plot.id()));
        }
        PlotLedgerState state = ledgers.load(Set.of(plot.id())).get(plot.id()).state();
        String digest = PaymentDigest.entry(entry.amount(),
                List.of(new PaymentDigest.Part(plot.id(), entry.amount(), state.charges())));
        if (!preview && entry.digest() != null && !entry.digest().equals(digest)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PREVIEW_OUTDATED,
                    "The charges of the plot changed since the preview. Preview again before saving.");
        }
        List<Line> lines = entry.lines() == null
                ? AllocationPlanner.propose(entry.amount(), state.charges()).lines()
                : checked(entry.amount(), entry.lines(), state);
        PaymentPartProposal part = views.part(plot.id(), plot.number(), entry.amount(), state, lines);
        if (preview) {
            return new PaymentEntryResult(true, entry.amount().toString(), List.of(part), digest, List.of());
        }

        BillingMonthRecord month = billingMonths.openForBooking(tenantId, entry.receivedOn());
        Long receipt = entry.source() == PaymentSource.CASH ? receipts.next(tenantId) : null;
        UUID paymentId = UUID.randomUUID();
        Instant now = clock.instant();
        PaymentRecord payment = PaymentRecord.payment(paymentId, tenantId, plot.id(), entry.source(), entry.amount(),
                entry.receivedOn(), month.getId(), paymentId, receipt, entry.bankReference(), entry.payerMemberId(),
                notes.seal(tenantId, paymentId, entry.payerNote()), actor.memberId(), now);
        entityManager.persist(payment);
        // the allocations point at the payment: it is inserted first (inserts are ordered by entity otherwise)
        entityManager.flush();
        for (Line line : lines) {
            entityManager.persist(AllocationRecord.allocate(tenantId, plot.id(), paymentId, line.chargeId(),
                    line.amount(), AllocationOrigin.ENTRY, actor.memberId(), now));
        }
        entityManager.flush();

        audit.publishPayment(actor, LedgerAuditPublisher.Action.PAYMENT_RECORDED, plot.id(), plot.number(),
                auditChanges(payment, month), entry.payerNote());
        events.publishEvent(new PaymentsChanged(tenantId, Set.of(plot.id())));
        log.debug("Payment {} saved with {} allocations", paymentId, lines.size());
        return new PaymentEntryResult(false, entry.amount().toString(), List.of(part), digest,
                List.of(PaymentViews.saved(payment, month)));
    }

    /** The values of the request, or 400 {@code VALIDATION_FAILED} with every field problem. */
    private Entry validate(PaymentEntryRequest request) {
        List<FieldProblem> errors = new ArrayList<>();
        Money amount = Money.parse(request.getAmount());
        PaymentSource source = PaymentSource.valueOf(request.getSource().name());
        String amountProblem = PaymentRules.amountProblem(amount);
        if (amountProblem != null) {
            errors.add(new FieldProblem("amount", amountProblem));
        }
        String dateProblem = PaymentRules.dateProblem(request.getReceivedOn(), clock);
        if (dateProblem != null) {
            errors.add(new FieldProblem("receivedOn", dateProblem));
        }
        if (request.getPlotId() == null) {
            errors.add(new FieldProblem("plotId", ProblemCodes.REQUIRED));
        }
        String noteProblem = PaymentRules.payerNoteProblem(request.getPayerNote());
        if (noteProblem != null) {
            errors.add(new FieldProblem("payerNote", noteProblem));
        }
        String referenceProblem = PaymentRules.bankReferenceProblem(request.getBankReference(), source);
        if (referenceProblem != null) {
            errors.add(new FieldProblem("bankReference", referenceProblem));
        }
        List<Line> lines = null;
        if (request.getLines() != null) {
            lines = new ArrayList<>();
            for (PaymentLineInput line : request.getLines()) {
                lines.add(new Line(line.getChargeId(), Money.parse(line.getAmount())));
            }
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        return new Entry(amount, request.getReceivedOn(), source, PaymentRules.text(request.getBankReference()),
                request.getPayerMemberId(), PaymentRules.text(request.getPayerNote()),
                Objects.requireNonNull(request.getPlotId()), lines, request.getDigest());
    }

    /**
     * The caller's lines when they are valid against the plot's open charges: 400 {@code VALIDATION_FAILED} for a
     * charge that is not open on the plot, named twice or a line not above zero; otherwise 400
     * {@code ALLOCATION_EXCEEDS_PAYMENT} when the lines together exceed the payment, then 400
     * {@code ALLOCATION_EXCEEDS_CHARGE} for the lines that exceed what is open on their charge.
     */
    private static List<Line> checked(Money amount, List<Line> lines, PlotLedgerState state) {
        List<Violation> violations = AllocationPlanner.validate(amount, lines, state.charges());
        if (violations.isEmpty()) {
            return lines;
        }
        List<FieldProblem> fields = new ArrayList<>();
        List<FieldProblem> exceedsCharge = new ArrayList<>();
        boolean exceedsPayment = false;
        for (Violation violation : violations) {
            Integer i = violation.line();
            switch (violation.problem()) {
                case ALLOCATION_EXCEEDS_PAYMENT -> exceedsPayment = true;
                case ALLOCATION_EXCEEDS_CHARGE -> exceedsCharge.add(new FieldProblem("lines[" + i + "].amount",
                        ProblemCodes.ALLOCATION_EXCEEDS_CHARGE));
                case NOT_FOUND -> fields.add(new FieldProblem("lines[" + i + "].chargeId", ProblemCodes.NOT_FOUND));
                case DUPLICATE -> fields.add(new FieldProblem("lines[" + i + "].chargeId", ProblemCodes.DUPLICATE));
                case AMOUNT_NOT_POSITIVE -> fields.add(new FieldProblem("lines[" + i + "].amount",
                        ProblemCodes.AMOUNT_NOT_POSITIVE));
            }
        }
        if (!fields.isEmpty()) {
            throw invalid(fields);
        }
        if (exceedsPayment) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.ALLOCATION_EXCEEDS_PAYMENT,
                    "More is allocated than the payment amount.",
                    List.of(new FieldProblem("lines", ProblemCodes.ALLOCATION_EXCEEDS_PAYMENT)));
        }
        throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.ALLOCATION_EXCEEDS_CHARGE,
                "An amount exceeds what is open on the charge.", exceedsCharge);
    }

    /**
     * The values of {@code PAYMENT_RECORDED}: codes and parameters; the receipt number of a cash payment or the bank
     * reference (user content) of a bank payment when given. The payer note is added sealed by the publisher.
     */
    static List<FieldChange> auditChanges(PaymentRecord payment, BillingMonthRecord month) {
        List<FieldChange> changes = new ArrayList<>(List.of(
                new FieldChange("amount", null, payment.getAmount().toString()),
                new FieldChange("source", null, payment.getSource().name()),
                new FieldChange("receivedOn", null, payment.getReceivedOn().toString()),
                new FieldChange("billingMonth", null, month.yearMonth().toString())));
        if (payment.getReceiptNumber() != null) {
            changes.add(new FieldChange("receiptNumber", null, payment.getReceiptNumber().toString()));
        }
        if (payment.getBankReference() != null) {
            changes.add(new FieldChange("bankReference", null, payment.getBankReference()));
        }
        return changes;
    }

    private static ApiProblemException invalid(List<FieldProblem> errors) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                "The request is not valid.", errors);
    }
}
