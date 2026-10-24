package app.nadel.meters;

import app.nadel.audit.AuditEvent;
import app.nadel.identity.AccessProfile;
import app.nadel.meters.ConsumptionCalculator.Reading;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Changes of readings that a recalculation books together with the change (feature 012 §2.5): a reading entered inside
 * a charged interval, or a charged reading corrected or deleted. The ledger calls it inside its own transaction (a
 * preview rolls the change back, a booking keeps it), so every method requires a running transaction
 * ({@code Propagation.MANDATORY}) and opens none.
 * <p>
 * Each method takes the row locks and runs the rules of the reading endpoints ({@code POST /readings},
 * {@code PUT} and {@code DELETE /readings/{id}}): the meter {@code FOR UPDATE} (404 for an unknown meter or reading, or
 * one of another cooperative; an unknown meter of an entry with the field {@code meterId}), 409
 * {@code READING_STATUS_INVALID} for a reading that does not count, the reading rules (409
 * {@code READING_DATE_IN_FUTURE}, {@code READING_DATE_OUTSIDE_METER}, {@code READING_OUT_OF_ORDER} with the field
 * {@code takenOn} or {@code value}), the same supersede rules (a reading of the same date is replaced, a correction is
 * a new row that supersedes the corrected one), but <b>no</b> use check ({@link ReadingUsage}): a reading that charges
 * used is changed here, and the recalculation corrects the charges. They do <b>not</b> publish
 * {@link PlotMeterPointsChanged}: the recalculation charges the affected intervals itself, so the ledger's listener
 * cannot book them a second time. The audit entry of the change is returned, not published: the caller publishes it
 * with the recalculation's entry, or drops it with a rolled-back preview.
 */
public interface ReadingChanges {

    /**
     * A change applied in the caller's transaction.
     *
     * @param meterId   the meter of the reading
     * @param kind      the kind of the meter (only a plot meter's intervals are charged)
     * @param resource  what it measures
     * @param plotId    the plot of a plot meter; null for the other kinds
     * @param readingId the counted reading after the change (entered, or the correction's new row; a new id on every
     *                  call); null for a deletion
     * @param before    the counted reading the change replaced, corrected or deleted; null for an entry on a new date
     * @param after     the counted reading after the change; null for a deletion
     * @param from      the first day the change reaches: the day after the point before the changed date (the
     *                  earliest of the old and new date of a correction), or that date when there is none
     * @param to        the last day the change reaches: the day before the point after the changed date (the latest of
     *                  the old and new date), or that date when there is none; {@code from} to {@code to} is the span
     *                  {@link ReadingUsage} is asked about by the reading endpoints
     * @param through   the date of the last point whose interval the change alters: the point after the latest changed
     *                  date, or that date when there is none; the intervals closed by a point from {@code from} to
     *                  {@code through} are the ones the change can alter
     * @param audit     the audit entry of the change ({@code READING_ENTERED}, {@code READING_REPLACED},
     *                  {@code READING_CORRECTED} with the changed fields only, {@code READING_DELETED}), not published;
     *                  null when nothing changed (the same reading again)
     */
    record Change(UUID meterId, MeterKind kind, MeterResource resource, @Nullable UUID plotId,
            @Nullable UUID readingId, @Nullable Reading before, @Nullable Reading after, LocalDate from,
            LocalDate to, LocalDate through, @Nullable AuditEvent audit) {

        public Change {
            Objects.requireNonNull(meterId, "meterId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(through, "through");
            if (to.isBefore(from) || through.isBefore(from)) {
                throw new IllegalArgumentException("A change does not reach back before it starts");
            }
        }
    }

    /** Enters a reading of the meter (a value with up to three decimals as text) on the date. */
    Change enter(UUID meterId, LocalDate takenOn, String value, AccessProfile actor);

    /** Corrects the date and value of a counted reading. */
    Change correct(UUID readingId, LocalDate takenOn, String value, AccessProfile actor);

    /** Deletes a counted reading; the intervals around it join. */
    Change delete(UUID readingId, AccessProfile actor);
}
