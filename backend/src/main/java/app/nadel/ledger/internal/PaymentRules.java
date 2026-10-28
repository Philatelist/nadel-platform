package app.nadel.ledger.internal;

import app.nadel.ledger.PaymentSource;
import app.nadel.money.Money;
import app.nadel.problem.ProblemCodes;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.jspecify.annotations.Nullable;

/**
 * The rules of the values of a payment and of a payment reversal (feature 015 §2.1, technical considerations 2.1),
 * as field codes of {@code VALIDATION_FAILED}; null when a value is fine. Pure: no database. The texts are user
 * content and are kept as entered, trimmed ({@link #text}); lengths count characters as PostgreSQL's
 * {@code char_length} does (code points), on the trimmed text.
 */
final class PaymentRules {

    /** The days of payments are days in Kyiv, whatever the zone of the server or the clock. */
    static final ZoneId ZONE = ZoneId.of("Europe/Kyiv");
    /** {@code numeric(14,2)}: at most 12 digits before the point. */
    static final int MAX_INTEGER_DIGITS = 12;
    static final int MAX_NOTE_LENGTH = 200;
    static final int MAX_BANK_REFERENCE_LENGTH = 64;
    static final int MAX_REASON_LENGTH = 500;

    private PaymentRules() {
    }

    /**
     * {@code AMOUNT_NOT_POSITIVE} for zero or less, {@code INVALID} when it does not fit {@code numeric(14,2)}. An
     * amount always has two decimals ({@link Money}; the contract refuses another form).
     */
    static @Nullable String amountProblem(Money amount) {
        if (amount.signum() <= 0) {
            return ProblemCodes.AMOUNT_NOT_POSITIVE;
        }
        if (amount.value().precision() - amount.value().scale() > MAX_INTEGER_DIGITS) {
            return ProblemCodes.INVALID;
        }
        return null;
    }

    /** Today in Kyiv by the clock. */
    static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }

    /** {@code DATE_IN_FUTURE} for a day after today in Kyiv; any earlier day is allowed. */
    static @Nullable String dateProblem(LocalDate receivedOn, Clock clock) {
        return receivedOn.isAfter(today(clock)) ? ProblemCodes.DATE_IN_FUTURE : null;
    }

    /** The text as stored: trimmed; null when there is none or it is blank. */
    static @Nullable String text(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw.strip();
    }

    /** {@code TOO_LONG} for a payer note of more than 200 characters. */
    static @Nullable String payerNoteProblem(@Nullable String note) {
        return tooLong(note, MAX_NOTE_LENGTH);
    }

    /**
     * {@code INVALID} for a bank reference on a cash payment (it has a receipt number instead), {@code TOO_LONG} for
     * one of more than 64 characters.
     */
    static @Nullable String bankReferenceProblem(@Nullable String reference, PaymentSource source) {
        String stored = text(reference);
        if (stored == null) {
            return null;
        }
        if (source != PaymentSource.BANK) {
            return ProblemCodes.INVALID;
        }
        return tooLong(stored, MAX_BANK_REFERENCE_LENGTH);
    }

    /** {@code REQUIRED} for a missing or blank reason of a reversal, {@code TOO_LONG} for more than 500 characters. */
    static @Nullable String reasonProblem(@Nullable String reason) {
        String stored = text(reason);
        if (stored == null) {
            return ProblemCodes.REQUIRED;
        }
        return tooLong(stored, MAX_REASON_LENGTH);
    }

    private static @Nullable String tooLong(@Nullable String raw, int max) {
        String stored = text(raw);
        return stored != null && stored.codePointCount(0, stored.length()) > max ? ProblemCodes.TOO_LONG : null;
    }
}
