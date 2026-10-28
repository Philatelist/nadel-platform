package app.nadel.ledger.internal;

import app.nadel.members.MemberRef;
import app.nadel.money.Money;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.AllocationReleaseCause;
import nadel.contract.model.PaymentAllocation;
import nadel.contract.model.PaymentPartProposal;
import nadel.contract.model.PaymentPayer;
import nadel.contract.model.PaymentProposalLine;
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
     * The proposal of {@code amount} on the plot: every open charge of the plot in planner order with what the lines
     * place on it ({@code 0.00} when nothing), the total placed and what the payment leaves as prepayment. The lines
     * are valid (each an open charge of the plot).
     */
    PaymentPartProposal part(UUID plotId, String plotNumber, Money amount, PlotLedgerState state,
            List<AllocationPlanner.Line> lines) {
        Map<UUID, Money> placed = new HashMap<>();
        Money total = Money.ZERO;
        for (AllocationPlanner.Line line : lines) {
            placed.merge(line.chargeId(), line.amount(), Money::plus);
            total = total.plus(line.amount());
        }
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
        return new PaymentPartProposal(plotId, plotNumber, amount.toString(), rows, total.toString(),
                amount.minus(total).toString());
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
