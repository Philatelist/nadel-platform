package app.nadel.ledger.internal;

import app.nadel.money.Money;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Everything booked for one {@link ChargeIdentity} (feature 012, technical considerations 2.2): the {@code REGULAR}
 * charges of the identity (a charge, and the charge booked again after a recalculation reversed it), their reversals
 * and their adjustments. Pure.
 * <ul>
 * <li>The <b>effective amount</b> is the sum of the chain: a reversed charge and its reversal come to {@code 0.00}, an
 * adjusted charge counts with its adjustments.</li>
 * <li>The <b>live</b> charge is the {@code REGULAR} charge that is not reversed (at most one). A charge of a closed
 * month that adjustments brought to {@code 0.00} stays in its month; when 011 charged the identity again (the case
 * became chargeable, {@link ChargeKeys}), the new charge is the live one.</li>
 * <li>A chain whose charge was reversed by hand ({@code MANUAL_REVERSAL}) and not booked again is <b>frozen</b>: a
 * recalculation leaves it as it is.</li>
 * </ul>
 *
 * @param regulars    the {@code REGULAR} charges, in the order given
 * @param reversals   their reversals
 * @param adjustments their adjustments
 */
record BookedChain(ChargeIdentity identity, List<BookedCharge> regulars, List<BookedCharge> reversals,
        List<BookedCharge> adjustments) {

    BookedChain {
        Objects.requireNonNull(identity, "identity");
        regulars = List.copyOf(regulars);
        reversals = List.copyOf(reversals);
        adjustments = List.copyOf(adjustments);
    }

    /**
     * The chains of the rows given, by identity. Opening balances are left out; a reversal or an adjustment whose
     * charge is not among the rows is left out too (it belongs to a charge outside the scope).
     *
     * @throws IllegalStateException when an identity has two live charges (the ledger never books one twice)
     */
    static Map<ChargeIdentity, BookedChain> of(Collection<BookedCharge> rows) {
        Map<UUID, ChargeIdentity> identityOf = new HashMap<>();
        Map<ChargeIdentity, List<BookedCharge>> regulars = new TreeMap<>();
        for (BookedCharge row : rows) {
            ChargeIdentity.of(row).ifPresent(identity -> {
                identityOf.put(row.id(), identity);
                regulars.computeIfAbsent(identity, k -> new ArrayList<>()).add(row);
            });
        }
        Map<ChargeIdentity, List<BookedCharge>> reversals = new HashMap<>();
        Map<ChargeIdentity, List<BookedCharge>> adjustments = new HashMap<>();
        for (BookedCharge row : rows) {
            UUID target = row.kind() == ChargeKind.REVERSAL ? row.reversesChargeId()
                    : row.kind() == ChargeKind.ADJUSTMENT ? row.adjustsChargeId() : null;
            ChargeIdentity identity = target == null ? null : identityOf.get(target);
            if (identity != null) {
                (row.kind() == ChargeKind.REVERSAL ? reversals : adjustments)
                        .computeIfAbsent(identity, k -> new ArrayList<>()).add(row);
            }
        }
        Map<ChargeIdentity, BookedChain> chains = new TreeMap<>();
        regulars.forEach((identity, charges) -> {
            BookedChain chain = new BookedChain(identity, charges, reversals.getOrDefault(identity, List.of()),
                    adjustments.getOrDefault(identity, List.of()));
            chain.live();
            chains.put(identity, chain);
        });
        return chains;
    }

    /** The effective amount: every row of the chain added up, exactly. */
    Money effective() {
        Money sum = Money.ZERO;
        for (List<BookedCharge> rows : List.of(regulars, reversals, adjustments)) {
            for (BookedCharge row : rows) {
                sum = sum.plus(row.amount());
            }
        }
        return sum;
    }

    /**
     * The {@code REGULAR} charge that is not reversed; null when every one is. Of two or more, those adjusted to
     * {@code 0.00} do not count.
     *
     * @throws IllegalStateException when two are not reversed and not adjusted to {@code 0.00}
     */
    @Nullable BookedCharge live() {
        List<BookedCharge> live = regulars.stream().filter(charge -> !reversed(charge.id())).toList();
        if (live.size() > 1) {
            live = live.stream().filter(charge -> !adjustedToZero(charge)).toList();
        }
        if (live.size() > 1) {
            throw new IllegalStateException("Two live charges of one identity: " + identity);
        }
        return live.isEmpty() ? null : live.getFirst();
    }

    /** The sum of the adjustments of the live charge ({@code 0.00} when none, or no live charge). */
    Money adjustmentsOfLive() {
        BookedCharge live = live();
        Money sum = Money.ZERO;
        if (live != null) {
            for (BookedCharge adjustment : adjustments) {
                if (live.id().equals(adjustment.adjustsChargeId())) {
                    sum = sum.plus(adjustment.amount());
                }
            }
        }
        return sum;
    }

    /** Whether the charge was reversed by hand and not booked again: the recalculation leaves the chain alone. */
    boolean frozen() {
        return live() == null && reversals.stream().anyMatch(r -> r.source() == ChargeSource.MANUAL_REVERSAL);
    }

    private boolean adjustedToZero(BookedCharge charge) {
        Money sum = charge.amount();
        for (BookedCharge adjustment : adjustments) {
            if (charge.id().equals(adjustment.adjustsChargeId())) {
                sum = sum.plus(adjustment.amount());
            }
        }
        return sum.signum() == 0;
    }

    private boolean reversed(UUID chargeId) {
        return reversals.stream().anyMatch(reversal -> chargeId.equals(reversal.reversesChargeId()));
    }
}
