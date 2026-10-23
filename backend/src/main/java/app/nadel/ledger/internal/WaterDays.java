package app.nadel.ledger.internal;

import app.nadel.meters.MeterResource;
import app.nadel.meters.WaterConnections;
import app.nadel.meters.WaterConnections.ConnectedPeriod;
import app.nadel.tariffs.DaySet;
import app.nadel.tariffs.TariffBook.ChargeTypeEntry;
import app.nadel.tariffs.TariffBook.Snapshot;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The days a tariff may charge a plot (feature 011 §2.8): every day, except that a tariff of the {@code WATER} charge
 * type, of any basis, charges only the days the plot is connected to water ({@link WaterConnections}). A plot that is
 * never connected in the period is charged nothing and nothing is queued for it. Reads inside the caller's
 * transaction.
 */
@Component
class WaterDays {

    /** The days the tariffs of a charge type may charge a plot in a month. */
    @FunctionalInterface
    interface ApplicableDays {

        DaySet of(UUID chargeTypeId, UUID plotId, YearMonth month);

        /** Every day for every charge type. */
        ApplicableDays ALL = (chargeTypeId, plotId, month) -> DaySet.ALL;
    }

    private static final String WATER = "WATER";

    private final WaterConnections connections;

    WaterDays(WaterConnections connections) {
        this.connections = connections;
    }

    /**
     * The applicable days for the month run and the retry of month items: the connected days of each plot for the
     * {@code WATER} type, every day for the others. The connections are read once, for all the plots and months.
     */
    ApplicableDays forMonths(Snapshot snapshot, Collection<UUID> plotIds, Collection<YearMonth> months) {
        Optional<UUID> water = snapshot.chargeTypes().stream().filter(type -> WATER.equals(type.code()))
                .filter(ChargeTypeEntry::charges).map(ChargeTypeEntry::id).findFirst();
        if (water.isEmpty() || plotIds.isEmpty() || months.isEmpty()) {
            return ApplicableDays.ALL;
        }
        LocalDate from = months.stream().min(Comparator.naturalOrder()).orElseThrow().atDay(1);
        LocalDate to = months.stream().max(Comparator.naturalOrder()).orElseThrow().atEndOfMonth();
        Map<UUID, DaySet> days = daySets(connections.connectedDays(plotIds, from, to));
        UUID waterType = water.get();
        return (chargeTypeId, plotId, month) -> waterType.equals(chargeTypeId)
                ? days.getOrDefault(plotId, DaySet.NONE) : DaySet.ALL;
    }

    /**
     * The applicable days of a plot meter's interval: the days the plot is connected to water for a water meter, every
     * day for electricity.
     */
    DaySet forInterval(MeterResource resource, UUID plotId, LocalDate from, LocalDate to) {
        if (resource != MeterResource.WATER) {
            return DaySet.ALL;
        }
        return daySets(connections.connectedDays(List.of(plotId), from, to)).getOrDefault(plotId, DaySet.NONE);
    }

    private static Map<UUID, DaySet> daySets(Map<UUID, List<ConnectedPeriod>> periods) {
        Map<UUID, DaySet> days = new HashMap<>();
        periods.forEach((plotId, list) -> days.put(plotId, DaySet.of(list.stream()
                .map(period -> new DaySet.Range(period.from(), period.to())).toList())));
        return days;
    }
}
