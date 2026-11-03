package app.nadel.treasury.internal;

import app.nadel.money.Money;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.treasury.internal.AccountBalances.Opening;
import app.nadel.treasury.internal.TreasuryQueries.AccountRow;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * The rules every entry of the cash book shares (feature 018 §2.2, §2.8; technical considerations 2.5): the amount of
 * an entry, the opening balance of its account, the reason of a reversal and the refusal of a second reversal. Pure;
 * the writers ({@link Expenses}, {@link Handovers}, {@link OtherIncomes}) apply them in their own order.
 */
final class EntryChecks {

    /** {@code numeric(14,2)}: twelve digits before the point. */
    static final int MAX_INTEGER_DIGITS = 12;
    /** The upper end of the day totals the cash rule reads: every movement from the opening date on. */
    static final LocalDate LAST_DAY = LocalDate.of(9999, 12, 31);

    private EntryChecks() {
    }

    /** The problem of an entry amount ({@code amount}), or null when it fits: above 0.00, at most 12 integer digits. */
    static @Nullable FieldProblem amountProblem(Money amount) {
        if (amount.signum() <= 0) {
            return new FieldProblem("amount", ProblemCodes.AMOUNT_NOT_POSITIVE);
        }
        if (MoneyAccounts.integerDigits(amount) > MAX_INTEGER_DIGITS) {
            return new FieldProblem("amount", ProblemCodes.INVALID);
        }
        return null;
    }

    /** 400 {@code VALIDATION_FAILED} with the problems, when there are any. */
    static void refuse(List<FieldProblem> errors) {
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", errors);
        }
    }

    /** The account's opening balance; 409 {@code OPENING_BALANCE_MISSING} without one, 400 when dated before it. */
    static Opening requireOpening(AccountRow account, LocalDate date) {
        Opening opening = account.opening();
        if (opening == null) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.OPENING_BALANCE_MISSING,
                    "The account has no opening balance yet.");
        }
        if (date.isBefore(opening.date())) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.BEFORE_OPENING_BALANCE,
                    "The entry is dated before the opening balance of the account.",
                    List.of(new FieldProblem("date", ProblemCodes.BEFORE_OPENING_BALANCE)));
        }
        return opening;
    }

    /** The trimmed reason of a reversal; 400 {@code reason} {@code REQUIRED} when blank. */
    static String requireReason(@Nullable String raw) {
        String reason = blankToNull(raw);
        if (reason == null) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("reason", ProblemCodes.REQUIRED)));
        }
        return reason;
    }

    static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    static ApiProblemException alreadyReversed() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.ENTRY_ALREADY_REVERSED,
                "The entry has already been reversed.");
    }
}
