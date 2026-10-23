package app.nadel.ledger.internal;

import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.NotChargedItem;
import nadel.contract.model.NotChargedKind;
import nadel.contract.model.NotChargedReason;
import nadel.contract.model.NotChargedTariff;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shapes "not charged" items for the API: codes and ids, with the plot number, the charge type and the tariffs by
 * name (user content) so the client can link to what needs fixing. The plots, charge types and tariffs are loaded
 * once per call. Runs inside the caller's transaction.
 */
@Component
class NotChargedViews {

    private final PlotCatalog plots;
    private final ChargeTypeCatalog chargeTypes;

    NotChargedViews(PlotCatalog plots, ChargeTypeCatalog chargeTypes) {
        this.plots = plots;
        this.chargeTypes = chargeTypes;
    }

    /** What the views of a set of items need, loaded once. */
    record Context(Map<UUID, PlotFacts> plots, Map<UUID, ChargeTypeFacts> types, Map<UUID, TariffEntry> tariffs) {
    }

    /** Loads the plots, all charge types and the tariffs of the snapshot. */
    Context context(Collection<UUID> plotIds, Snapshot snapshot) {
        return new Context(plots.findAll(plotIds),
                chargeTypes.all().stream().collect(Collectors.toMap(ChargeTypeFacts::id, Function.identity())),
                snapshot.tariffs().stream().collect(Collectors.toMap(TariffEntry::id, Function.identity())));
    }

    /** A stored item. */
    NotChargedItem view(NotChargedItemRecord item, Context context) {
        NotChargedItem dto = item(NotChargedKind.fromValue(item.getKind().name()),
                NotChargedReason.fromValue(item.getReason().name()), item.getPlotId(), item.getChargeTypeId(),
                item.getMonth(), item.getFromDate(), item.getToDate(), item.tariffIds(), context);
        dto.setId(item.getId());
        dto.setMeterId(item.getMeterId());
        dto.setCreatedAt(item.getCreatedAt().atOffset(ZoneOffset.UTC));
        return dto;
    }

    /**
     * A conflict of the month run: the stored open item of the case when there is one (its id and creation time),
     * with the tariffs of this run.
     */
    NotChargedItem conflict(UUID plotId, UUID chargeTypeId, YearMonth month, List<TariffEntry> tariffs,
            @Nullable NotChargedItemRecord open, Context context) {
        NotChargedItem dto = item(NotChargedKind.TARIFF_MONTH, NotChargedReason.CONFLICTING_TARIFFS, plotId,
                chargeTypeId, month, month.atDay(1), month.atEndOfMonth(),
                tariffs.stream().map(TariffEntry::id).toList(), context);
        if (open != null) {
            dto.setId(open.getId());
            dto.setCreatedAt(open.getCreatedAt().atOffset(ZoneOffset.UTC));
        }
        return dto;
    }

    /**
     * A meter interval a recalculation cannot charge (feature 012): the stored open item of the interval when there is
     * one (its id and creation time), with the reason and tariffs of the recalculation's plan.
     *
     * @param from the first day of the interval
     * @param to   the date of the reading that closes it
     */
    NotChargedItem interval(UUID plotId, UUID chargeTypeId, UUID meterId, LocalDate from, LocalDate to,
            NotChargedItemRecord.Reason reason, List<UUID> tariffIds, @Nullable NotChargedItemRecord open,
            Context context) {
        NotChargedItem dto = item(NotChargedKind.INTERVAL, NotChargedReason.fromValue(reason.name()), plotId,
                chargeTypeId, null, from, to, tariffIds, context);
        dto.setMeterId(meterId);
        if (open != null) {
            dto.setId(open.getId());
            dto.setCreatedAt(open.getCreatedAt().atOffset(ZoneOffset.UTC));
        }
        return dto;
    }

    private static NotChargedItem item(NotChargedKind kind, NotChargedReason reason, @Nullable UUID plotId,
            @Nullable UUID chargeTypeId, @Nullable YearMonth month, @Nullable LocalDate from, @Nullable LocalDate to,
            List<UUID> tariffIds, Context context) {
        // tariffs by name, as the tariffs list orders them; a tariff that no longer exists is left out
        List<NotChargedTariff> tariffs = tariffIds.stream().map(context.tariffs()::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing((TariffEntry t) -> t.name().toLowerCase(Locale.ROOT))
                        .thenComparing(TariffEntry::id))
                .map(t -> new NotChargedTariff(t.id(), t.name())).toList();
        NotChargedItem dto = new NotChargedItem(kind, reason, tariffs);
        if (plotId != null) {
            dto.setPlotId(plotId);
            PlotFacts plot = context.plots().get(plotId);
            if (plot != null) {
                dto.setPlotNumber(plot.number());
            }
        }
        if (chargeTypeId != null && context.types().containsKey(chargeTypeId)) {
            dto.setChargeType(ChargeViews.typeRef(context.types().get(chargeTypeId)));
        }
        if (month != null) {
            dto.setMonth(month.toString());
        }
        dto.setFrom(from);
        dto.setTo(to);
        return dto;
    }
}
