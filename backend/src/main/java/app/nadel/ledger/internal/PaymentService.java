package app.nadel.ledger.internal;

import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.OwnedPlot;
import app.nadel.identity.OwnedPlotsSource;
import app.nadel.ledger.PaymentSource;
import app.nadel.ledger.PaymentsChanged;
import app.nadel.ledger.internal.AllocationPlanner.Line;
import app.nadel.ledger.internal.AllocationPlanner.Violation;
import app.nadel.ledger.internal.PlotLedgerQueries.PlotLedger;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.PaymentEntryRequest;
import nadel.contract.model.PaymentEntryResult;
import nadel.contract.model.PaymentLineInput;
import nadel.contract.model.PaymentPartProposal;
import nadel.contract.model.PaymentSplitInput;
import nadel.contract.model.PaymentSplitPartInput;
import nadel.contract.model.PaymentSplitPlot;
import nadel.contract.model.PaymentSplitPreview;
import nadel.contract.model.SavedPayment;
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
 * <p>
 * <b>Split</b> (feature 015 §2.6, {@code split} instead of {@code plotId}): the plots are the payer's current plots
 * (owned now through {@link OwnedPlotsSource}, not common land, not archived) in natural plot order
 * ({@link PlotCatalog#inNaturalOrder}); without parts the {@link PaymentSplitter} proposal over their debts is used,
 * otherwise the caller's parts (each a plot of the payer at most once, {@code 0.00} or more: a part of {@code 0.00} is
 * shown but not saved; an absent amount takes the proposed part), which must add up to the amount (400
 * {@code SPLIT_TOTAL_MISMATCH}). Each part's lines are checked as for one plot, under {@code split.parts[i].}. A save
 * locks every plot of the parts {@code FOR SHARE}, then all their ledger locks in one call, books one month, takes
 * one receipt number for a cash group, and writes one payment per part above zero with a shared group, the payer
 * {@code split.payerMemberId} and the note sealed per payment; one {@code PAYMENT_RECORDED} per part and one
 * {@link PaymentsChanged} with every plot.
 */
@Service
class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PlotCatalog plots;
    private final OwnedPlotsSource ownedPlots;
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

    PaymentService(PlotCatalog plots, OwnedPlotsSource ownedPlots, MemberDirectory members, PlotLedgerLock plotLocks,
            PlotLedgerQueries ledgers, BillingMonths billingMonths, ReceiptCounter receipts, PaymentNoteCipher notes,
            PaymentViews views, LedgerAuditPublisher audit, ApplicationEventPublisher events,
            EntityManager entityManager, Clock clock) {
        this.plots = plots;
        this.ownedPlots = ownedPlots;
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
     * The values of a request that passed {@link PaymentRules}: one plot ({@code plotId}) or a split ({@code split}),
     * never both.
     *
     * @param lines the caller's lines for {@code plotId}; null to save the proposal
     */
    private record Entry(Money amount, LocalDate receivedOn, PaymentSource source, @Nullable String bankReference,
            @Nullable UUID payerMemberId, @Nullable String payerNote, @Nullable UUID plotId, @Nullable List<Line> lines,
            @Nullable Split split, @Nullable String digest) {
    }

    /**
     * The split of a request.
     *
     * @param parts the caller's parts in request order; null to use the proposed split
     */
    private record Split(UUID payerMemberId, @Nullable List<PartInput> parts) {
    }

    /**
     * A part the caller gave.
     *
     * @param index  its index in the request, for the field names of problems
     * @param amount null to take the proposed part
     * @param lines  null to use the proposal of the part
     */
    private record PartInput(int index, UUID plotId, @Nullable Money amount, @Nullable List<Line> lines) {
    }

    /** A part of a split resolved against the payer's plots: its plot, amount and the caller's lines (or null). */
    private record Part(PlotFacts plot, Money amount, @Nullable List<Line> lines, String prefix) {
    }

    /** Previews ({@code preview}) or saves the payment of the request. */
    @Transactional
    PaymentEntryResult enter(PaymentEntryRequest request, boolean preview, AccessProfile actor) {
        Entry entry = validate(request);
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        if (entry.split() != null) {
            return enterSplit(entry, entry.split(), preview, actor, tenantId);
        }

        PlotFacts plot = (preview ? plots.find(Objects.requireNonNull(entry.plotId()))
                : plots.findAndLockShared(Objects.requireNonNull(entry.plotId())))
                .orElseThrow(LedgerAccess::plotNotFound);
        takesPayments(plot);
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
                : checked("", entry.amount(), entry.lines(), state);
        PaymentPartProposal part = views.part(plot.id(), plot.number(), entry.amount(), state, lines);
        if (preview) {
            return new PaymentEntryResult(true, entry.amount().toString(), List.of(part), digest, List.of());
        }

        List<SavedPayment> saved = book(entry, entry.payerMemberId(), List.of(new Booking(plot, entry.amount(), lines)),
                actor, tenantId);
        return new PaymentEntryResult(false, entry.amount().toString(), List.of(part), digest, saved);
    }

    /** A payment to write: the plot, its amount (above zero) and its allocation lines. */
    private record Booking(PlotFacts plot, Money amount, List<Line> lines) {
    }

    /**
     * Writes the payments of one group under the locks the caller holds: books the month of the received day, takes
     * one receipt number for cash (stored on every payment of the group), writes each payment with its allocations,
     * publishes one {@code PAYMENT_RECORDED} per payment and one {@link PaymentsChanged} with every plot.
     */
    private List<SavedPayment> book(Entry entry, @Nullable UUID payerMemberId, List<Booking> bookings,
            AccessProfile actor, UUID tenantId) {
        BillingMonthRecord month = billingMonths.openForBooking(tenantId, entry.receivedOn());
        Long receipt = entry.source() == PaymentSource.CASH ? receipts.next(tenantId) : null;
        Instant now = clock.instant();
        // a payment of one plot is its own group; the parts of a split share the id of the first part
        UUID groupId = null;
        List<PaymentRecord> payments = new ArrayList<>();
        for (Booking booking : bookings) {
            UUID paymentId = UUID.randomUUID();
            groupId = groupId == null ? paymentId : groupId;
            PaymentRecord payment = PaymentRecord.payment(paymentId, tenantId, booking.plot().id(), entry.source(),
                    booking.amount(), entry.receivedOn(), month.getId(), groupId, receipt, entry.bankReference(),
                    payerMemberId, notes.seal(tenantId, paymentId, entry.payerNote()), actor.memberId(), now);
            entityManager.persist(payment);
            payments.add(payment);
        }
        // the allocations point at the payments: they are inserted first (inserts are ordered by entity otherwise)
        entityManager.flush();
        int allocations = 0;
        for (int i = 0; i < bookings.size(); i++) {
            Booking booking = bookings.get(i);
            for (Line line : booking.lines()) {
                entityManager.persist(AllocationRecord.allocate(tenantId, booking.plot().id(), payments.get(i).getId(),
                        line.chargeId(), line.amount(), AllocationOrigin.ENTRY, actor.memberId(), now));
                allocations++;
            }
        }
        entityManager.flush();

        Set<UUID> plotIds = new LinkedHashSet<>();
        for (int i = 0; i < bookings.size(); i++) {
            PlotFacts plot = bookings.get(i).plot();
            audit.publishPayment(actor, LedgerAuditPublisher.Action.PAYMENT_RECORDED, plot.id(), plot.number(),
                    auditChanges(payments.get(i), month), entry.payerNote());
            plotIds.add(plot.id());
        }
        events.publishEvent(new PaymentsChanged(tenantId, plotIds));
        // the group id is the id of its first payment (of the only one for a payment of one plot)
        log.debug("Payment {} saved with {} allocations, {} payments in its group", groupId, allocations,
                payments.size());
        return payments.stream().map(payment -> PaymentViews.saved(payment, month)).toList();
    }

    /**
     * A payment split over the payer's current plots (feature 015 §2.6): the parts (proposed or the caller's), each
     * part's proposal or checked lines, the digest; a save locks the plots of the parts and writes one payment per part
     * above zero.
     */
    private PaymentEntryResult enterSplit(Entry entry, Split split, boolean preview, AccessProfile actor,
            UUID tenantId) {
        UUID payer = split.payerMemberId();
        if (members.find(Set.of(payer)).isEmpty()) {
            throw invalid(List.of(new FieldProblem("split.payerMemberId", ProblemCodes.MEMBER_NOT_FOUND)));
        }
        List<PlotFacts> payerPlots = payerPlots(payer);
        if (payerPlots.isEmpty()) {
            throw invalid(List.of(new FieldProblem("split.payerMemberId", ProblemCodes.NOT_FOUND)));
        }
        Map<UUID, PlotFacts> byId = payerPlots.stream()
                .collect(Collectors.toMap(PlotFacts::id, Function.identity()));
        if (split.parts() != null) {
            checkPartPlots(split.parts(), byId);
        }
        List<UUID> partPlots = split.parts() == null ? payerPlots.stream().map(PlotFacts::id).toList()
                : split.parts().stream().map(PartInput::plotId).toList();
        if (!preview) {
            // plot FOR SHARE (a deletion or an archiving waits), then every plot ledger lock in one sorted call
            List<PlotFacts> locked = plots.findAllAndLockShared(partPlots);
            if (locked.size() != partPlots.size()) {
                throw LedgerAccess.plotNotFound();
            }
            locked.forEach(PaymentService::takesPayments);
            plotLocks.lockAll(tenantId, partPlots);
        }
        Map<UUID, PlotLedger> ledgerOf = ledgers.load(byId.keySet());
        List<PaymentSplitter.PlotDebt> debts = payerPlots.stream()
                .map(plot -> new PaymentSplitter.PlotDebt(plot.id(),
                        PaymentViews.debt(ledgerOf.get(plot.id()).state())))
                .toList();
        Map<UUID, Money> proposed = new HashMap<>();
        PaymentSplitter.split(entry.amount(), debts).forEach(part -> proposed.put(part.plotId(), part.amount()));

        List<Part> parts = parts(split, payerPlots, proposed);
        Money total = parts.stream().map(Part::amount).reduce(Money.ZERO, Money::plus);
        if (total.value().compareTo(entry.amount().value()) != 0) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.SPLIT_TOTAL_MISMATCH,
                    "The parts do not add up to the payment amount.",
                    List.of(new FieldProblem("split.parts", ProblemCodes.SPLIT_TOTAL_MISMATCH)));
        }
        String digest = PaymentDigest.entry(entry.amount(), payer, parts.stream()
                .map(part -> new PaymentDigest.Part(part.plot().id(), part.amount(),
                        ledgerOf.get(part.plot().id()).state().charges()))
                .toList());
        if (!preview && entry.digest() != null && !entry.digest().equals(digest)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PREVIEW_OUTDATED,
                    "The charges of the plots changed since the preview. Preview again before saving.");
        }

        List<PaymentPartProposal> partViews = new ArrayList<>();
        List<Booking> bookings = new ArrayList<>();
        for (Part part : parts) {
            PlotLedgerState state = ledgerOf.get(part.plot().id()).state();
            List<Line> lines = part.lines() == null
                    ? AllocationPlanner.propose(part.amount(), state.charges()).lines()
                    : checked(part.prefix(), part.amount(), part.lines(), state);
            partViews.add(views.part(part.plot().id(), part.plot().number(), part.amount(), state, lines));
            if (part.amount().signum() > 0) {
                bookings.add(new Booking(part.plot(), part.amount(), lines));
            }
        }
        PaymentSplitPreview splitView = new PaymentSplitPreview(payer, debts.stream()
                .map(debt -> new PaymentSplitPlot(debt.plotId(), byId.get(debt.plotId()).number(),
                        debt.debt().toString(), proposed.get(debt.plotId()).toString()))
                .toList());
        List<SavedPayment> saved = preview ? List.of() : book(entry, payer, bookings, actor, tenantId);
        PaymentEntryResult result = new PaymentEntryResult(preview, entry.amount().toString(), partViews, digest,
                saved);
        result.setSplit(splitView);
        return result;
    }

    /**
     * The plots of a split payer: owned now, not common land, not archived, in natural plot order. Empty when the
     * member owns no such plot.
     */
    private List<PlotFacts> payerPlots(UUID payer) {
        List<UUID> owned = ownedPlots.currentPlotsOf(payer).stream().map(OwnedPlot::plotId).toList();
        if (owned.isEmpty()) {
            return List.of();
        }
        Map<UUID, PlotFacts> facts = plots.findAll(owned);
        return plots.inNaturalOrder(owned).stream().map(facts::get).filter(Objects::nonNull)
                .filter(plot -> !plot.archived() && !plot.commonLand()).toList();
    }

    /** 400 {@code VALIDATION_FAILED} when a part names a plot that is not a current plot of the payer, or twice. */
    private static void checkPartPlots(List<PartInput> parts, Map<UUID, PlotFacts> payerPlots) {
        List<FieldProblem> errors = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (PartInput part : parts) {
            String field = "split.parts[" + part.index() + "].plotId";
            if (!payerPlots.containsKey(part.plotId())) {
                errors.add(new FieldProblem(field, ProblemCodes.NOT_FOUND));
            } else if (!seen.add(part.plotId())) {
                errors.add(new FieldProblem(field, ProblemCodes.DUPLICATE));
            }
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
    }

    /**
     * The parts of the split in natural plot order: the proposal for every plot of the payer, or the caller's parts
     * (an absent amount takes the proposed part of its plot).
     */
    private static List<Part> parts(Split split, List<PlotFacts> payerPlots, Map<UUID, Money> proposed) {
        List<Part> parts = new ArrayList<>();
        if (split.parts() == null) {
            payerPlots.forEach(plot -> parts.add(new Part(plot, proposed.get(plot.id()), null, "")));
            return parts;
        }
        Map<UUID, PartInput> given = split.parts().stream()
                .collect(Collectors.toMap(PartInput::plotId, Function.identity()));
        for (PlotFacts plot : payerPlots) {
            PartInput part = given.get(plot.id());
            if (part != null) {
                parts.add(new Part(plot, part.amount() == null ? proposed.get(plot.id()) : part.amount(),
                        part.lines(), "split.parts[" + part.index() + "]."));
            }
        }
        return parts;
    }

    /** 409 for a plot that takes no payment: archived ({@code PLOT_ARCHIVED}) or common land. */
    private static void takesPayments(PlotFacts plot) {
        if (plot.archived()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_ARCHIVED, "The plot is archived.");
        }
        if (plot.commonLand()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_COMMON_LAND,
                    "No payment is taken for common land.");
        }
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
        PaymentSplitInput splitInput = request.getSplit();
        if (splitInput == null && request.getPlotId() == null) {
            errors.add(new FieldProblem("plotId", ProblemCodes.REQUIRED));
        }
        if (splitInput != null && request.getPlotId() != null) {
            errors.add(new FieldProblem("split", ProblemCodes.INVALID));
        }
        if (splitInput != null && request.getLines() != null) {
            errors.add(new FieldProblem("lines", ProblemCodes.INVALID));
        }
        if (splitInput != null && request.getPayerMemberId() != null
                && !request.getPayerMemberId().equals(splitInput.getPayerMemberId())) {
            errors.add(new FieldProblem("payerMemberId", ProblemCodes.INVALID));
        }
        Split split = splitInput == null ? null : split(splitInput, errors);
        String noteProblem = PaymentRules.payerNoteProblem(request.getPayerNote());
        if (noteProblem != null) {
            errors.add(new FieldProblem("payerNote", noteProblem));
        }
        String referenceProblem = PaymentRules.bankReferenceProblem(request.getBankReference(), source);
        if (referenceProblem != null) {
            errors.add(new FieldProblem("bankReference", referenceProblem));
        }
        List<Line> lines = lines(request.getLines());
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        return new Entry(amount, request.getReceivedOn(), source, PaymentRules.text(request.getBankReference()),
                split == null ? request.getPayerMemberId() : split.payerMemberId(),
                PaymentRules.text(request.getPayerNote()), request.getPlotId(), lines, split, request.getDigest());
    }

    /**
     * The split of the request; its problems are added to {@code errors}: an empty list of parts ({@code REQUIRED}), a
     * part amount below zero or with more than 12 integer digits ({@code INVALID}; {@code 0.00} is allowed).
     */
    private static Split split(PaymentSplitInput input, List<FieldProblem> errors) {
        if (input.getParts() == null) {
            return new Split(input.getPayerMemberId(), null);
        }
        if (input.getParts().isEmpty()) {
            errors.add(new FieldProblem("split.parts", ProblemCodes.REQUIRED));
        }
        List<PartInput> parts = new ArrayList<>();
        for (int i = 0; i < input.getParts().size(); i++) {
            PaymentSplitPartInput part = input.getParts().get(i);
            Money amount = part.getAmount() == null ? null : Money.parse(part.getAmount());
            if (amount != null && (amount.signum() < 0
                    || amount.signum() > 0 && PaymentRules.amountProblem(amount) != null)) {
                errors.add(new FieldProblem("split.parts[" + i + "].amount", ProblemCodes.INVALID));
            }
            parts.add(new PartInput(i, part.getPlotId(), amount, lines(part.getLines())));
        }
        return new Split(input.getPayerMemberId(), parts);
    }

    static @Nullable List<Line> lines(@Nullable List<PaymentLineInput> input) {
        if (input == null) {
            return null;
        }
        List<Line> lines = new ArrayList<>();
        for (PaymentLineInput line : input) {
            lines.add(new Line(line.getChargeId(), Money.parse(line.getAmount())));
        }
        return lines;
    }

    /**
     * The caller's lines when they are valid against the plot's open charges: 400 {@code VALIDATION_FAILED} for a
     * charge that is not open on the plot, named twice or a line not above zero; otherwise 400
     * {@code ALLOCATION_EXCEEDS_PAYMENT} when the lines together exceed the payment, then 400
     * {@code ALLOCATION_EXCEEDS_CHARGE} for the lines that exceed what is open on their charge. The field names start
     * with {@code prefix} ({@code ""} for one plot, {@code split.parts[i].} for a part of a split).
     */
    static List<Line> checked(String prefix, Money amount, List<Line> lines, PlotLedgerState state) {
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
                case ALLOCATION_EXCEEDS_CHARGE -> exceedsCharge.add(new FieldProblem(
                        prefix + "lines[" + i + "].amount", ProblemCodes.ALLOCATION_EXCEEDS_CHARGE));
                case NOT_FOUND -> fields.add(new FieldProblem(prefix + "lines[" + i + "].chargeId",
                        ProblemCodes.NOT_FOUND));
                case DUPLICATE -> fields.add(new FieldProblem(prefix + "lines[" + i + "].chargeId",
                        ProblemCodes.DUPLICATE));
                case AMOUNT_NOT_POSITIVE -> fields.add(new FieldProblem(prefix + "lines[" + i + "].amount",
                        ProblemCodes.AMOUNT_NOT_POSITIVE));
            }
        }
        if (!fields.isEmpty()) {
            throw invalid(fields);
        }
        if (exceedsPayment) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.ALLOCATION_EXCEEDS_PAYMENT,
                    "More is allocated than the payment amount.",
                    List.of(new FieldProblem(prefix + "lines", ProblemCodes.ALLOCATION_EXCEEDS_PAYMENT)));
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

    static ApiProblemException invalid(List<FieldProblem> errors) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                "The request is not valid.", errors);
    }
}
