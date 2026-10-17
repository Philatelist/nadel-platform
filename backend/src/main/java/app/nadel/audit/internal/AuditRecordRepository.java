package app.nadel.audit.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AuditRecordRepository extends JpaRepository<AuditRecord, UUID> {

    /**
     * One page of the audit log of a cooperative, newest first. Every filter is optional (null means "any") and
     * combines with AND. {@code from} is inclusive, {@code toExclusive} exclusive. The order is fixed here, so the
     * {@code Pageable} must be unsorted; the id breaks ties so paging is stable.
     */
    @Query("""
            select r from AuditRecord r
            where r.tenantId = :tenantId
              and (:entityType is null or r.entityType = :entityType)
              and (:entityId is null or r.entityId = :entityId)
              and (:actorMemberId is null or r.actorMemberId = :actorMemberId)
              and (cast(:from as Instant) is null or r.occurredAt >= :from)
              and (cast(:toExclusive as Instant) is null or r.occurredAt < :toExclusive)
            order by r.occurredAt desc, r.id desc
            """)
    Page<AuditRecord> search(@Param("tenantId") UUID tenantId, @Param("entityType") String entityType,
            @Param("entityId") UUID entityId, @Param("actorMemberId") UUID actorMemberId,
            @Param("from") Instant from, @Param("toExclusive") Instant toExclusive, Pageable pageable);
}
