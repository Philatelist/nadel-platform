package app.nadel.ledger.internal;

import app.nadel.settings.CommonConsumerSplit;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * How each common consumer is shared among plots (tables {@code common_consumer_billing} and
 * {@code common_consumer_user}, V18): the consumer's own split (no row: the cooperative's default) and the plots marked
 * as its users. Both are settings, keyed by a meter; a run reads them for every meter of a consumer's chain and takes
 * those of the latest meter that has any ({@link #of}), so a replaced meter keeps the settings until the new one gets
 * its own. Parameterised native SQL inside the caller's transaction, under RLS.
 */
@Component
class CommonConsumerSettings {

    private final EntityManager entityManager;

    CommonConsumerSettings(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** The settings of one meter: its own split (null: the cooperative's default) and its service users. */
    record Billing(@Nullable CommonConsumerSplit split, Set<UUID> users) {

        Billing {
            users = Set.copyOf(users);
        }

        boolean isEmpty() {
            return split == null && users.isEmpty();
        }
    }

    /** The settings of each meter that has any; meters without settings are absent. */
    @Transactional(propagation = Propagation.MANDATORY)
    Map<UUID, Billing> of(Collection<UUID> meterIds) {
        if (meterIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, CommonConsumerSplit> splits = new HashMap<>();
        for (Object row : entityManager.createNativeQuery("select cast(consumer_meter_id as text) || '|' || split "
                + "from common_consumer_billing where consumer_meter_id in (:meters)")
                .setParameter("meters", meterIds).getResultList()) {
            String[] parts = row.toString().split("\\|");
            splits.put(UUID.fromString(parts[0]), CommonConsumerSplit.valueOf(parts[1]));
        }
        Map<UUID, Set<UUID>> users = new HashMap<>();
        for (Object row : entityManager.createNativeQuery("select cast(consumer_meter_id as text) || '|' "
                + "|| cast(plot_id as text) from common_consumer_user where consumer_meter_id in (:meters)")
                .setParameter("meters", meterIds).getResultList()) {
            String[] parts = row.toString().split("\\|");
            users.computeIfAbsent(UUID.fromString(parts[0]), k -> new HashSet<>()).add(UUID.fromString(parts[1]));
        }
        Map<UUID, Billing> billing = new HashMap<>();
        for (UUID meter : meterIds) {
            Billing one = new Billing(splits.get(meter), users.getOrDefault(meter, Set.of()));
            if (!one.isEmpty()) {
                billing.put(meter, one);
            }
        }
        return billing;
    }

    /** The settings of one meter; empty settings when it has none. */
    @Transactional(propagation = Propagation.MANDATORY)
    Billing ofMeter(UUID meterId) {
        return of(List.of(meterId)).getOrDefault(meterId, new Billing(null, Set.of()));
    }

    /**
     * The settings of a consumer's chain: those of the latest meter of {@code meterIds} (oldest first) that has any;
     * empty settings when none has.
     */
    static Billing ofChain(List<UUID> meterIds, Map<UUID, Billing> billing) {
        for (int i = meterIds.size() - 1; i >= 0; i--) {
            Billing one = billing.get(meterIds.get(i));
            if (one != null) {
                return one;
            }
        }
        return new Billing(null, Set.of());
    }

    /** Saves the settings of one meter: its split (null removes the row) and its users (replaced as a whole). */
    @Transactional(propagation = Propagation.MANDATORY)
    void save(UUID tenantId, UUID meterId, @Nullable CommonConsumerSplit split, Collection<UUID> users) {
        if (split == null) {
            entityManager.createNativeQuery("delete from common_consumer_billing where consumer_meter_id = :meter")
                    .setParameter("meter", meterId).executeUpdate();
        } else {
            entityManager.createNativeQuery("insert into common_consumer_billing (tenant_id, consumer_meter_id, split) "
                    + "values (:tenant, :meter, :split) on conflict (tenant_id, consumer_meter_id) "
                    + "do update set split = excluded.split, updated_at = now()")
                    .setParameter("tenant", tenantId).setParameter("meter", meterId)
                    .setParameter("split", split.name()).executeUpdate();
        }
        entityManager.createNativeQuery("delete from common_consumer_user where consumer_meter_id = :meter")
                .setParameter("meter", meterId).executeUpdate();
        for (UUID plot : users) {
            entityManager.createNativeQuery("insert into common_consumer_user (tenant_id, consumer_meter_id, plot_id) "
                    + "values (:tenant, :meter, :plot)")
                    .setParameter("tenant", tenantId).setParameter("meter", meterId).setParameter("plot", plot)
                    .executeUpdate();
        }
    }

    /**
     * The transaction-scoped advisory lock of the settings of one consumer meter: two changes of the same consumer run
     * one after the other, so each audits the change from what the other saved.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void lock(UUID tenantId, UUID meterId) {
        entityManager.createNativeQuery(
                "select count(*) from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) as l")
                .setParameter("key", "ledger.common-consumer-billing:" + tenantId + ":" + meterId)
                .getSingleResult();
    }
}
