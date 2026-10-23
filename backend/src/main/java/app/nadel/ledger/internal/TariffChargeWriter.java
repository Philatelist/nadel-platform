package app.nadel.ledger.internal;

import app.nadel.ledger.ChargesBooked;
import app.nadel.ledger.internal.TariffMonthPlanning.PlannedCharge;
import app.nadel.tariffs.ChargeCalculator.Part;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import app.nadel.tariffs.TariffBook;
import app.nadel.tariffs.TariffBook.Snapshot;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the month run and the retry of a month item share: the locks they take and the writing of tariff charges.
 * Everything runs inside the caller's transaction.
 * <p>
 * Lock order: the cooperative's charging lock ({@link ChargingLock}, taken by the caller first), the month advisory
 * lock (one run or retry of a month at a time per cooperative), the queue advisory lock
 * (one booking run or retry of the cooperative at a time, since a run also retries the open items of every other
 * month), the charge types {@code FOR SHARE} (an archive waits), the tariffs {@code FOR SHARE} (a correction of a
 * version or a change of scope waits, then sees the charges: {@link LedgerTariffVersionUsage}), then the billing months
 * {@code FOR SHARE} (a closing waits). Ids are locked in id order. Nothing takes a month advisory lock while it holds
 * the queue lock, and every transaction takes at most one month advisory lock, so the two advisory locks cannot
 * deadlock; a closing takes a single month {@code FOR UPDATE}, so the shared month locks cannot either.
 */
@Component
class TariffChargeWriter {

    /** At most this many plots per {@link ChargesBooked} event. */
    static final int PLOTS_PER_EVENT = 500;

    private final TariffBook book;
    private final ChargeTypeCatalog chargeTypes;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;

    TariffChargeWriter(TariffBook book, ChargeTypeCatalog chargeTypes, ApplicationEventPublisher events,
            EntityManager entityManager) {
        this.book = book;
        this.chargeTypes = chargeTypes;
        this.events = events;
        this.entityManager = entityManager;
    }

    /**
     * The transaction-scoped advisory lock of the month run of {@code month} in the cooperative: a second run or a
     * retry of the same month waits until this transaction ends.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void lockMonth(UUID tenantId, YearMonth month) {
        entityManager.createNativeQuery(
                "select count(*) from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) as l")
                .setParameter("key", "ledger.month-run:" + tenantId + ":" + month)
                .getSingleResult();
    }

    /**
     * The transaction-scoped advisory lock of the "not charged" queue of the cooperative: every booking month run and
     * every retry takes it after its month lock, so the retries of the open items (by a run of any month or by
     * "Повторити") never run side by side and each sees the charges the other booked.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void lockQueue(UUID tenantId) {
        entityManager.createNativeQuery(
                "select count(*) from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) as l")
                .setParameter("key", "ledger.not-charged:" + tenantId)
                .getSingleResult();
    }

    /**
     * Locks every charge type and then every tariff of the cooperative {@code FOR SHARE} (each in id order), and reads
     * the tariffs under the lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Snapshot lockTariffs() {
        for (UUID type : new TreeSet<>(chargeTypes.all().stream().map(ChargeTypeFacts::id).toList())) {
            chargeTypes.findAndLockShared(type);
        }
        book.lockShared(book.tariffIds());
        return book.snapshot();
    }

    /**
     * Writes the charges (each with its parts, one per version) in the billing month; the charges first, then the
     * parts, so the foreign keys hold whatever order the batch takes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<ChargeRecord> book(UUID tenantId, List<PlannedCharge> planned, UUID billingMonthId,
            @Nullable UUID createdBy, Instant now) {
        List<ChargeRecord> written = new ArrayList<>(planned.size());
        for (PlannedCharge charge : planned) {
            ChargeRecord record = ChargeRecord.tariff(tenantId, charge.plotId(), charge.chargeTypeId(),
                    billingMonthId, charge.tariff().id(), charge.amount(), charge.serviceFrom(), charge.serviceTo(),
                    charge.accruedOn(), charge.sourceKey(), charge.calculation(), createdBy, now);
            entityManager.persist(record);
            written.add(record);
        }
        entityManager.flush();
        for (int i = 0; i < planned.size(); i++) {
            writeParts(tenantId, written.get(i).getId(), planned.get(i));
        }
        entityManager.flush();
        return written;
    }

    /**
     * Writes a charge the recalculation {@code recalculationId} books (feature 012): the planned charge with
     * {@code sourceKey} (its base key, or {@code base#r{recalculation}} when a charge has the base key) and its parts,
     * in the billing month. Flushes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    ChargeRecord bookRecalculated(UUID tenantId, PlannedCharge charge, UUID billingMonthId, String sourceKey,
            UUID recalculationId, @Nullable UUID createdBy, Instant now) {
        ChargeRecord record = ChargeRecord.tariff(tenantId, charge.plotId(), charge.chargeTypeId(), billingMonthId,
                charge.tariff().id(), charge.amount(), charge.serviceFrom(), charge.serviceTo(), charge.accruedOn(),
                sourceKey, charge.calculation(), recalculationId, createdBy, now);
        entityManager.persist(record);
        entityManager.flush();
        writeParts(tenantId, record.getId(), charge);
        entityManager.flush();
        return record;
    }

    /**
     * Writes one {@code charge_part} per version of the planned charge for {@code chargeId}: the charge itself, or an
     * adjustment of a recalculation, whose parts are the versions of the expected charge (feature 012).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void writeParts(UUID tenantId, UUID chargeId, PlannedCharge charge) {
        List<UUID> versionIds = charge.versionIds();
        List<Part> parts = charge.breakdown().parts();
        for (int p = 0; p < parts.size(); p++) {
            Part part = parts.get(p);
            entityManager.persist(ChargePartRecord.of(tenantId, chargeId, versionIds.get(p), part.days(),
                    part.units(), part.terms().rate().value()));
        }
    }

    /** Publishes {@link ChargesBooked} for the plots, at most {@value #PLOTS_PER_EVENT} plots per event. */
    void publishBooked(UUID tenantId, Set<UUID> plotIds) {
        publishBooked(events, tenantId, plotIds);
    }

    /**
     * Publishes {@link ChargesBooked} for the plots through {@code events}, at most {@value #PLOTS_PER_EVENT} plots
     * per event, the plots in id order; nothing for no plots. Also for a booking outside the tariff runs (the opening
     * balances of the Excel import).
     */
    static void publishBooked(ApplicationEventPublisher events, UUID tenantId, Set<UUID> plotIds) {
        List<UUID> all = new ArrayList<>(new TreeSet<>(plotIds));
        for (int from = 0; from < all.size(); from += PLOTS_PER_EVENT) {
            events.publishEvent(new ChargesBooked(tenantId,
                    Set.copyOf(all.subList(from, Math.min(all.size(), from + PLOTS_PER_EVENT)))));
        }
    }
}
