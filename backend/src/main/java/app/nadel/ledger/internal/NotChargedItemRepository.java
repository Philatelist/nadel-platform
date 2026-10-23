package app.nadel.ledger.internal;

import app.nadel.ledger.internal.NotChargedItemRecord.Kind;
import app.nadel.ledger.internal.NotChargedItemRecord.Status;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface NotChargedItemRepository extends JpaRepository<NotChargedItemRecord, UUID> {

    /** The items with the status, in no particular order. */
    List<NotChargedItemRecord> findByStatus(Status status);

    /** The open item with the key, if any (at most one, unique index {@code not_charged_item_open_key_uq}). */
    default Optional<NotChargedItemRecord> findOpen(String itemKey) {
        return findByItemKeyAndStatus(itemKey, Status.OPEN);
    }

    Optional<NotChargedItemRecord> findByItemKeyAndStatus(String itemKey, Status status);

    /** The items of a kind and a status, in no particular order. */
    List<NotChargedItemRecord> findByKindAndStatus(Kind kind, Status status);

    /** The items of a kind and a status about one meter. */
    List<NotChargedItemRecord> findByKindAndStatusAndMeterId(Kind kind, Status status, UUID meterId);

    /** The items of a kind, a status and a billing month (its first day). */
    List<NotChargedItemRecord> findByKindAndStatusAndMonth(Kind kind, Status status, LocalDate month);

    /**
     * The open items of the month: those of the month itself, and those without a month whose days end in it (a
     * reading interval, a run range).
     */
    @Query("""
            select count(i) from NotChargedItemRecord i
            where i.status = app.nadel.ledger.internal.NotChargedItemRecord.Status.OPEN
              and (i.month = :first or (i.month is null and i.toDate between :first and :last))
            """)
    long countOpenOfMonth(@Param("first") LocalDate first, @Param("last") LocalDate last);
}
