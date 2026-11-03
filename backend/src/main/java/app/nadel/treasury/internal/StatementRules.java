package app.nadel.treasury.internal;

import app.nadel.money.Money;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/**
 * The rules of the four figures of a monthly bank statement (feature 018 §2.7, worked example 2.17 #9; technical
 * considerations 2.5), pure: {@code incoming} and {@code outgoing} are positive figures as the statement shows them
 * (0.00 or more), {@code opening} and {@code closing} may be below 0.00 (an overdraft), each has at most 12 integer
 * digits ({@code numeric(14,2)}), and they agree: {@code opening + incoming − outgoing = closing}. The table's CHECK
 * {@code bank_reconciliation_figures_ck} (V32) is the database's backstop of the same rules.
 */
final class StatementRules {

    private StatementRules() {
    }

    /** The four figures of a bank month: as a statement, as the books, or as their differences. */
    record Figures(Money opening, Money incoming, Money outgoing, Money closing) {

        Figures {
            Objects.requireNonNull(opening, "opening");
            Objects.requireNonNull(incoming, "incoming");
            Objects.requireNonNull(outgoing, "outgoing");
            Objects.requireNonNull(closing, "closing");
        }
    }

    /** Whether the figures agree: {@code opening + incoming − outgoing = closing}. */
    static boolean consistent(Figures statement) {
        return statement.opening().plus(statement.incoming()).minus(statement.outgoing()).equals(statement.closing());
    }

    /**
     * The problems of the figures as fields ({@code INVALID}: {@code incoming} or {@code outgoing} below 0.00, any
     * figure with more than 12 integer digits); empty when each fits.
     */
    static List<FieldProblem> fieldProblems(Figures statement) {
        List<FieldProblem> problems = new ArrayList<>();
        check(problems, "opening", statement.opening(), true);
        check(problems, "incoming", statement.incoming(), false);
        check(problems, "outgoing", statement.outgoing(), false);
        check(problems, "closing", statement.closing(), true);
        return problems;
    }

    /**
     * Checks the figures entered: 400 {@code VALIDATION_FAILED} with the field problems, then 400
     * {@code STATEMENT_INCONSISTENT} (field {@code closing}) when they do not agree.
     */
    static void check(Figures statement) {
        EntryChecks.refuse(fieldProblems(statement));
        if (!consistent(statement)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.STATEMENT_INCONSISTENT,
                    "The figures of the bank statement do not agree: opening + incoming - outgoing differs from closing.",
                    List.of(new FieldProblem("closing", ProblemCodes.STATEMENT_INCONSISTENT)));
        }
    }

    private static void check(List<FieldProblem> problems, String field, Money value, boolean signed) {
        if ((!signed && value.signum() < 0) || MoneyAccounts.integerDigits(value) > EntryChecks.MAX_INTEGER_DIGITS) {
            problems.add(new FieldProblem(field, ProblemCodes.INVALID));
        }
    }
}
