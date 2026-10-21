package app.nadel.tariffs;

import app.nadel.money.Rate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The terms of one tariff version: from which date, on which basis, how often and at which rate. A fixed one-time
 * amount, the frequency one-off and a due date belong together: {@code FIXED_ONE_TIME} if and only if {@code ONE_OFF} if
 * and only if a due date is present. The constructor enforces it, so no invalid terms exist (the database has the same
 * rule as {@code tariff_version_one_off_ck}).
 *
 * @param validFrom the date the version applies from
 * @param basis     what the rate applies to
 * @param frequency how often
 * @param rate      the rate, exactly as entered (never rounded)
 * @param dueDate   the due date of a one-time amount; null for every other basis
 */
public record VersionTerms(LocalDate validFrom, Basis basis, Frequency frequency, Rate rate,
        @Nullable LocalDate dueDate) {

    /** A way the terms break the one-off rule. */
    public enum Violation {
        /** {@code ONE_OFF} without {@code FIXED_ONE_TIME}, or {@code FIXED_ONE_TIME} without {@code ONE_OFF}. */
        FREQUENCY_BASIS_MISMATCH,
        /** A one-time amount (or a one-off frequency) without a due date. */
        DUE_DATE_REQUIRED,
        /** A due date although the basis is not a one-time amount. */
        DUE_DATE_NOT_ALLOWED
    }

    public VersionTerms {
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(rate, "rate");
        List<Violation> violations = violations(basis, frequency, dueDate);
        if (!violations.isEmpty()) {
            throw new InvalidVersionTermsException(violations);
        }
    }

    /** Every way the basis, frequency and due date break the one-off rule; empty when they agree. */
    public static List<Violation> violations(Basis basis, Frequency frequency, @Nullable LocalDate dueDate) {
        List<Violation> violations = new ArrayList<>();
        boolean fixed = basis == Basis.FIXED_ONE_TIME;
        boolean oneOff = frequency == Frequency.ONE_OFF;
        if (fixed != oneOff) {
            violations.add(Violation.FREQUENCY_BASIS_MISMATCH);
        }
        if ((fixed || oneOff) && dueDate == null) {
            violations.add(Violation.DUE_DATE_REQUIRED);
        } else if (!fixed && !oneOff && dueDate != null) {
            violations.add(Violation.DUE_DATE_NOT_ALLOWED);
        }
        return violations;
    }
}
