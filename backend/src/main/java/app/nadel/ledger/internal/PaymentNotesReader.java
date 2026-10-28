package app.nadel.ledger.internal;

import app.nadel.ledger.PaymentNotes;
import app.nadel.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The port {@link PaymentNotes}: reads the sealed payer notes of payments (500 per {@code IN} list, under RLS in the
 * caller's transaction) and opens them with {@link PaymentNoteCipher} for the cooperative bound to the transaction.
 * Only a caller with {@code MEMBERS_VIEW} gets here. The notes are personal data: nothing here logs them or puts them
 * in an exception.
 */
@Component
class PaymentNotesReader implements PaymentNotes {

    private static final String NOTES = """
            select p.id, p.payer_note_enc
            from payment p
            where p.id in (:ids)
              and p.payer_note_enc is not null
            """;

    private static final int CHUNK = 500;

    private final EntityManager entityManager;
    private final PaymentNoteCipher cipher;

    PaymentNotesReader(EntityManager entityManager, PaymentNoteCipher cipher) {
        this.entityManager = entityManager;
        this.cipher = cipher;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_MEMBERS_VIEW')")
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, String> notes(Collection<UUID> paymentIds) {
        Objects.requireNonNull(paymentIds, "paymentIds");
        List<UUID> ids = paymentIds.stream().distinct().toList();
        Map<UUID, String> notes = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return notes;
        }
        UUID tenant = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("No cooperative is bound"));
        for (int from = 0; from < ids.size(); from += CHUNK) {
            for (Object row : entityManager.createNativeQuery(NOTES)
                    .setParameter("ids", ids.subList(from, Math.min(ids.size(), from + CHUNK)))
                    .getResultList()) {
                Object[] values = (Object[]) row;
                UUID id = (UUID) values[0];
                String note = cipher.open(tenant, id, (byte[]) values[1]);
                if (note != null) {
                    notes.put(id, note);
                }
            }
        }
        return notes;
    }
}
