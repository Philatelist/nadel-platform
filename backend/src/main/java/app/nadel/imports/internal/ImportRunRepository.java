package app.nadel.imports.internal;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ImportRunRepository extends JpaRepository<ImportRun, UUID> {

    /**
     * Drops the plan (personal data) of previews that expired: they can no longer be committed. Runs on every import
     * call of the cooperative, since there is no scheduled job yet. Returns the number of runs cleared.
     */
    @Modifying
    @Query("update ImportRun r set r.plan = null, r.planDigest = null where r.tenantId = :tenantId "
            + "and r.status = app.nadel.imports.internal.ImportStatus.PREVIEW and r.expiresAt < :now "
            + "and r.plan is not null")
    int clearExpiredPlans(@Param("tenantId") UUID tenantId, @Param("now") Instant now);

    /** The run with an exclusive row lock ({@code FOR UPDATE}) until the end of the transaction. RLS hides other cooperatives' runs. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ImportRun r where r.id = :id")
    Optional<ImportRun> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Takes a transaction-scoped advisory lock for the imports of the cooperative (released at commit or rollback):
     * two commits of one cooperative run one after another, whatever runs they are for. The key differs from the one
     * of the chairman lock. Returns 1; the select only exists to run the function.
     */
    @Query(value = "select count(*) from (select pg_advisory_xact_lock("
            + "hashtextextended(cast(:tenantId as text) || '/imports', 0))) l", nativeQuery = true)
    long lockImports(@Param("tenantId") UUID tenantId);

    /** Marks a preview committed and drops its plan. Flushes first, clears the persistence context afterwards. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ImportRun r set r.status = app.nadel.imports.internal.ImportStatus.COMMITTED, "
            + "r.committedAt = :now, r.plan = null, r.planDigest = null where r.id = :id "
            + "and r.status = app.nadel.imports.internal.ImportStatus.PREVIEW")
    int markCommitted(@Param("id") UUID id, @Param("now") Instant now);

    /** Marks a preview stale and drops its plan (personal data). */
    @Modifying
    @Query("update ImportRun r set r.status = app.nadel.imports.internal.ImportStatus.STALE, "
            + "r.plan = null, r.planDigest = null where r.id = :id "
            + "and r.status = app.nadel.imports.internal.ImportStatus.PREVIEW")
    int markStale(@Param("id") UUID id);

    /** Drops the plan of an expired preview; its status stays PREVIEW (shown as expired once past its expiry). */
    @Modifying
    @Query("update ImportRun r set r.plan = null, r.planDigest = null where r.id = :id "
            + "and r.status = app.nadel.imports.internal.ImportStatus.PREVIEW")
    int dropPlan(@Param("id") UUID id);

    /**
     * One page of the history, newest first. Selects only the history columns: the plan (personal data) is never
     * loaded. The id breaks ties between uploads of the same instant, so pages never overlap.
     */
    @Query(value = "select new app.nadel.imports.internal.ImportHistoryRow(r.id, r.fileName, r.createdByMemberId, "
            + "r.createdByName, r.createdAt, r.committedAt, r.expiresAt, r.status, r.counts, r.problemCount) "
            + "from ImportRun r where r.tenantId = :tenantId order by r.createdAt desc, r.id desc",
            countQuery = "select count(r) from ImportRun r where r.tenantId = :tenantId")
    Page<ImportHistoryRow> history(@Param("tenantId") UUID tenantId, Pageable pageable);
}
