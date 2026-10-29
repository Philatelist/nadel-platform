package app.nadel.documents.internal;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out gap-free document numbers per cooperative, kind and year (feature 016 §2.7, technical considerations 2.7)
 * from table {@code document_counter}: inside the issuing transaction the row is created at 0 when missing
 * ({@code INSERT … ON CONFLICT DO NOTHING}) and moved forward by one ({@code UPDATE … RETURNING}). The row lock of the
 * {@code UPDATE} is held until the transaction ends, so a concurrent issue of the same cooperative and year waits and
 * then gets the next number (read committed: the waiting {@code UPDATE} sees the committed value), and a rollback undoes
 * the step (no gaps). A trigger refuses any other change than {@code +1}. The caller takes the step last, after every
 * check, so the lock is held only for the insert of the document.
 */
@Component
class DocumentCounter {

    /** The kinds of numbered documents (the CHECK of {@code document_counter.kind}). */
    enum Kind {
        CERTIFICATE
    }

    private final EntityManager entityManager;

    DocumentCounter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** The next number of the kind in the year for the cooperative, taken in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    long next(UUID tenantId, Kind kind, int year) {
        entityManager.createNativeQuery("insert into document_counter (tenant_id, kind, year, last_value) "
                + "values (:tenant, :kind, :year, 0) on conflict (tenant_id, kind, year) do nothing")
                .setParameter("tenant", tenantId)
                .setParameter("kind", kind.name())
                .setParameter("year", (short) year)
                .executeUpdate();
        Object value = entityManager.createNativeQuery("with step as (update document_counter "
                + "set last_value = last_value + 1 where tenant_id = :tenant and kind = :kind and year = :year "
                + "returning last_value) select last_value from step")
                .setParameter("tenant", tenantId)
                .setParameter("kind", kind.name())
                .setParameter("year", (short) year)
                .getSingleResult();
        return ((Number) value).longValue();
    }
}
