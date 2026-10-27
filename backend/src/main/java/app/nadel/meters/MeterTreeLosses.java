package app.nadel.meters;

import java.math.BigDecimal;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The losses of a substation or quarter meter over the range of a {@link MeterTreeReport} (money rules 6.3:
 * {@code node losses = node consumption − Σ consumption of its children}).
 *
 * @param kwh     the losses in kWh, exact and possibly negative; unknown with the reason when the node's consumption
 *                ({@link MeterTreeUnknownReason#VALUE_UNKNOWN}) or a child's
 *                ({@link MeterTreeUnknownReason#CHILD_VALUE_UNKNOWN}) is not known; calculated when either side is
 * @param percent the losses in percent of the node's consumption, rounded HALF_UP to two decimals for display (a ratio,
 *                not money; never used to calculate); null when the losses are unknown or the consumption is not above
 *                zero
 */
public record MeterTreeLosses(MeterTreeQuantity kwh, @Nullable BigDecimal percent) {

    public MeterTreeLosses {
        Objects.requireNonNull(kwh, "kwh");
        if (percent != null && !kwh.isKnown()) {
            throw new IllegalArgumentException("Unknown losses have no percent");
        }
    }
}
