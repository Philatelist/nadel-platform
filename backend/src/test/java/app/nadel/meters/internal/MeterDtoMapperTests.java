package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterResource;
import app.nadel.meters.MeterValue;
import app.nadel.meters.internal.MeterChains.Neighbours;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import nadel.contract.model.Meter;
import org.junit.jupiter.api.Test;

/** The replacement links of a meter as the API shows them: the meter it replaced and the meter that replaced it. */
class MeterDtoMapperTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PLOT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final LocalDate REPLACED_ON = LocalDate.of(2026, 9, 20);

    @Test
    void aMeterWithoutNeighboursHasNoLinks() {
        Meter dto = toDto(meter("ЕЛ-9/1"), Neighbours.NONE);

        assertThat(dto.getReplaces()).isNull();
        assertThat(dto.getReplacedBy()).isNull();
        assertThat(dto.getReplacesMeterId()).isNull();
    }

    @Test
    void theNewMeterLinksToThePredecessorWithItsRemoval() {
        MeterRecord old = meter("ЕЛ-9/1");
        old.remove(REPLACED_ON, MeterValue.of(new BigDecimal("8205")), NOW);
        MeterRecord replacement = MeterRecord.replacement(old, "ЕЛ-9/2", REPLACED_ON,
                MeterValue.of(new BigDecimal("3")), NOW);

        Meter dto = toDto(replacement, new Neighbours(old, null));

        assertThat(dto.getReplacesMeterId()).isEqualTo(old.getId());
        assertThat(dto.getReplaces()).isNotNull();
        assertThat(dto.getReplaces().getId()).isEqualTo(old.getId());
        assertThat(dto.getReplaces().getSerial()).isEqualTo("ЕЛ-9/1");
        assertThat(dto.getReplaces().getRemovedOn()).isEqualTo(REPLACED_ON);
        assertThat(dto.getReplaces().getFinalReading()).isEqualTo("8205.000");
        assertThat(dto.getReplacedBy()).isNull();
    }

    @Test
    void theReplacedMeterLinksToTheSuccessorAndKeepsItsOwnRemoval() {
        MeterRecord old = meter("ЕЛ-9/1");
        old.remove(REPLACED_ON, MeterValue.of(new BigDecimal("8205")), NOW);
        MeterRecord replacement = MeterRecord.replacement(old, "ЕЛ-9/2", REPLACED_ON,
                MeterValue.of(new BigDecimal("3")), NOW);

        Meter dto = toDto(old, new Neighbours(null, replacement));

        assertThat(dto.getReplacedBy()).isNotNull();
        assertThat(dto.getReplacedBy().getId()).isEqualTo(replacement.getId());
        assertThat(dto.getReplacedBy().getSerial()).isEqualTo("ЕЛ-9/2");
        assertThat(dto.getRemovedOn()).isEqualTo(REPLACED_ON);
        assertThat(dto.getFinalReading()).isEqualTo("8205.000");
        assertThat(dto.getReplaces()).isNull();
    }

    @Test
    void aPredecessorThatIsNotRemovedIsNotLinked() {
        // a replacement always removes the old meter; data that says otherwise gives no half-filled link
        MeterRecord old = meter("ЕЛ-9/1");
        MeterRecord replacement = MeterRecord.replacement(old, "ЕЛ-9/2", REPLACED_ON,
                MeterValue.of(new BigDecimal("3")), NOW);

        assertThat(toDto(replacement, new Neighbours(old, null)).getReplaces()).isNull();
    }

    private static MeterRecord meter(String serial) {
        return MeterRecord.register(TENANT, MeterKind.PLOT, MeterResource.ELECTRICITY, PLOT, serial, null,
                LocalDate.of(2026, 6, 1), MeterValue.of(new BigDecimal("8000")), null, NOW);
    }

    private static Meter toDto(MeterRecord meter, Neighbours neighbours) {
        return MeterDtoMapper.toDto(meter, null, null, false, true, neighbours);
    }
}
