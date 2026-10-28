package app.nadel.ledger.internal;

import app.nadel.money.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The digests of the payment previews (feature 015, technical considerations 2.6), SHA-256 as 64 hex digits like
 * {@link RecalculationDigest}: a save with another digest than its preview is refused ({@code PREVIEW_OUTDATED}).
 * Pure.
 * <ul>
 * <li>{@link #entry}: the amount, the payer of a split, the parts in their order (plot, amount: the order matters,
 * the first plot takes the excess of a split), and per part the plot's open charges in planner order as
 * {@code chargeId:open}.</li>
 * <li>{@link #reallocation}: the payment, its live allocations as {@code id:remaining} (sorted by id), and the plot's
 * open charges as if those were released, in planner order.</li>
 * <li>{@link #prepayment}: the plot, its prepayment and its open charges in planner order.</li>
 * </ul>
 * The open charges are put in {@link AllocationPlanner#ORDER} by the digest itself, so the order the caller loads them
 * in never matters, while a change of that order (the charge types reordered, a charge booked) changes the digest. Each
 * kind starts with its own tag, so digests of two kinds never coincide.
 */
final class PaymentDigest {

    /** A part of an entry: its plot, its amount and the plot's charges (any order). */
    record Part(UUID plotId, Money amount, Collection<ChargeState> charges) {

        Part {
            Objects.requireNonNull(plotId, "plotId");
            Objects.requireNonNull(amount, "amount");
            charges = List.copyOf(charges);
        }
    }

    private PaymentDigest() {
    }

    /** The digest of the preview of an entry of one plot (one part, no payer in the digest). */
    static String entry(Money amount, List<Part> parts) {
        return entry(amount, null, parts);
    }

    /**
     * The digest of the preview of an entry, of one plot (one part) or split over several plots of {@code payer}: the
     * payer is part of a split's digest, since the plots of the split are the payer's.
     */
    static String entry(Money amount, @Nullable UUID payer, List<Part> parts) {
        List<String> text = new ArrayList<>();
        text.add("E|" + amount);
        if (payer != null) {
            text.add("Y|" + payer);
        }
        for (Part part : parts) {
            text.add("P|" + part.plotId() + "|" + part.amount());
            open(text, part.charges());
        }
        return sha256(text);
    }

    /** The digest of the preview of a reallocation of the payment. */
    static String reallocation(UUID paymentId, Collection<AllocationState> allocations,
            Collection<ChargeState> chargesAsIfReleased) {
        List<String> text = new ArrayList<>();
        text.add("R|" + paymentId);
        allocations.stream().filter(allocation -> allocation.paymentId().equals(paymentId) && allocation.live())
                .sorted(Comparator.comparing(allocation -> allocation.id().toString()))
                .forEach(allocation -> text.add("A|" + allocation.id() + ":" + allocation.remaining()));
        open(text, chargesAsIfReleased);
        return sha256(text);
    }

    /** The digest of the preview of a prepayment allocation of the plot. */
    static String prepayment(UUID plotId, Money prepayment, Collection<ChargeState> charges) {
        List<String> text = new ArrayList<>();
        text.add("X|" + plotId + "|" + prepayment);
        open(text, charges);
        return sha256(text);
    }

    private static void open(List<String> text, Collection<ChargeState> charges) {
        for (ChargeState charge : AllocationPlanner.openInOrder(charges)) {
            text.add("O|" + charge.plotId() + "|" + charge.id() + ":" + charge.open());
        }
    }

    private static String sha256(List<String> text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
