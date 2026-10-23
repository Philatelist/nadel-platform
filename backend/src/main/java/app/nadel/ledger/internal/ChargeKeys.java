package app.nadel.ledger.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which source keys the charging of feature 011 (the listener of reading changes, the retry of a "not charged" item and
 * the month run) counts as charged, and the key it books a new charge under.
 * <p>
 * A key counts as charged while a charge with it, or with a key {@code base#r{recalculation}} it was booked again
 * under, still stands, or was reversed by hand ({@code MANUAL_REVERSAL}): a redelivered event or a rerun never books
 * twice, and a charge reversed by hand is not re-created (its correction is a new reading or a recalculation). A key
 * whose every charge a recalculation reversed (feature 012, a {@code REVERSE} line that queued the case) is not
 * charged: once the case can be charged, 011 charges it again, under {@code base#r{recalculation}} with a
 * recalculation that reversed one of those charges (the base key is taken, {@code charge_source_key_uq}; such a
 * recalculation booked no standing charge under its own suffix, so one of these keys is free).
 */
@Component
class ChargeKeys {

    private static final int BATCH = 500;

    private final ChargeRepository charges;

    ChargeKeys(ChargeRepository charges) {
        this.charges = charges;
    }

    /** The keys among {@code keys} that count as charged. */
    @Transactional(propagation = Propagation.MANDATORY)
    Set<String> charged(Collection<String> keys) {
        Set<String> charged = new HashSet<>();
        for (List<String> existing : existing(keys)) {
            Set<String> reversed = new HashSet<>(charges.reversedBy(existing, ChargeSource.RECALCULATION));
            for (String key : existing) {
                if (!reversed.contains(key) || revisionsOf(key).charged()) {
                    charged.add(key);
                }
            }
        }
        return charged;
    }

    /**
     * The key each of {@code keys} (base keys that do not count as charged, {@link #charged}) is booked under: the key
     * itself while no charge has it, else {@code base#r{recalculation}}.
     *
     * @throws IllegalStateException for a key that counts as charged
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Map<String, String> bookingKeys(Collection<String> keys) {
        Set<String> taken = new HashSet<>();
        existing(keys).forEach(taken::addAll);
        Map<String, String> booking = new HashMap<>();
        for (String key : keys) {
            booking.put(key, taken.contains(key) ? revisionsOf(key).bookingKey(key) : key);
        }
        return booking;
    }

    /** The keys among {@code keys} that a charge has, in batches. */
    private List<List<String>> existing(Collection<String> keys) {
        List<String> all = List.copyOf(new LinkedHashSet<>(keys));
        List<List<String>> batches = new ArrayList<>();
        for (int from = 0; from < all.size(); from += BATCH) {
            List<String> existing = charges.existingSourceKeys(all.subList(from, Math.min(all.size(), from + BATCH)));
            if (!existing.isEmpty()) {
                batches.add(existing);
            }
        }
        return batches;
    }

    private Revisions revisionsOf(String base) {
        String prefix = base + "#r";
        List<Revision> revisions = new ArrayList<>();
        for (Object[] row : charges.revisionsOf(base, prefix, prefix.length())) {
            revisions.add(new Revision((String) row[0], (ChargeSource) row[1], (UUID) row[2], (Instant) row[3]));
        }
        return new Revisions(revisions);
    }

    /** A charge with the base key or a {@code base#r...} key, and the reversal that cancels it (null: it stands). */
    private record Revision(String sourceKey, @Nullable ChargeSource reversedBy, @Nullable UUID recalculationId,
            @Nullable Instant reversedAt) {

        boolean reversedByRecalculation() {
            return reversedBy == ChargeSource.RECALCULATION && recalculationId != null;
        }
    }

    private record Revisions(List<Revision> revisions) {

        /** Whether one of the charges stands or was reversed by hand. */
        boolean charged() {
            return revisions.stream().anyMatch(revision -> !revision.reversedByRecalculation());
        }

        /** {@code base#r{recalculation}} of the latest recalculation reversal whose key no charge has. */
        String bookingKey(String base) {
            if (charged()) {
                throw new IllegalStateException("The key counts as charged");
            }
            Set<String> taken = new HashSet<>(revisions.stream().map(Revision::sourceKey).toList());
            return revisions.stream()
                    .sorted(Comparator.comparing(Revision::reversedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(revision -> String.valueOf(revision.recalculationId())))
                    .map(revision -> base + "#r" + revision.recalculationId())
                    .filter(key -> !taken.contains(key))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No free key to book the charge again"));
        }
    }
}
