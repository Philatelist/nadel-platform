package app.nadel.meters.internal;

import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterResource;
import app.nadel.meters.MeterValue;
import app.nadel.meters.MissingReadingRow;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.text.Collator;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The meters without a reading in a range ({@code MeterReports.missingReadings}, feature 013 §3.6), in the caller's
 * transaction: one native query selects the meters (RLS-bound, parameterised, plots 500 per {@code IN} list) with their
 * last approved reading before the range and whether a pending reading is dated in it; the meters and plots are then
 * loaded in bulk and put in the order of the register.
 */
@Component
class MissingReadingQueries {

    /** Values per {@code IN} list. */
    private static final int CHUNK = 500;

    /** Bound to an {@code IN} list that a {@code true} "all" flag switches off (an empty list is not valid SQL). */
    private static final List<UUID> NONE = List.of(new UUID(0L, 0L));

    /**
     * The meters of the resources and kinds that worked through the whole range (installed before it, not removed on or
     * before its end: an installation or removal in the range is a point of it) without an approved reading in it; a
     * water meter only when its plot was connected to water on a day of the range. With the latest approved reading
     * before the range (none: the installation is the last point) and whether a pending reading is dated in the range.
     */
    private static final String MISSING = """
            select m.id, last.taken_on, last.value,
                   exists (select 1 from meter_reading p
                           where p.meter_id = m.id and p.status = 'PENDING'
                             and p.taken_on between :from and :to) as pending
            from meter m
            left join lateral (select r.taken_on, r.value from meter_reading r
                               where r.meter_id = m.id and r.status = 'APPROVED' and r.taken_on < :from
                               order by r.taken_on desc
                               limit 1) last on true
            where m.resource in (:resources)
              and m.kind in (:kinds)
              and (:allPlots = true or m.plot_id in (:plotIds))
              and m.installed_on < :from
              and (m.removed_on is null or m.removed_on > :to)
              and not exists (select 1 from meter_reading a
                              where a.meter_id = m.id and a.status = 'APPROVED'
                                and a.taken_on between :from and :to)
              and (m.resource <> 'WATER'
                   or exists (select 1 from plot_water_connection c
                              where c.plot_id = m.plot_id and c.valid_from <= :to
                                and (c.valid_to is null or c.valid_to >= :from)))
            """;

    /** Within a plot: electricity before water, the newest installation first. */
    private static final Comparator<MeterRecord> WITHIN_A_PLOT = Comparator.comparing(MeterRecord::getResource)
            .thenComparing(MeterRecord::getInstalledOn, Comparator.reverseOrder())
            .thenComparing(MeterRecord::getId);

    private final EntityManager entityManager;
    private final MeterRepository meters;
    private final PlotCatalog plots;

    MissingReadingQueries(EntityManager entityManager, MeterRepository meters, PlotCatalog plots) {
        this.entityManager = entityManager;
        this.meters = meters;
        this.plots = plots;
    }

    /** What the query found about one meter. */
    private record Found(@Nullable LocalDate lastReadingOn, @Nullable BigDecimal lastValue, boolean pending) {
    }

    List<MissingReadingRow> missingReadings(LocalDate from, LocalDate to, Set<MeterResource> resources,
            Set<MeterKind> kinds, @Nullable Collection<UUID> plotIds, int limit) {
        List<String> resourceNames = (resources.isEmpty() ? Set.of(MeterResource.values()) : resources).stream()
                .map(Enum::name).toList();
        List<String> kindNames = (kinds.isEmpty() ? Set.of(MeterKind.values()) : kinds).stream().map(Enum::name)
                .toList();
        Map<UUID, Found> found = new HashMap<>();
        if (plotIds == null) {
            query(from, to, resourceNames, kindNames, null, found);
        } else {
            for (List<UUID> chunk : chunks(plotIds.stream().distinct().toList())) {
                query(from, to, resourceNames, kindNames, chunk, found);
            }
        }
        if (found.isEmpty()) {
            return List.of();
        }
        List<MeterRecord> records = new ArrayList<>();
        for (List<UUID> chunk : chunks(List.copyOf(found.keySet()))) {
            records.addAll(meters.findAllById(chunk));
        }
        Map<UUID, List<MeterRecord>> byPlot = new HashMap<>();
        List<MeterRecord> named = new ArrayList<>();
        for (MeterRecord meter : records) {
            if (meter.getPlotId() != null) {
                byPlot.computeIfAbsent(meter.getPlotId(), id -> new ArrayList<>()).add(meter);
            } else {
                named.add(meter);
            }
        }
        Map<UUID, PlotFacts> facts = new HashMap<>();
        for (List<UUID> chunk : chunks(List.copyOf(byPlot.keySet()))) {
            facts.putAll(plots.findAll(chunk));
        }
        List<MissingReadingRow> rows = new ArrayList<>();
        for (UUID plotId : byPlot.isEmpty() ? List.<UUID>of() : plots.inStreetOrder(byPlot.keySet())) {
            for (MeterRecord meter : byPlot.get(plotId).stream().sorted(WITHIN_A_PLOT).toList()) {
                if (rows.size() == limit) {
                    return rows;
                }
                rows.add(row(meter, facts.get(plotId), found.get(meter.getId()), to));
            }
        }
        Collator collator = Collator.getInstance(Locale.forLanguageTag("uk"));
        List<MeterRecord> sortedNamed = named.stream()
                .sorted(Comparator.comparing(MeterRecord::getKind)
                        .thenComparing(meter -> Objects.requireNonNullElse(meter.getName(), ""), collator)
                        .thenComparing(MeterRecord::getId))
                .toList();
        for (MeterRecord meter : sortedNamed) {
            if (rows.size() == limit) {
                return rows;
            }
            rows.add(row(meter, null, found.get(meter.getId()), to));
        }
        return rows;
    }

    private void query(LocalDate from, LocalDate to, List<String> resources, List<String> kinds,
            @Nullable List<UUID> plotIds, Map<UUID, Found> into) {
        List<?> rows = entityManager.createNativeQuery(MISSING)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("resources", resources)
                .setParameter("kinds", kinds)
                .setParameter("allPlots", plotIds == null)
                .setParameter("plotIds", plotIds == null ? NONE : plotIds)
                .getResultList();
        for (Object row : rows) {
            Object[] v = (Object[]) row;
            into.put((UUID) v[0], new Found(date(v[1]), (BigDecimal) v[2], (Boolean) v[3]));
        }
    }

    private static MissingReadingRow row(MeterRecord meter, @Nullable PlotFacts plot, Found found, LocalDate to) {
        boolean initial = found.lastReadingOn() == null;
        LocalDate lastOn = initial ? meter.getInstalledOn() : found.lastReadingOn();
        MeterValue lastValue = initial ? meter.getInitialValue() : MeterValue.of(found.lastValue());
        return new MissingReadingRow(meter.getId(), meter.getResource(), meter.getKind(), meter.getPlotId(),
                plot == null ? null : plot.number(), plot == null ? null : plot.street(), meter.getName(),
                meter.getSerial(), meter.getInstalledOn(), lastOn, lastValue, initial,
                ChronoUnit.DAYS.between(lastOn, to), found.pending());
    }

    private static @Nullable LocalDate date(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate) value;
    }

    private static List<List<UUID>> chunks(List<UUID> ids) {
        List<List<UUID>> chunks = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += CHUNK) {
            chunks.add(ids.subList(start, Math.min(ids.size(), start + CHUNK)));
        }
        return chunks;
    }
}
