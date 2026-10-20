package app.nadel.imports.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
