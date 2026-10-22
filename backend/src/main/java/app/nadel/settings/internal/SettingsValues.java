package app.nadel.settings.internal;

import app.nadel.audit.FieldChange;
import app.nadel.money.Money;
import app.nadel.settings.CommonConsumerSplit;
import app.nadel.settings.LossesMode;
import app.nadel.settings.PlotStatusVisibility;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Every setting of a cooperative at once. {@link #DEFAULTS} are the values of a cooperative that never saved its
 * settings (and the column defaults of {@code cooperative_settings}). The percentage keeps two decimals and the red
 * amount is money ({@link Money}, two decimals): neither is rounded here, the request was checked for that.
 */
record SettingsValues(PlotStatusVisibility memberPlotStatusVisibility, LossesMode lossesMode, BigDecimal lossesPercent,
        CommonConsumerSplit commonConsumerDefaultSplit, BigDecimal redAmount, int redDays) {

    static final SettingsValues DEFAULTS = new SettingsValues(PlotStatusVisibility.OWN_ONLY, LossesMode.FIXED_PERCENT,
            new BigDecimal("0.00"), CommonConsumerSplit.CONNECTED_PLOTS, new BigDecimal("1000.00"), 90);

    SettingsValues {
        Objects.requireNonNull(memberPlotStatusVisibility, "memberPlotStatusVisibility");
        Objects.requireNonNull(lossesMode, "lossesMode");
        Objects.requireNonNull(commonConsumerDefaultSplit, "commonConsumerDefaultSplit");
        // numeric(5,2) and numeric(14,2) come back with scale 2; a value from a request was checked to have at most 2
        lossesPercent = lossesPercent.setScale(2, RoundingMode.UNNECESSARY);
        redAmount = Money.of(redAmount).value();
    }

    /** The audit changes from {@code before} to these values: one per changed field, codes and plain numbers. */
    List<FieldChange> changesSince(SettingsValues before) {
        List<FieldChange> changes = new ArrayList<>();
        add(changes, "memberPlotStatusVisibility", before.memberPlotStatusVisibility.name(),
                memberPlotStatusVisibility.name());
        add(changes, "lossesMode", before.lossesMode.name(), lossesMode.name());
        add(changes, "lossesPercent", before.lossesPercent.toPlainString(), lossesPercent.toPlainString());
        add(changes, "commonConsumerDefaultSplit", before.commonConsumerDefaultSplit.name(),
                commonConsumerDefaultSplit.name());
        add(changes, "redAmount", before.redAmount.toPlainString(), redAmount.toPlainString());
        add(changes, "redDays", Integer.toString(before.redDays), Integer.toString(redDays));
        return changes;
    }

    private static void add(List<FieldChange> changes, String field, String before, String after) {
        if (!before.equals(after)) {
            changes.add(new FieldChange(field, before, after));
        }
    }
}
