package app.nadel.members.internal;

import java.util.Comparator;
import java.util.UUID;

/**
 * The order in which PostgreSQL sorts {@code uuid} values: byte by byte, unsigned. {@link UUID#compareTo} compares the
 * two halves as signed numbers, so it puts an id starting with 8..f before one starting with 0..7. Rows are locked in
 * chunks, each in database order, so the ids must be split by this order for the lock order to be one order overall.
 */
final class UuidOrder {

    static final Comparator<UUID> POSTGRES = (a, b) -> {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    };

    private UuidOrder() {
    }
}
