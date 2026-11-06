package app.nadel.tariffs.internal;

import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.tariffs.TariffBook;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
import app.nadel.tariffs.TariffResolver;
import app.nadel.tariffs.TariffResolver.Conflict;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.TariffConflict;
import nadel.contract.model.TariffConflictPlot;
import org.springframework.stereotype.Component;

/**
 * The conflict warning of the tariffs page (feature 011 §2.3): for a tariff, the other tariffs of the same charge type
 * that apply to some of the same plots from today on, worked out by {@link TariffResolver} (the rule the month run
 * uses) for every active plot that is not common land. Versions end only with an archived tariff (feature 020), so two
 * tariffs whose scopes share a plot conflict from the later first version on, unless one is archived with its last day
 * in a month before the other starts ({@link #shareAMonth}); an archived tariff whose last day is before today is left
 * out by the resolver. A warning only: both tariffs stay valid, and on those plots the month run
 * charges neither and queues the case. Runs in the caller's transaction; plots are read through {@link PlotCatalog}.
 */
@Component
class TariffConflicts {

    /** The plots listed per conflict; {@code plotCount} has them all. */
    static final int LISTED_PLOTS = 10;
    /** The end of the range checked: "from today on" (an active tariff never ends). */
    private static final LocalDate FOREVER = LocalDate.of(9999, 12, 31);

    private final TariffBook book;
    private final PlotCatalog plots;
    private final Clock clock;

    TariffConflicts(TariffBook book, PlotCatalog plots, Clock clock) {
        this.book = book;
        this.plots = plots;
        this.clock = clock;
    }

    /** The conflicts of each of the tariffs, by tariff id; a tariff without one maps to an empty list. */
    Map<UUID, List<TariffConflict>> of(Collection<UUID> tariffIds) {
        Map<UUID, List<TariffConflict>> result = new HashMap<>();
        tariffIds.forEach(id -> result.put(id, List.of()));
        if (tariffIds.isEmpty()) {
            return result;
        }
        Snapshot snapshot = book.snapshot();
        Set<UUID> types = snapshot.tariffs().stream().filter(t -> tariffIds.contains(t.id()))
                .map(TariffEntry::chargeTypeId)
                .filter(type -> snapshot.tariffsOf(type).size() > 1)
                .collect(Collectors.toSet());
        if (types.isEmpty()) {
            return result;
        }
        LocalDate today = LocalDate.now(clock);
        // tariff -> other tariff -> plots on which both apply, in natural order of the numbers
        Map<UUID, Map<UUID, List<PlotFacts>>> shared = new HashMap<>();
        for (PlotFacts plot : plots.findChargeable()) {
            TariffResolver.PlotFacts facts = new TariffResolver.PlotFacts(plot.id(), plot.commonLand(),
                    plot.archived(), plot.areaM2());
            for (UUID type : types) {
                if (TariffResolver.resolve(snapshot, type, facts, today, FOREVER) instanceof Conflict conflict) {
                    for (TariffEntry tariff : conflict.tariffs()) {
                        for (TariffEntry other : conflict.tariffs()) {
                            if (!other.id().equals(tariff.id()) && shareAMonth(tariff, other, YearMonth.from(today))) {
                                shared.computeIfAbsent(tariff.id(), id -> new LinkedHashMap<>())
                                        .computeIfAbsent(other.id(), id -> new ArrayList<>()).add(plot);
                            }
                        }
                    }
                }
            }
        }
        for (UUID id : tariffIds) {
            Map<UUID, List<PlotFacts>> withOthers = shared.getOrDefault(id, Map.of());
            // snapshot order: by name, then id
            result.put(id, snapshot.tariffs().stream().filter(other -> withOthers.containsKey(other.id()))
                    .map(other -> conflict(other, withOthers.get(other.id()))).toList());
        }
        return result;
    }

    /**
     * Whether both tariffs apply in a common billing month from {@code from} on, as the month run resolves them month by
     * month: a tariff archived before the other starts (its {@code validUntil} in an earlier month) never meets it.
     */
    static boolean shareAMonth(TariffEntry a, TariffEntry b, YearMonth from) {
        YearMonth start = latest(from, latest(firstMonth(a), firstMonth(b)));
        YearMonth endA = a.validUntil() == null ? null : YearMonth.from(a.validUntil());
        YearMonth endB = b.validUntil() == null ? null : YearMonth.from(b.validUntil());
        YearMonth end = endA == null ? endB : endB == null || endA.isBefore(endB) ? endA : endB;
        return end == null || !start.isAfter(end);
    }

    private static YearMonth firstMonth(TariffEntry tariff) {
        return tariff.firstDay().map(YearMonth::from).orElse(YearMonth.from(FOREVER));
    }

    private static YearMonth latest(YearMonth a, YearMonth b) {
        return a.isAfter(b) ? a : b;
    }

    private static TariffConflict conflict(TariffEntry other, List<PlotFacts> plots) {
        List<TariffConflictPlot> listed = plots.stream().limit(LISTED_PLOTS)
                .map(plot -> new TariffConflictPlot(plot.id(), plot.number())).toList();
        return new TariffConflict(other.id(), other.name(), (long) plots.size(), listed);
    }
}
