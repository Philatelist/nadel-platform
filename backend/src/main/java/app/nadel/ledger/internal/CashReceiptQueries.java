package app.nadel.ledger.internal;

import app.nadel.ledger.CashReceiptData;
import app.nadel.ledger.CashReceiptData.Part;
import app.nadel.ledger.CashReceiptData.Placement;
import app.nadel.money.Money;
import app.nadel.tariffs.ChargeTypeCatalog;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the payment group of a printed cash receipt ({@code LedgerReadModel#cashReceipt}, feature 016 §2.8): the
 * payment asked, the other parts of its group and their reversals, and the net of each part's allocations per charge
 * (only charges with more than {@code 0.00}), ordered as money is placed ({@link AllocationPlanner#ORDER}: debt date,
 * charge type position, service start, booking time, id). Four small reads in the caller's transaction, under RLS.
 */
@Component
class CashReceiptQueries {

    private static final Comparator<PaymentRecord> ENTERED = Comparator.comparing(PaymentRecord::getCreatedAt)
            .thenComparing(payment -> payment.getId().toString());

    private final PaymentRepository payments;
    private final AllocationRepository allocations;
    private final ChargeRepository charges;
    private final BillingMonthRepository months;
    private final ChargeTypeCatalog chargeTypes;

    CashReceiptQueries(PaymentRepository payments, AllocationRepository allocations, ChargeRepository charges,
            BillingMonthRepository months, ChargeTypeCatalog chargeTypes) {
        this.payments = payments;
        this.allocations = allocations;
        this.charges = charges;
        this.months = months;
        this.chargeTypes = chargeTypes;
    }

    /** The group of the payment; empty for an unknown id and for a payment reversal row. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<CashReceiptData> find(UUID paymentId) {
        Optional<PaymentRecord> asked = payments.findById(paymentId)
                .filter(payment -> payment.getKind() == PaymentKind.PAYMENT);
        if (asked.isEmpty()) {
            return Optional.empty();
        }
        PaymentRecord payment = asked.get();
        List<PaymentRecord> group = payments.findByGroupId(payment.getGroupId());
        Map<UUID, Instant> reversedAt = new HashMap<>();
        group.stream().filter(row -> row.getReversesPaymentId() != null)
                .forEach(row -> reversedAt.put(row.getReversesPaymentId(), row.getCreatedAt()));
        List<PaymentRecord> parts = group.stream().filter(row -> row.getKind() == PaymentKind.PAYMENT).sorted(ENTERED)
                .toList();

        Map<UUID, Map<UUID, Money>> netByPart = new HashMap<>();
        allocations.findByPaymentIdIn(parts.stream().map(PaymentRecord::getId).toList())
                .forEach(row -> netByPart.computeIfAbsent(row.getPaymentId(), id -> new HashMap<>())
                        .merge(row.getChargeId(), row.getAmount(), Money::plus));
        List<UUID> chargeIds = netByPart.values().stream().flatMap(net -> net.keySet().stream()).distinct().toList();
        Map<UUID, ChargeRecord> chargeById = chargeIds.isEmpty() ? Map.of()
                : charges.findAllById(chargeIds).stream()
                        .collect(Collectors.toMap(ChargeRecord::getId, Function.identity()));
        Map<UUID, BillingMonthRecord> monthById = chargeIds.isEmpty() ? Map.of()
                : months.findAll().stream().collect(Collectors.toMap(BillingMonthRecord::getId, Function.identity()));
        Map<UUID, Integer> positions = OpenAmounts.positions(chargeTypes.all());
        Comparator<ChargeRecord> order = Comparator.comparing(ChargeRecord::getAccruedOn)
                .thenComparingInt(charge -> positions.getOrDefault(charge.getChargeTypeId(), Integer.MAX_VALUE))
                .thenComparing(ChargeRecord::getServiceFrom)
                .thenComparing(ChargeRecord::getCreatedAt)
                .thenComparing(charge -> charge.getId().toString());

        List<Part> receiptParts = parts.stream().map(part -> {
            Instant reversal = reversedAt.get(part.getId());
            Map<UUID, Money> net = netByPart.getOrDefault(part.getId(), Map.of());
            List<Placement> placements = net.entrySet().stream().filter(entry -> entry.getValue().signum() > 0)
                    .map(entry -> chargeById.get(entry.getKey())).sorted(order)
                    .map(charge -> new Placement(charge.getId(), charge.getChargeTypeId(),
                            charge.getKind() == ChargeKind.OPENING,
                            monthById.get(charge.getBillingMonthId()).yearMonth(), charge.getServiceFrom(),
                            charge.getServiceTo(), net.get(charge.getId())))
                    .toList();
            Money placed = placements.stream().map(Placement::amount).reduce(Money.ZERO, Money::plus);
            Money unallocated = reversal != null ? Money.ZERO : part.getAmount().minus(placed);
            return new Part(part.getId(), part.getPlotId(), part.getAmount(), reversal, placements,
                    unallocated.signum() < 0 ? Money.ZERO : unallocated);
        }).toList();

        PaymentRecord first = parts.getFirst();
        return Optional.of(new CashReceiptData(payment.getGroupId(), first.getReceiptNumber(), first.getSource(),
                first.getReceivedOn(), first.getCreatedBy(), first.getPayerMemberId(), receiptParts));
    }
}
