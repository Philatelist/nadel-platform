package app.nadel.meters;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Which days plots are connected to water (feature 011 §2.8): the ledger charges water tariffs of any basis only for
 * the connected days, and a well pump's electricity is shared among the plots connected to that well. The cooperative
 * is the one of the caller's transaction (row-level security); every method runs inside it.
 */
public interface WaterConnections {

    /** Days a plot was connected, {@code from} and {@code to} both included. */
    record ConnectedPeriod(LocalDate from, LocalDate to) {

        public ConnectedPeriod {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (to.isBefore(from)) {
                throw new IllegalArgumentException("A period ends on or after its first day");
            }
        }
    }

    /**
     * The days each of the plots was connected to any water source from {@code from} to {@code to} (both included),
     * clipped to that range, oldest first. A plot without a connected day in the range is absent from the map.
     */
    Map<UUID, List<ConnectedPeriod>> connectedDays(Collection<UUID> plotIds, LocalDate from, LocalDate to);

    /**
     * The days each plot was connected to one water source from {@code from} to {@code to} (both included), clipped to
     * that range, oldest first: the common consumer meter {@code sourceMeterId}, or the cooperative's water supply when
     * it is null. A plot without a connected day in the range is absent from the map.
     */
    Map<UUID, List<ConnectedPeriod>> connectedToSource(@Nullable UUID sourceMeterId, LocalDate from, LocalDate to);
}
