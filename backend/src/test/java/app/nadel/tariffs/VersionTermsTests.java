package app.nadel.tariffs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.money.Rate;
import app.nadel.tariffs.VersionTerms.Violation;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A fixed one-time amount, the one-off frequency and a due date belong together. */
class VersionTermsTests {

    private static final LocalDate FROM = LocalDate.of(2027, 1, 1);
    private static final LocalDate DUE = LocalDate.of(2027, 5, 1);
    private static final Rate RATE = Rate.parse("50");

    @Test
    void aRecurringVersionHasNoDueDate() {
        for (Basis basis : List.of(Basis.PER_PLOT, Basis.PER_AREA_SOTKA, Basis.PER_KWH, Basis.PER_M3)) {
            for (Frequency frequency : List.of(Frequency.MONTHLY, Frequency.QUARTERLY, Frequency.YEARLY)) {
                VersionTerms terms = new VersionTerms(FROM, basis, frequency, RATE, null);
                assertThat(terms.dueDate()).isNull();
                assertThat(terms.rate()).isEqualTo(RATE);
            }
        }
    }

    @Test
    void aFixedOneTimeAmountIsOneOffWithADueDate() {
        VersionTerms terms = new VersionTerms(FROM, Basis.FIXED_ONE_TIME, Frequency.ONE_OFF, RATE, DUE);

        assertThat(terms.dueDate()).isEqualTo(DUE);
        assertThat(terms.validFrom()).isEqualTo(FROM);
    }

    @Test
    void aFixedAmountWithARecurringFrequencyIsAMismatch() {
        assertThat(violations(Basis.FIXED_ONE_TIME, Frequency.MONTHLY, DUE))
                .containsExactly(Violation.FREQUENCY_BASIS_MISMATCH);
    }

    @Test
    void oneOffWithoutAFixedAmountIsAMismatch() {
        assertThat(violations(Basis.PER_PLOT, Frequency.ONE_OFF, DUE))
                .containsExactly(Violation.FREQUENCY_BASIS_MISMATCH);
    }

    @Test
    void aOneTimeAmountWithoutADueDateIsRefused() {
        assertThat(violations(Basis.FIXED_ONE_TIME, Frequency.ONE_OFF, null))
                .containsExactly(Violation.DUE_DATE_REQUIRED);
    }

    @Test
    void aDueDateWithARecurringBasisIsRefused() {
        assertThat(violations(Basis.PER_PLOT, Frequency.MONTHLY, DUE)).containsExactly(Violation.DUE_DATE_NOT_ALLOWED);
    }

    @Test
    void allBrokenPartsAreReportedTogether() {
        assertThat(violations(Basis.FIXED_ONE_TIME, Frequency.YEARLY, null))
                .containsExactly(Violation.FREQUENCY_BASIS_MISMATCH, Violation.DUE_DATE_REQUIRED);
        assertThat(violations(Basis.PER_PLOT, Frequency.ONE_OFF, null))
                .containsExactly(Violation.FREQUENCY_BASIS_MISMATCH, Violation.DUE_DATE_REQUIRED);
    }

    @Test
    void theConstructorRefusesInvalidTermsAndNulls() {
        assertThatThrownBy(() -> new VersionTerms(FROM, Basis.FIXED_ONE_TIME, Frequency.MONTHLY, RATE, DUE))
                .isInstanceOf(InvalidVersionTermsException.class);
        assertThatThrownBy(() -> new VersionTerms(null, Basis.PER_PLOT, Frequency.MONTHLY, RATE, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new VersionTerms(FROM, Basis.PER_PLOT, Frequency.MONTHLY, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void termsWithoutAnEndRunUntilTheNextVersion() {
        VersionTerms first = new VersionTerms(FROM, Basis.PER_PLOT, Frequency.MONTHLY, RATE, null);
        VersionTerms second = new VersionTerms(FROM.plusMonths(3), Basis.PER_PLOT, Frequency.MONTHLY, RATE, null);

        assertThat(first.validTo()).isNull();
        assertThat(VersionTerms.lastDayOf(List.of(first, second), 0)).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(VersionTerms.lastDayOf(List.of(first, second), 1)).isNull();
    }

    @Test
    void theEndOfAnArchivedTariffEndsTheLastVersion() {
        VersionTerms first = new VersionTerms(FROM, Basis.PER_PLOT, Frequency.MONTHLY, RATE, null);
        VersionTerms last = new VersionTerms(FROM.plusMonths(3), Basis.PER_PLOT, Frequency.MONTHLY, RATE, null)
                .endingOn(LocalDate.of(2027, 6, 30));

        assertThat(last.validTo()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(last.validFrom()).isEqualTo(FROM.plusMonths(3));
        assertThat(VersionTerms.lastDayOf(List.of(first, last), 1)).isEqualTo(LocalDate.of(2027, 6, 30));
        // an own end after the next version's start gives way to the day before it
        VersionTerms longer = first.endingOn(LocalDate.of(2027, 12, 31));
        assertThat(VersionTerms.lastDayOf(List.of(longer, last), 0)).isEqualTo(LocalDate.of(2027, 3, 31));
    }

    @Test
    void aVersionCannotEndBeforeItStarts() {
        assertThatThrownBy(() -> new VersionTerms(FROM, Basis.PER_PLOT, Frequency.MONTHLY, RATE, null,
                FROM.minusDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new VersionTerms(FROM, Basis.PER_PLOT, Frequency.MONTHLY, RATE, null, FROM).validTo())
                .isEqualTo(FROM);
    }

    private static List<Violation> violations(Basis basis, Frequency frequency, LocalDate dueDate) {
        assertThat(VersionTerms.violations(basis, frequency, dueDate)).isNotEmpty();
        try {
            new VersionTerms(FROM, basis, frequency, RATE, dueDate);
            throw new AssertionError("expected InvalidVersionTermsException");
        } catch (InvalidVersionTermsException e) {
            assertThat(e.violations()).isEqualTo(VersionTerms.violations(basis, frequency, dueDate));
            return e.violations();
        }
    }
}
