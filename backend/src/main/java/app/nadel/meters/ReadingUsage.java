package app.nadel.meters;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Tells whether a charge was produced from the readings of a meter in a date range. A reading can be corrected or deleted
 * only while no charge uses it; after that a correction goes through recalculation (money-rules section 5).
 * <p>
 * This is a port: the meters module defines it and never depends on the module that knows the answer. The ledger
 * (feature 011, {@code LedgerReadingUsage}) depends on meters, so it provides a Spring bean implementing this
 * interface and the meters module finds it by type. Every implementation is asked and the readings count as used when
 * any says so.
 * <p>
 * The question is about a date range and not a single reading because a charge reads the meter on a closed range of
 * days, from one point to another (an interval charge from its previous point to its reading; a run around the values
 * at its ends, which may be interpolated between two readings): changing, inserting or deleting a point anywhere in
 * that range changes what the charge was calculated from. The meters module therefore asks about the days a change on
 * a date reaches: from the day after the previous point to the day before the next point (or the date itself where
 * there is none), so a new reading after a charged interval is still accepted.
 * <p>
 * The method runs inside the caller's tenant-aware transaction, with the meter row locked {@code FOR UPDATE}, so an
 * implementation may query its own tables (under row level security) without opening a transaction of its own. To make
 * the check race-free, whatever creates charges from readings must lock the meter row {@code FOR SHARE} when it charges,
 * which conflicts with that lock.
 */
public interface ReadingUsage {

    /**
     * @param tenantId the cooperative of the meter (always the caller's own)
     * @param meterId  the meter whose readings are about to change
     * @param from     the first day of the span
     * @param to       the last day of the span, not before {@code from}
     * @return {@code true} when at least one charge was produced from the readings of the meter in the span
     */
    boolean isUsed(UUID tenantId, UUID meterId, LocalDate from, LocalDate to);
}
