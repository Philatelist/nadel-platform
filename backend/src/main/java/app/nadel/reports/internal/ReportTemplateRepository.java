package app.nadel.reports.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The templates of the caller's cooperative. Every query names the cooperative on top of row level security. */
interface ReportTemplateRepository extends JpaRepository<ReportTemplateRecord, UUID> {

    /**
     * Every template of the cooperative: active ones before archived ones, each group by name ignoring case (the same
     * {@code lower} as the unique index), the id breaking ties.
     */
    @Query("select t from ReportTemplateRecord t where t.tenantId = :tenantId order by "
            + "case when t.status = app.nadel.reports.internal.TemplateStatus.ACTIVE then 0 else 1 end, "
            + "lower(t.name), t.id")
    List<ReportTemplateRecord> findAllInOrder(@Param("tenantId") UUID tenantId);

    /** The template, without a lock; empty for an unknown id and for another cooperative's template. */
    @Query("select t from ReportTemplateRecord t where t.id = :id and t.tenantId = :tenantId")
    Optional<ReportTemplateRecord> findOne(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /**
     * The template with an exclusive row lock ({@code FOR UPDATE}) until the end of the transaction: two uploads of a
     * version, or an upload and the archive, run one after another, so version numbers are consecutive.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ReportTemplateRecord t where t.id = :id and t.tenantId = :tenantId")
    Optional<ReportTemplateRecord> findOneForUpdate(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /** Whether an active template of the cooperative has the name, ignoring case as the unique index does. */
    @Query("select count(t) > 0 from ReportTemplateRecord t where t.tenantId = :tenantId and "
            + "t.status = app.nadel.reports.internal.TemplateStatus.ACTIVE and "
            + "lower(t.name) = lower(:name)")
    boolean existsActiveName(@Param("tenantId") UUID tenantId, @Param("name") String name);

    /** Whether an active template of the cooperative is in the slot. */
    @Query("select count(t) > 0 from ReportTemplateRecord t where t.tenantId = :tenantId and "
            + "t.status = app.nadel.reports.internal.TemplateStatus.ACTIVE and t.slot = :slot")
    boolean existsActiveSlot(@Param("tenantId") UUID tenantId, @Param("slot") String slot);
}
