package app.nadel.ledger.internal;

import app.nadel.members.MemberRef;
import app.nadel.money.Money;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.AllocationProblem;
import nadel.contract.model.AllocationProblemCode;
import nadel.contract.model.AllocationReleaseCause;
import nadel.contract.model.PaymentAllocation;
import nadel.contract.model.PaymentChargeAmount;
import nadel.contract.model.PaymentCurrentAllocation;
import nadel.contract.model.PaymentPartProposal;
import nadel.contract.model.PaymentPayer;
import nadel.contract.model.PaymentProposalLine;
import nadel.contract.model.PaymentResultLine;
import nadel.contract.model.PaymentSource;
import nadel.contract.model.SavedPayment;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shapes payments for the API (tag {@code payments}): the proposal of a payment over the open charges of a plot, a
 * saved payment, the payer and the allocations of a payment with their charges. The charges, their months and the
 * charge types are loaded once per call. Amounts are the server's: {@link Money} as strings with two decimals. Runs
 * inside the caller's transaction.
 */
@Component
class PaymentViews {

    private final ChargeRepository charges;
    private final BillingMonthRepository months;
    private final ChargeTypeCatalog chargeTypes;

    PaymentViews(ChargeRepository charges, BillingMonthRepository months, ChargeTypeCatalog chargeTypes) {
        this.charges = charges;
        this.months = months;
        this.chargeTypes = chargeTypes;
    }

    /** The charges, the billing months and the charge types the views of a call name, loaded once. */
    record Context(Map<UUID, ChargeRecord> charges, Map<UUID, BillingMonthRecord> months,
            Map<UUID, ChargeTypeFacts> types) {
    }

    /** Loads the given charges, every billing month and every charge type of the cooperative. */
    Context context(Collection<UUID> chargeIds) {
        Map<UUID, ChargeRecord> byId = chargeIds.isEmpty() ? Map.of()
                : charges.findAllById(chargeIds).stream()
                        .collect(Collectors.toMap(ChargeRecord::getId, Function.identity()));
        return new Context(byId, months.findAll().stream()
                .collect(Collectors.toMap(BillingMonthRecord::getId, Function.identity())),
                chargeTypes.all().stream().collect(Collectors.toMap(ChargeTypeFacts::id, Function.identity())));
    }

    /** The billing month of a row as {@code YYYY-MM}. */
    static String month(Context context, UUID billingMonthId) {
        return context.months().get(billingMonthId).yearMonth().toString();
    }

    /**
     * The proposal of the placement's amount on the plot: every open charge of the plot in planner order with what
     * the proposal's or the caller's lines place on it ({@code 0.00} when nothing; lines with problems as sent), the
     * lines a save writes (the automatic ones after them; none while there is a problem), the total placed (the
     * automatic part too; with problems the requested amounts above zero), what the payment leaves as prepayment
     * (never below {@code 0.00}) and what the plot owes before the payment.
     */
    PaymentPartProposal part(String plotNumber, PlotLedgerState state, LinePlacement placement) {
        Map<UUID, Money> placed = new HashMap<>();
        for (AllocationPlanner.Line line : placement.lines()) {
            placed.merge(line.chargeId(), line.amount(), Money::plus);
        }
        List<PaymentResultLine> result = new ArrayList<>();
        if (placement.valid()) {
            placement.lines().forEach(line -> result.add(
                    new PaymentResultLine(line.chargeId(), line.amount().toString(), false)));
            placement.automatic().forEach(line -> result.add(
                    new PaymentResultLine(line.chargeId(), line.amount().toString(), true)));
        }
        Money total = placement.allocated();
        Money left = placement.amount().minus(total);
        List<ChargeState> open = AllocationPlanner.openInOrder(state.charges());
        Context context = context(open.stream().map(ChargeState::id).toList());
        List<PaymentProposalLine> rows = open.stream().map(charge -> {
            ChargeRecord record = context.charges().get(charge.id());
            return new PaymentProposalLine(charge.id(),
                    nadel.contract.model.ChargeKind.fromValue(charge.kind().name()),
                    ChargeViews.typeRef(context.types().get(charge.chargeTypeId())),
                    month(context, record.getBillingMonthId()), record.getServiceFrom(), record.getServiceTo(),
                    charge.accruedOn(), charge.open().toString(),
                    placed.getOrDefault(charge.id(), Money.ZERO).toString());
        }).toList();
        PaymentPartProposal proposal = new PaymentPartProposal(placement.plotId(), plotNumber,
                placement.amount().toString(), rows, result, total.toString(),
                placement.automaticTotal().toString(), (left.signum() < 0 ? Money.ZERO : left).toString());
        proposal.setDebt(debt(state).toString());
        return proposal;
    }

    /**
     * The problems of a placement as a preview answers them: per line its plot, index and charge; the problem of all
     * lines ({@code ALLOCATION_EXCEEDS_PAYMENT}) with its plot only.
     */
    static List<AllocationProblem> problems(LinePlacement placement) {
        List<AllocationProblem> problems = new ArrayList<>();
        for (AllocationPlanner.Violation violation : placement.violations()) {
            AllocationProblem problem = new AllocationProblem(
                    AllocationProblemCode.fromValue(violation.problem().name()));
            problem.setPlotId(placement.plotId());
            Integer line = violation.line();
            if (line != null) {
                problem.setLine(line);
                problem.setChargeId(placement.lines().get(line).chargeId());
            }
            problems.add(problem);
        }
        return problems;
    }

    /**
     * Where the payment's money is now: per charge the net of its allocation rows (allocations minus releases), only
     * charges with more than {@code 0.00}, in planner order (a charge the state does not know last, by id).
     */
    static List<PaymentChargeAmount> net(Collection<AllocationRecord> rows, PlotLedgerState state, Context context) {
        Map<UUID, Money> byCharge = new HashMap<>();
        rows.forEach(row -> byCharge.merge(row.getChargeId(), row.getAmount(), Money::plus));
        Map<UUID, ChargeState> charges = new HashMap<>();
        state.charges().forEach(charge -> charges.put(charge.id(), charge));
        return byCharge.entrySet().stream().filter(entry -> entry.getValue().signum() > 0)
                .sorted(Comparator.comparing((Map.Entry<UUID, Money> entry) -> charges.get(entry.getKey()),
                        Comparator.nullsLast(AllocationPlanner.ORDER))
                        .thenComparing(entry -> entry.getKey().toString()))
                .map(entry -> {
                    ChargeRecord charge = context.charges().get(entry.getKey());
                    return new PaymentChargeAmount(entry.getKey(),
                            nadel.contract.model.ChargeKind.fromValue(charge.getKind().name()),
                            ChargeViews.typeRef(context.types().get(charge.getChargeTypeId())),
                            month(context, charge.getBillingMonthId()), charge.getServiceFrom(),
                            charge.getServiceTo(), charge.getAccruedOn(), entry.getValue().toString());
                }).toList();
    }

    /**
     * What the live allocations of the payment place on their charges now (what is left of each), oldest first and,
     * among those written together, in the planner order of their charges, with their charges.
     */
    List<PaymentCurrentAllocation> current(UUID paymentId, PlotLedgerState state) {
        Map<UUID, ChargeState> charges = new HashMap<>();
        state.charges().forEach(charge -> charges.put(charge.id(), charge));
        List<AllocationState> live = state.allocations().stream()
                .filter(allocation -> allocation.paymentId().equals(paymentId) && allocation.live())
                .sorted(Comparator.comparing(AllocationState::createdAt)
                        .thenComparing(allocation -> charges.get(allocation.chargeId()),
                                Comparator.nullsLast(AllocationPlanner.ORDER))
                        .thenComparing(allocation -> allocation.id().toString()))
                .toList();
        Context context = context(live.stream().map(AllocationState::chargeId).distinct().toList());
        return live.stream().map(allocation -> {
            ChargeRecord charge = context.charges().get(allocation.chargeId());
            return new PaymentCurrentAllocation(allocation.id(), allocation.chargeId(),
                    nadel.contract.model.ChargeKind.fromValue(charge.getKind().name()),
                    ChargeViews.typeRef(context.types().get(charge.getChargeTypeId())),
                    month(context, charge.getBillingMonthId()), charge.getServiceFrom(), charge.getServiceTo(),
                    charge.getAccruedOn(), allocation.remaining().toString());
        }).toList();
    }

    /**
     * Lines of money on charges as the audit log stores them: {@code <code> <YYYY-MM> <amount>} per line, joined by
     * {@code "; "}, where the code is the charge type's code (its stored name, user content, for a type without one)
     * and the month the start of the charge's service period; null when there is no line.
     */
    @Nullable String auditLines(List<AllocationPlanner.Line> lines) {
        if (lines.isEmpty()) {
            return null;
        }
        Context context = context(lines.stream().map(AllocationPlanner.Line::chargeId).distinct().toList());
        return String.join("; ", lines.stream().map(line -> {
            ChargeRecord charge = context.charges().get(line.chargeId());
            ChargeTypeFacts type = context.types().get(charge.getChargeTypeId());
            return (type.code() != null ? type.code() : type.name()) + " "
                    + YearMonth.from(charge.getServiceFrom()) + " " + line.amount();
        }).toList());
    }

    /** What the plot owes: the sum of what is open on its charges that money can be placed on. */
    static Money debt(PlotLedgerState state) {
        return AllocationPlanner.openInOrder(state.charges()).stream().map(ChargeState::open)
                .reduce(Money.ZERO, Money::plus);
    }

    /** A payment just saved, booked in {@code month}. */
    static SavedPayment saved(PaymentRecord payment, BillingMonthRecord month) {
        SavedPayment saved = new SavedPayment(payment.getId(), payment.getGroupId(), payment.getPlotId(),
                payment.getAmount().toString(), PaymentSource.fromValue(payment.getSource().name()),
                payment.getReceivedOn(), month.yearMonth().toString());
        saved.setReceiptNumber(payment.getReceiptNumber());
        return saved;
    }

    /** The payer of a payment; null when none is named or the member is not found. */
    static @Nullable PaymentPayer payer(@Nullable UUID payerId, Map<UUID, MemberRef> members) {
        MemberRef member = payerId == null ? null : members.get(payerId);
        return member == null ? null : new PaymentPayer(member.id(), member.shortName());
    }

    /** An allocation or release of a payment with its charge. */
    static PaymentAllocation allocation(AllocationRecord row, Context context) {
        ChargeRecord charge = context.charges().get(row.getChargeId());
        PaymentAllocation dto = new PaymentAllocation(row.getId(), row.getChargeId(),
                ChargeViews.typeRef(context.types().get(charge.getChargeTypeId())), charge.getServiceFrom(),
                charge.getServiceTo(), charge.getAccruedOn(), row.getAmount().toString(), row.getCreatedBy() == null,
                row.getCreatedAt().atOffset(ZoneOffset.UTC));
        if (row.getOrigin() != null) {
            dto.setOrigin(nadel.contract.model.AllocationOrigin.fromValue(row.getOrigin().name()));
        }
        if (row.getReleaseCause() != null) {
            dto.setReleaseCause(AllocationReleaseCause.fromValue(row.getReleaseCause().name()));
        }
        dto.setReleasesAllocationId(row.getReleasesAllocationId());
        return dto;
    }
}
