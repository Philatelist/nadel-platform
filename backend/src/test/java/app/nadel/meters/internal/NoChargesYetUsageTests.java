package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Until the ledger exists nothing is charged, so no reading is ever "used". */
class NoChargesYetUsageTests {

    @Test
    void noReadingIsUsedBeforeChargesExist() {
        assertThat(new NoChargesYetUsage().isUsed(UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-12-31"))).isFalse();
    }
}
