package app.nadel.documents.internal;

import app.nadel.documents.internal.InvoiceFigures.TypeRow;
import app.nadel.ledger.InvoiceLedger;
import app.nadel.ledger.InvoiceLedger.TypeMovement;
import app.nadel.money.Money;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The figures of an invoice from the plot's ledger of the month (feature 016 §2.3, technical considerations 2.3).
 * Pure: the booking-month rule is the ledger's ({@code InvoiceLedger}); here the rows are ordered and summed.
 * <ul>
 * <li>A charge type whose four figures are {@code 0.00} and that has no line is left out; the rows follow the
 * cooperative's charge type order (the order of {@code ChargeTypeCatalog#all}, an unknown type last).</li>
 * <li>{@code endDebt} = the sum of the rows' end debts; {@code received} = the sum of the payments booked after the
 * month and received before the invoice; {@code due} = {@code max(0.00, endDebt - received)}.</li>
 * <li>The prepayment is shown as it is and never lowers «До сплати» (an assumption of the technical
 * considerations).</li>
 * </ul>
 * Exact {@code Money} sums, nothing rounded.
 */
final class InvoiceCalculator {

    private InvoiceCalculator() {
    }

    /**
     * The figures of the invoice.
     *
     * @param ledger the plot's ledger of the month
     * @param types  the cooperative's charge types in their order ({@code ChargeTypeCatalog#all})
     */
    static InvoiceFigures of(InvoiceLedger ledger, List<ChargeTypeFacts> types) {
        Objects.requireNonNull(ledger, "ledger");
        Map<UUID, Integer> positions = new HashMap<>();
        for (int i = 0; i < types.size(); i++) {
            positions.put(types.get(i).id(), i);
        }
        Comparator<UUID> typeOrder = Comparator.comparingInt((UUID id) -> positions.getOrDefault(id,
                Integer.MAX_VALUE)).thenComparing(UUID::toString);

        Set<UUID> withLines = new HashSet<>();
        ledger.lines().forEach(line -> withLines.add(line.chargeTypeId()));
        Map<UUID, TypeMovement> byType = new HashMap<>();
        ledger.types().forEach(type -> byType.put(type.chargeTypeId(), type));
        Set<UUID> shown = new HashSet<>(withLines);
        byType.values().stream().filter(type -> !allZero(type)).forEach(type -> shown.add(type.chargeTypeId()));

        List<TypeRow> rows = shown.stream().sorted(typeOrder).map(id -> {
            TypeMovement type = byType.get(id);
            return type == null ? new TypeRow(id, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO)
                    : new TypeRow(id, type.start(), type.charged(), type.paid(), type.end());
        }).toList();
        List<InvoiceLedger.Line> lines = ledger.lines().stream()
                .sorted(Comparator.comparing(InvoiceLedger.Line::chargeTypeId, typeOrder)
                        .thenComparing(InvoiceLedger.Line::serviceFrom)
                        .thenComparing(InvoiceLedger.Line::serviceTo)
                        .thenComparing(line -> line.chargeId().toString()))
                .toList();

        Money endDebt = rows.stream().map(TypeRow::end).reduce(Money.ZERO, Money::plus);
        Money received = ledger.receivedAfter().stream().map(InvoiceLedger.LaterPayment::amount)
                .reduce(Money.ZERO, Money::plus);
        Money due = endDebt.minus(received);
        return new InvoiceFigures(rows, lines, ledger.receivedAfter(), endDebt, ledger.prepaymentAtEnd(), received,
                due.signum() < 0 ? Money.ZERO : due);
    }

    private static boolean allZero(TypeMovement type) {
        return type.start().signum() == 0 && type.charged().signum() == 0 && type.paid().signum() == 0
                && type.end().signum() == 0;
    }
}
