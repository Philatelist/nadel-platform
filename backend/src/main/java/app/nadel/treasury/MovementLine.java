package app.nadel.treasury;

import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One line of an account's journal as the cash book reports show it ({@link TreasuryReadModel#cashBook},
 * {@link TreasuryReadModel#bankMovements}, feature 018 §2.12). The amount is signed (money in positive, out negative);
 * a reversal has the kind of the line it reverses and the opposite amount.
 *
 * @param createdAt      when the line was entered (its time in the report)
 * @param receiptNumber  the receipt number of a cash member payment (for its reversal, the payment's); null otherwise
 * @param documentNumber the bank reference of a bank member payment, the deposit slip of a handover, the document
 *                       number of an expense (for a reversal, its original's); user content; null when none
 * @param plotId         the plot of a member payment line; null otherwise
 * @param counterparty   the counterparty of an expense line (for a reversal, its original's), opened from its sealed
 *                       column: personal data, shown only to callers with {@code CASHBOOK_VIEW}; null otherwise
 * @param categoryId     the category of an expense or other income line; null otherwise
 */
public record MovementLine(UUID movementId, LocalDate date, Instant createdAt, MovementKind kind, boolean reversal,
        Money amount, @Nullable Long receiptNumber, @Nullable String documentNumber, @Nullable UUID plotId,
        @Nullable String counterparty, @Nullable UUID categoryId) {

    public MovementLine {
        Objects.requireNonNull(movementId, "movementId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(amount, "amount");
    }
}
