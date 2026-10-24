package app.nadel.ledger.internal;

import java.math.BigDecimal;
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
 * whose every charge a recalculation set aside (feature 012: reversed it in an open month, or adjusted it to
 * {@code 0.00} in a closed one; a line that queued the case) is not charged: once the case can be charged, 011 charges
 * it again, under {@code base#r{recalculation}} with a recalculation that set aside one of those charges (the base key
 * is taken, {@code charge_source_key_uq}; such a recalculation booked no standing charge under its own suffix, so one
 * of these keys is free). A charge adjusted to {@code 0.00} stands in its closed month; the new one is booked like any
 * 011 charge (its own month if open, else the earliest open month with its own service period).
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
            Set<String> setAside = new HashSet<>(charges.setAsideBy(existing, ChargeSource.RECALCULATION));
            for (String key : existing) {
                if (!setAside.contains(key) || revisionsOf(key).charged()) {
                    charged.add(key);
                }
            }
        }
        return charged;
    }

    /**
     * The keys of the interval ({@code CONSUMPTION:…}, {@code LOSSES:…}) whose charge stands under another key (feature
     * 012): a recalculation of a corrected reading matches by identity (meter, source and point dates,
     * {@link ChargeIdentity}), so an interval it adjusted or kept keeps its charge under the old reading's key while
     * the corrected reading has a new one. Such a key counts as charged as well, so the listener never books the
     * interval a second time. Only a standing charge counts here (not reversed, not adjusted to {@code 0.00}): after a
     * reversal by hand the corrected reading is charged again (feature 011).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Set<String> standingOfInterval(ReadingChargePlanning.MeterInterval interval) {
        Set<String> keys = new HashSet<>();
        for (String source : charges.standingIntervalSources(interval.meterId(), interval.from().on().toString(),
                interval.to().on().toString())) {
            keys.add(ChargeSource.LOSSES.name().equals(source) ? interval.lossesKey() : interval.consumptionKey());
        }
        return keys;
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
        Map<String, Revision> cancelled = cancelledOf(base, prefix);
        List<Revision> revisions = new ArrayList<>();
        for (Object[] row : charges.revisionsOf(base, prefix, prefix.length())) {
            String key = (String) row[0];
            Revision revision = new Revision(key, (ChargeSource) row[1], (UUID) row[2], (Instant) row[3]);
            revisions.add(row[1] == null && cancelled.containsKey(key) ? cancelled.get(key) : revision);
        }
        return new Revisions(revisions);
    }

    /**
     * The charges of the keys whose adjustments bring them to {@code 0.00}, by key, each set aside by the recalculation
     * of its latest adjustment (the one that brought it to zero).
     */
    private Map<String, Revision> cancelledOf(String base, String prefix) {
        Map<String, BigDecimal> sums = new HashMap<>();
        Map<String, Revision> latest = new HashMap<>();
        Comparator<Revision> order = Comparator.comparing(Revision::setAsideAt,
                Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(revision -> String.valueOf(revision.recalculationId()));
        for (Object[] row : charges.adjustmentsOf(base, prefix, prefix.length())) {
            String key = (String) row[0];
            sums.putIfAbsent(key, (BigDecimal) row[1]);
            sums.merge(key, (BigDecimal) row[2], BigDecimal::add);
            Revision adjustment = new Revision(key, ChargeSource.RECALCULATION, (UUID) row[3], (Instant) row[4]);
            latest.merge(key, adjustment, (a, b) -> order.compare(a, b) >= 0 ? a : b);
        }
        Map<String, Revision> cancelled = new HashMap<>();
        latest.forEach((key, revision) -> {
            BigDecimal effective = sums.get(key);
            if (effective.signum() == 0) {
                cancelled.put(key, revision);
            }
        });
        return cancelled;
    }

    /**
     * A charge with the base key or a {@code base#r...} key, and what set it aside (null: it stands): the reversal that
     * cancels it, or the adjustment of a recalculation that brought it to {@code 0.00}.
     */
    private record Revision(String sourceKey, @Nullable ChargeSource setAsideBy, @Nullable UUID recalculationId,
            @Nullable Instant setAsideAt) {

        boolean setAsideByRecalculation() {
            return setAsideBy == ChargeSource.RECALCULATION && recalculationId != null;
        }
    }

    private record Revisions(List<Revision> revisions) {

        /** Whether one of the charges stands (not adjusted to {@code 0.00}) or was reversed by hand. */
        boolean charged() {
            return revisions.stream().anyMatch(revision -> !revision.setAsideByRecalculation());
        }

        /** {@code base#r{recalculation}} of the latest recalculation that set a charge aside, whose key is free. */
        String bookingKey(String base) {
            if (charged()) {
                throw new IllegalStateException("The key counts as charged");
            }
            Set<String> taken = new HashSet<>(revisions.stream().map(Revision::sourceKey).toList());
            return revisions.stream()
                    .sorted(Comparator.comparing(Revision::setAsideAt, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(revision -> String.valueOf(revision.recalculationId())))
                    .map(revision -> base + "#r" + revision.recalculationId())
                    .filter(key -> !taken.contains(key))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No free key to book the charge again"));
        }
    }
}
