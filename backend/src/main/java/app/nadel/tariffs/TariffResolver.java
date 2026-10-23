package app.nadel.tariffs;

import app.nadel.tariffs.TariffBook.ChargeTypeEntry;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Which tariff of a charge type charges a plot over a range of days (feature 011 §2.3). Pure: no Spring, no database,
 * no clock. The ledger's month run and the conflict warning of the tariffs page use the same rule.
 * <p>
 * The rule. A charge type that is not active, or is archived, charges nothing ({@link NoneReason#TYPE_INACTIVE}); an
 * archived plot or common land neither ({@link NoneReason#OUTSIDE_SCOPE}). A tariff of the type applies to the plot
 * when the plot is in its scope (not excluded from all plots, or selected) and it has a version valid on a day of the
 * range; versions never end, so that is when its first version starts on or before the last day. Exactly one such
 * tariff is {@link Single}; two or more are a {@link Conflict} (they apply on the same days, so none of them is
 * charged); none is {@link None}, with the reason: {@link NoneReason#NO_TARIFF} when the type has no tariff valid in the
 * range, {@link NoneReason#EXCLUDED} when the plot is excluded from one, otherwise {@link NoneReason#OUTSIDE_SCOPE}.
 */
public final class TariffResolver {

    private TariffResolver() {
    }

    /**
     * What a resolver needs to know about a plot.
     *
     * @param commonLand whether it is common land, which never has a tariff
     * @param archived   whether it is archived
     * @param areaM2     the area in square metres
     */
    public record PlotFacts(UUID plotId, boolean commonLand, boolean archived, BigDecimal areaM2) {

        public PlotFacts {
            Objects.requireNonNull(plotId, "plotId");
            Objects.requireNonNull(areaM2, "areaM2");
        }
    }

    /** Why no tariff applies. */
    public enum NoneReason {
        /** The charge type has no tariff valid on a day of the range. */
        NO_TARIFF,
        /** The plot is in the scope of no tariff of the type (or it is archived, or common land). */
        OUTSIDE_SCOPE,
        /** The plot is excluded from a tariff of the type and in the scope of no other. */
        EXCLUDED,
        /** The charge type is inactive or archived. */
        TYPE_INACTIVE
    }

    /** The outcome of {@link #resolve}. */
    public sealed interface Resolution {
    }

    /** Exactly one tariff applies. */
    public record Single(TariffEntry tariff) implements Resolution {
    }

    /** No tariff applies. */
    public record None(NoneReason reason) implements Resolution {
    }

    /** Two or more tariffs apply on the same days: none of them is charged. Ordered by name, then id. */
    public record Conflict(List<TariffEntry> tariffs) implements Resolution {

        public Conflict {
            tariffs = List.copyOf(tariffs);
        }
    }

    /**
     * @param snapshot     the charge types and tariffs of the cooperative
     * @param chargeTypeId the charge type
     * @param plot         the plot
     * @param from         the first day of the range
     * @param to           the last day of the range, not before {@code from}
     */
    public static Resolution resolve(Snapshot snapshot, UUID chargeTypeId, PlotFacts plot, LocalDate from,
            LocalDate to) {
        Objects.requireNonNull(plot, "plot");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("The range ends on or after its first day");
        }
        if (!snapshot.chargeType(chargeTypeId).map(ChargeTypeEntry::charges).orElse(false)) {
            return new None(NoneReason.TYPE_INACTIVE);
        }
        List<TariffEntry> valid = snapshot.tariffsOf(chargeTypeId).stream()
                .filter(tariff -> tariff.firstDay().map(first -> !first.isAfter(to)).orElse(false))
                .toList();
        if (valid.isEmpty()) {
            return new None(NoneReason.NO_TARIFF);
        }
        if (plot.archived() || plot.commonLand()) {
            return new None(NoneReason.OUTSIDE_SCOPE);
        }
        List<TariffEntry> applying = valid.stream().filter(tariff -> tariff.covers(plot.plotId())).toList();
        if (applying.size() == 1) {
            return new Single(applying.getFirst());
        }
        if (applying.size() > 1) {
            return new Conflict(applying);
        }
        boolean excluded = valid.stream().anyMatch(tariff -> tariff.scope() == TariffBook.Scope.ALL_PLOTS
                && tariff.excludedPlots().contains(plot.plotId()));
        return new None(excluded ? NoneReason.EXCLUDED : NoneReason.OUTSIDE_SCOPE);
    }
}
