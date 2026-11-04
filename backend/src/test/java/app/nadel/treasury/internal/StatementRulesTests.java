package app.nadel.treasury.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.money.Money;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.treasury.internal.StatementRules.Figures;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The four figures of a bank statement (feature 018 §2.7, worked example 2.17 #9): they agree when opening + incoming
 * − outgoing = closing, the incoming and outgoing figures are never negative, opening and closing may be (an
 * overdraft), and each fits {@code numeric(14,2)}.
 */
class StatementRulesTests {

    @Test
    void example9Opening100In50Out20Closing200IsRefusedAsInconsistentOnTheClosingFigure() {
        Figures statement = figures("100.00", "50.00", "20.00", "200.00");

        assertThat(StatementRules.consistent(statement)).isFalse();
        assertThat(StatementRules.fieldProblems(statement)).isEmpty();
        assertThatThrownBy(() -> StatementRules.check(statement))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("STATEMENT_INCONSISTENT");
                    assertThat(e.errors()).containsExactly(new FieldProblem("closing", "STATEMENT_INCONSISTENT"));
                });
    }

    @Test
    void figuresThatAddUpAreAccepted() {
        // example 7: 20 000,00 + 3 000,00 − 150,00 = 22 850,00
        Figures statement = figures("20000.00", "3000.00", "150.00", "22850.00");

        assertThat(StatementRules.consistent(statement)).isTrue();
        assertThatCode(() -> StatementRules.check(statement)).doesNotThrowAnyException();
        assertThat(StatementRules.consistent(figures("100.00", "50.00", "20.00", "130.00"))).isTrue();
    }

    @Test
    void anOverdraftOpeningAndClosingAreAllowed() {
        Figures overdraft = figures("-50.00", "10.00", "0.00", "-40.00");

        assertThatCode(() -> StatementRules.check(overdraft)).doesNotThrowAnyException();
    }

    @Test
    void negativeIncomingOrOutgoingAndTooManyDigitsAreInvalidFieldsBeforeTheSumIsChecked() {
        Figures negative = figures("0.00", "-1.00", "-2.00", "1.00");
        Figures huge = figures("1000000000000.00", "0.00", "0.00", "1000000000000.00");

        assertThat(StatementRules.fieldProblems(negative)).containsExactly(
                new FieldProblem("incoming", "INVALID"), new FieldProblem("outgoing", "INVALID"));
        assertThat(StatementRules.fieldProblems(huge)).containsExactly(
                new FieldProblem("opening", "INVALID"), new FieldProblem("closing", "INVALID"));
        assertThatThrownBy(() -> StatementRules.check(negative))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
        assertThat(StatementRules.fieldProblems(figures("999999999999.99", "0.00", "0.00", "999999999999.99")))
                .isEmpty();
    }

    private static Figures figures(String opening, String incoming, String outgoing, String closing) {
        return new Figures(Money.parse(opening), Money.parse(incoming), Money.parse(outgoing), Money.parse(closing));
    }
}
