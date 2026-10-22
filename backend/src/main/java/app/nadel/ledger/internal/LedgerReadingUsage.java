package app.nadel.ledger.internal;

import app.nadel.meters.ReadingUsage;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger's answer to the meters port {@link ReadingUsage} (feature 011 §2.11): the readings of a meter in a span are
 * used when a charge that is not reversed read the meter on a day of the span. A charge reads its meter on the closed
 * range of days between the first and the last point it used (its {@code meter_value_use} rows: an interval charge the
 * two points of its interval), so the question is whether that range meets the span. The meters module asks about the
 * days a change reaches, from the day after the previous point to the day before the next one: a correction or deletion
 * of a charged reading (or a reading inserted inside a charged interval) is refused, a new reading after a charged
 * interval is accepted, and after the reversal of the charge the reading is free again. Rows are kept after a reversal
 * (the ledger is history); the reversal is what frees them.
 * <p>
 * Race-free by the port's lock contract: the reading write holds the meter {@code FOR UPDATE} while it asks, and every
 * path that charges from the points holds the meter {@code FOR SHARE} ({@code MeterNetwork.lockSharedPoints}) until it
 * commits. Runs in the caller's transaction, under RLS. Losses and common consumer runs (later slices) add their rows
 * with {@code run_id}.
 */
@Component
class LedgerReadingUsage implements ReadingUsage {

    private final EntityManager entityManager;

    LedgerReadingUsage(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean isUsed(UUID tenantId, UUID meterId, LocalDate from, LocalDate to) {
        Object used = entityManager.createNativeQuery("""
                select exists (
                    select 1 from meter_value_use u
                    where u.meter_id = :meter and u.charge_id is not null
                      and not exists (select 1 from charge r where r.reverses_charge_id = u.charge_id)
                    group by u.charge_id
                    having min(u.used_on) <= :to and max(u.used_on) >= :from)
                """)
                .setParameter("meter", meterId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return Boolean.TRUE.equals(used);
    }
}
