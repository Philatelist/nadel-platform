package app.nadel.meters.internal;

import app.nadel.meters.ConsumptionCalculator.Interval;
import app.nadel.meters.internal.ReadingValidator.Warning;
import java.util.List;
import nadel.contract.model.MeterReading;
import nadel.contract.model.ReadingInterval;
import nadel.contract.model.ReadingSource;
import nadel.contract.model.ReadingStatus;
import nadel.contract.model.ReadingWarning;
import org.jspecify.annotations.Nullable;

/**
 * Maps readings and intervals to the API model. Consumption is calculated from exact values and shown as a meter value
 * with three decimals; between two readings it is exact, so nothing is rounded here (ADR 0014).
 */
final class ReadingDtoMapper {

    private ReadingDtoMapper() {
    }

    /**
     * @param interval   the interval the reading closes; only for an approved reading that has a point before it
     * @param changeable whether the viewer may correct or delete it now
     */
    static MeterReading toDto(MeterReadingRecord reading, @Nullable Interval interval, boolean changeable) {
        MeterReading dto = new MeterReading(reading.getId(), reading.getMeterId(), reading.getTakenOn(),
                reading.getValue().toString(), ReadingStatus.fromValue(reading.getStatus().name()),
                ReadingSource.fromValue(reading.getSource().name()), changeable);
        dto.setSupersededById(reading.getSupersededById());
        dto.setRejectReason(reading.getRejectReason());
        if (interval != null) {
            dto.setInterval(toDto(interval));
        }
        return dto;
    }

    static ReadingInterval toDto(Interval interval) {
        return new ReadingInterval(interval.from(), interval.to(), Math.toIntExact(interval.days()),
                interval.consumption().toMeterValue().toString());
    }

    static List<ReadingWarning> toDto(List<Warning> warnings) {
        return warnings.stream().map(warning -> ReadingWarning.fromValue(warning.name())).toList();
    }
}
