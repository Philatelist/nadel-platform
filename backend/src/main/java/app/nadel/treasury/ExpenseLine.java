package app.nadel.treasury;

import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One expense as the expenses report lists it ({@link TreasuryReadModel#expenses}, feature 018 §2.12): an original
 * expense (never its reversal row), whether it is reversed and how many receipt files it has.
 *
 * @param amount         the amount spent (above zero)
 * @param counterparty   opened from its sealed column: personal data, shown only to callers with
 *                       {@code CASHBOOK_VIEW}; null when none was entered
 * @param documentNumber the receipt or invoice number as entered (user content); null when none
 * @param documentDate   the date of that document; null when none
 * @param attachments    the number of stored receipt files (0 to 5), {@code GET /expenses/{id}/attachments/{n}}
 */
public record ExpenseLine(UUID expenseId, LocalDate date, Instant createdAt, MoneyAccountKind account,
        UUID categoryId, Money amount, @Nullable String counterparty, @Nullable String documentNumber,
        @Nullable LocalDate documentDate, boolean reversed, int attachments) {

    public ExpenseLine {
        Objects.requireNonNull(expenseId, "expenseId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(amount, "amount");
    }
}
