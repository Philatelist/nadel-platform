package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The public event of a booking names a cooperative and at least one plot, and cannot be changed afterwards. */
class ChargesBookedTests {

    @Test
    void theEventKeepsACopyOfThePlots() {
        UUID plot = UUID.randomUUID();
        Set<UUID> plots = new HashSet<>(Set.of(plot));

        ChargesBooked event = new ChargesBooked(UUID.randomUUID(), plots);
        plots.add(UUID.randomUUID());

        assertThat(event.plotIds()).containsExactly(plot);
        assertThatThrownBy(() -> event.plotIds().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anEventNeedsACooperativeAndAPlot() {
        assertThatThrownBy(() -> new ChargesBooked(null, Set.of(UUID.randomUUID())))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChargesBooked(UUID.randomUUID(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
