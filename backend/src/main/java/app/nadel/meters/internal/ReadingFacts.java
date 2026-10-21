package app.nadel.meters.internal;

import app.nadel.meters.ConsumptionCalculator.Reading;
import app.nadel.meters.internal.MeterReadingRecord.Status;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What the register needs to know about the readings of meters, in bulk: the latest approved reading and whether a
 * meter has readings at all. Runs in the caller's transaction (RLS).
 */
@Component
class ReadingFacts {

    /** Upper bound of one {@code IN (...)} list. */
    private static final int CHUNK = 500;

    private final MeterReadingRepository readings;

    ReadingFacts(MeterReadingRepository readings) {
        this.readings = readings;
    }

    /** The latest approved reading of each meter that has one. */
    Map<UUID, Reading> latestApproved(Collection<UUID> meterIds) {
        Map<UUID, Reading> latest = new HashMap<>();
        for (List<UUID> chunk : chunks(meterIds)) {
            // one approved reading per meter and date (meter_reading_approved_uq), so one row per meter
            for (MeterReadingRecord reading : readings.latest(chunk, Status.APPROVED)) {
                latest.put(reading.getMeterId(), reading.toReading());
            }
        }
        return latest;
    }

    /** The latest approved reading of one meter. */
    Optional<Reading> latestApproved(UUID meterId) {
        return Optional.ofNullable(latestApproved(List.of(meterId)).get(meterId));
    }

    /** Whether the meter has a reading of any status. */
    boolean hasReadings(UUID meterId) {
        return readings.existsByMeterId(meterId);
    }

    /** The meters among {@code meterIds} that have a reading of any status. */
    Set<UUID> withReadings(Collection<UUID> meterIds) {
        Set<UUID> with = new HashSet<>();
        for (List<UUID> chunk : chunks(meterIds)) {
            with.addAll(readings.meterIdsWithReadings(chunk));
        }
        return with;
    }

    private static List<List<UUID>> chunks(Collection<UUID> ids) {
        List<UUID> list = List.copyOf(new HashSet<>(ids));
        List<List<UUID>> chunks = new ArrayList<>();
        for (int from = 0; from < list.size(); from += CHUNK) {
            chunks.add(list.subList(from, Math.min(list.size(), from + CHUNK)));
        }
        return chunks;
    }
}
