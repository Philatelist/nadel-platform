package app.nadel.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Many audit entries of one cooperative recorded together: the Excel import publishes one batch per
 * {@value #MAX_ENTRIES} records instead of one {@link AuditEvent} each. The audit listener writes a batch with one JDBC
 * batch insert in one transaction. Every entry keeps its own {@link AuditEvent#eventId()} as the id of its audit
 * entry, so a redelivered batch cannot create a duplicate.
 * <p>
 * Carries personal data (labels, values): never log it. Log {@link #batchId()} only.
 *
 * @param batchId  identifies the batch in logs; not stored
 * @param tenantId the cooperative; every entry belongs to it
 * @param entries  between 1 and {@value #MAX_ENTRIES} entries
 */
public record AuditBatch(UUID batchId, UUID tenantId, List<AuditEvent> entries) {

    /** The most entries one batch holds: keeps a stored publication and one insert transaction small. */
    public static final int MAX_ENTRIES = 500;

    public AuditBatch {
        entries = List.copyOf(entries);
        if (entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("A batch holds 1 to " + MAX_ENTRIES + " entries");
        }
        for (AuditEvent entry : entries) {
            if (!entry.tenantId().equals(tenantId)) {
                throw new IllegalArgumentException("An entry of another cooperative cannot join the batch");
            }
        }
    }

    /**
     * Splits events of one cooperative into batches of at most {@value #MAX_ENTRIES}, keeping their order. No events
     * give no batch.
     */
    public static List<AuditBatch> of(UUID tenantId, List<AuditEvent> events) {
        List<AuditBatch> batches = new ArrayList<>();
        for (int from = 0; from < events.size(); from += MAX_ENTRIES) {
            batches.add(new AuditBatch(UUID.randomUUID(), tenantId,
                    events.subList(from, Math.min(from + MAX_ENTRIES, events.size()))));
        }
        return batches;
    }
}
