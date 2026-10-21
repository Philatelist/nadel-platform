package app.nadel.meters.internal;

import app.nadel.meters.ConsumptionCalculator.Reading;
import app.nadel.meters.MeterValue;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
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
 * meter has readings at all. Runs in the caller's transaction (RLS). The reading commands of a later slice own the
 * readings themselves.
 */
@Component
class ReadingFacts {

    /** Upper bound of one {@code IN (...)} list. */
    private static final int CHUNK = 500;

    private final MeterRepository meters;

    ReadingFacts(MeterRepository meters) {
        this.meters = meters;
    }

    /** The latest approved reading of each meter that has one. */
    Map<UUID, Reading> latestApproved(Collection<UUID> meterIds) {
        Map<UUID, Reading> latest = new HashMap<>();
        for (List<UUID> chunk : chunks(meterIds)) {
            for (Object[] row : meters.latestApprovedReadings(chunk)) {
                latest.put((UUID) row[0], new Reading(date(row[1]), MeterValue.of((BigDecimal) row[2])));
            }
        }
        return latest;
    }

    /** The latest approved reading of one meter. */
    Optional<Reading> latestApproved(UUID meterId) {
        return Optional.ofNullable(latestApproved(List.of(meterId)).get(meterId));
    }

    /** The meters among {@code meterIds} that have a reading of any status. */
    Set<UUID> withReadings(Collection<UUID> meterIds) {
        Set<UUID> with = new HashSet<>();
        for (List<UUID> chunk : chunks(meterIds)) {
            with.addAll(meters.meterIdsWithReadings(chunk));
        }
        return with;
    }

    /** A {@code date} column of a native query: the driver may hand it over as {@link Date} or {@link LocalDate}. */
    private static LocalDate date(Object value) {
        return value instanceof Date sqlDate ? sqlDate.toLocalDate() : (LocalDate) value;
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
