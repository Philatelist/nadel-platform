package app.nadel.meters.internal;

import app.nadel.meters.ConsumptionCalculator.RangeConsumption;
import app.nadel.meters.ConsumptionCalculator.ValueAt;
import app.nadel.meters.MeterTreeLosses;
import app.nadel.meters.MeterTreeQuantity;
import app.nadel.meters.MeterTreeUnknownReason;
import app.nadel.meters.MeterTreeValue;
import app.nadel.meters.MeterValueKind;
import app.nadel.meters.Quantity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The figures of the electricity report that the tree endpoint leaves to the reader (money rules 6.3): the sum of the
 * children's consumption and the losses of a node, in kWh and in percent. Pure and exact (sums of {@link Quantity});
 * the only rounding is the percent, two decimals HALF_UP for display, as the losses runs show it. A figure derived from
 * a value that is not known is unknown with the reason, never zero.
 */
final class TreeFigures {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private TreeFigures() {
    }

    /** A value of the tree as the report gives it: the exact value (a reading as a quantity) or unknown. */
    static MeterTreeValue value(UUID meterId, ValueAt value) {
        return switch (value) {
            case ValueAt.Real real -> new MeterTreeValue(MeterValueKind.REAL, meterId, Quantity.of(real.value()),
                    null);
            case ValueAt.Calculated calculated -> new MeterTreeValue(MeterValueKind.CALCULATED, meterId,
                    calculated.value(), null);
            case ValueAt.Unknown unknown -> new MeterTreeValue(MeterValueKind.UNKNOWN, meterId, null,
                    unknown.lastReadingOn());
        };
    }

    /** The node's own consumption: known, or unknown because a value of its meter is. */
    static MeterTreeQuantity consumption(RangeConsumption range) {
        return switch (range) {
            case RangeConsumption.Known known -> MeterTreeQuantity.known(known.consumption(), known.calculated());
            case RangeConsumption.Unknown unknown -> MeterTreeQuantity.unknown(MeterTreeUnknownReason.VALUE_UNKNOWN,
                    unknown.lastReadingOn());
        };
    }

    /**
     * The sum of the children's consumption: zero without children; calculated when any child's is; unknown
     * ({@link MeterTreeUnknownReason#CHILD_VALUE_UNKNOWN}) when any child's is, with the latest day after which such a
     * child has no reading.
     */
    static MeterTreeQuantity childrenSum(List<RangeConsumption> children) {
        Quantity sum = Quantity.ZERO;
        boolean calculated = false;
        boolean unknown = false;
        LocalDate lastReadingOn = null;
        for (RangeConsumption child : children) {
            switch (child) {
                case RangeConsumption.Known known -> {
                    sum = sum.plus(known.consumption());
                    calculated |= known.calculated();
                }
                case RangeConsumption.Unknown part -> {
                    unknown = true;
                    lastReadingOn = later(lastReadingOn, part.lastReadingOn());
                }
            }
        }
        return unknown ? MeterTreeQuantity.unknown(MeterTreeUnknownReason.CHILD_VALUE_UNKNOWN, lastReadingOn)
                : MeterTreeQuantity.known(sum, calculated);
    }

    /**
     * The losses of a node: its consumption minus its children's, exact and possibly negative, calculated when either
     * side is; unknown with the reason of the side that is not known (the node's own first).
     */
    static MeterTreeLosses losses(MeterTreeQuantity own, MeterTreeQuantity children) {
        if (!own.isKnown()) {
            return new MeterTreeLosses(own, null);
        }
        if (!children.isKnown()) {
            return new MeterTreeLosses(children, null);
        }
        Quantity consumption = own.kwh();
        Quantity losses = consumption.minus(children.kwh());
        return new MeterTreeLosses(MeterTreeQuantity.known(losses, own.calculated() || children.calculated()),
                percent(losses, consumption));
    }

    /** {@code part} in percent of {@code whole}, two decimals HALF_UP; null when {@code whole} is not above zero. */
    static @Nullable BigDecimal percent(Quantity part, Quantity whole) {
        if (whole.signum() <= 0) {
            return null;
        }
        BigDecimal numerator = new BigDecimal(part.numerator().multiply(whole.denominator())).multiply(HUNDRED);
        BigDecimal denominator = new BigDecimal(part.denominator().multiply(whole.numerator()));
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP); // a ratio for display, not money
    }

    private static @Nullable LocalDate later(@Nullable LocalDate date, @Nullable LocalDate other) {
        if (date == null) {
            return other;
        }
        return other != null && other.isAfter(date) ? other : date;
    }
}
