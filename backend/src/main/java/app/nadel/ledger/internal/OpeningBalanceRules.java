package app.nadel.ledger.internal;

import app.nadel.audit.FieldChange;
import app.nadel.money.Money;
import app.nadel.problem.ProblemCodes;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The rules of an opening balance's values (feature 011 §2.9), shared by the entry on the plot account
 * ({@link OpeningBalances}) and the "Opening balances" sheet of the Excel import ({@link OpeningBalanceImportService}),
 * so the two can never disagree. Pure: no database.
 */
final class OpeningBalanceRules {

    /** {@code numeric(14,2)}: at most 12 digits before the point. */
    static final int MAX_INTEGER_DIGITS = 12;
    /** The length the {@code charge} table allows for a reason ({@code charge_reason_ck}). */
    static final int MAX_REASON_LENGTH = 500;

    private OpeningBalanceRules() {
    }

    /** {@code AMOUNT_NOT_POSITIVE} for zero or less, {@code INVALID} when it does not fit {@code numeric(14,2)}. */
    static @Nullable String amountProblem(Money amount) {
        if (amount.signum() <= 0) {
            return ProblemCodes.AMOUNT_NOT_POSITIVE;
        }
        if (amount.value().precision() - amount.value().scale() > MAX_INTEGER_DIGITS) {
            return ProblemCodes.INVALID;
        }
        return null;
    }

    /** {@code DATE_IN_FUTURE} for a date after today (Europe/Kyiv, the caller's clock). */
    static @Nullable String dateProblem(LocalDate date, LocalDate today) {
        return date.isAfter(today) ? ProblemCodes.DATE_IN_FUTURE : null;
    }

    /** The reason as stored: trimmed; null when there is none or it is blank. */
    static @Nullable String reason(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw.strip();
    }

    /** The audit values of a booked opening balance: codes and parameters; the reason (user content) when given. */
    static List<FieldChange> auditChanges(ChargeTypeFacts type, Money amount, LocalDate date, YearMonth month,
            @Nullable String reason) {
        List<FieldChange> changes = new ArrayList<>(List.of(
                new FieldChange("chargeType", null, type.auditName()),
                new FieldChange("amount", null, amount.toString()),
                new FieldChange("date", null, date.toString()),
                new FieldChange("billingMonth", null, month.toString())));
        if (reason != null) {
            changes.add(new FieldChange("reason", null, reason));
        }
        return changes;
    }
}
