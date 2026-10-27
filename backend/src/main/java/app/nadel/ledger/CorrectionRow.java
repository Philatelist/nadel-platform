package app.nadel.ledger;

import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One reversal or adjustment of the ledger ({@link LedgerReadModel#corrections}, the corrections report of feature 013
 * §3.8), with the charge it corrects. Ids, codes, dates and {@code Money} only; the reason is user content (shown as
 * entered, never logged).
 *
 * @param chargeId            the correction row itself
 * @param kind                reversal or adjustment
 * @param bookedAt            when it was booked ({@code created_at})
 * @param bookedOn            the day it was booked, in the cooperative's time zone (Europe/Kyiv)
 * @param billingMonth        the billing month it is booked in
 * @param plotId              the plot (that of the corrected charge)
 * @param chargeTypeId        the charge type (that of the corrected charge)
 * @param originalChargeId    the corrected charge: the one a reversal reverses or an adjustment adjusts
 * @param originalServiceFrom the first service day of the corrected charge
 * @param originalServiceTo   the last service day of the corrected charge
 * @param originalAmount      the amount the corrected charge was booked with (not its effective amount)
 * @param amount              the amount of the correction, signed as booked: a reversal is negative, an adjustment
 *                            either sign
 * @param source              who or what booked it (see {@link CorrectionSource} for the mapping)
 * @param recalculationId     the recalculation that booked it; null unless {@code source} is
 *                            {@link CorrectionSource#RECALCULATION}
 * @param cause               the cause of that recalculation; null without one
 * @param reason              the reason booked with the correction: the reason of a hand or run reversal, or the
 *                            recalculation's reason (a recalculation stores its reason on every row it books)
 * @param bookedBy            the member who booked it; null when unknown (a row written without an author)
 */
public record CorrectionRow(UUID chargeId, CorrectionKind kind, Instant bookedAt, LocalDate bookedOn,
        YearMonth billingMonth, UUID plotId, UUID chargeTypeId, UUID originalChargeId, LocalDate originalServiceFrom,
        LocalDate originalServiceTo, Money originalAmount, Money amount, CorrectionSource source,
        @Nullable UUID recalculationId, @Nullable CorrectionCause cause, String reason, @Nullable UUID bookedBy) {

    public CorrectionRow {
        Objects.requireNonNull(chargeId, "chargeId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(bookedAt, "bookedAt");
        Objects.requireNonNull(bookedOn, "bookedOn");
        Objects.requireNonNull(billingMonth, "billingMonth");
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(chargeTypeId, "chargeTypeId");
        Objects.requireNonNull(originalChargeId, "originalChargeId");
        Objects.requireNonNull(originalServiceFrom, "originalServiceFrom");
        Objects.requireNonNull(originalServiceTo, "originalServiceTo");
        Objects.requireNonNull(originalAmount, "originalAmount");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(reason, "reason");
        if ((cause == null) != (recalculationId == null)) {
            throw new IllegalArgumentException("A cause goes with a recalculation and only with one");
        }
    }
}
