package app.nadel.settings.internal;

import app.nadel.audit.FieldChange;
import app.nadel.money.Money;
import app.nadel.settings.CommonConsumerSplit;
import app.nadel.settings.LossesMode;
import app.nadel.settings.PlotStatusVisibility;
import app.nadel.settings.Requisites;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Every setting of a cooperative at once. {@link #DEFAULTS} are the values of a cooperative that never saved its
 * settings (and the column defaults of {@code cooperative_settings}). The percentage keeps two decimals and the red
 * amount is money ({@link Money}, two decimals): neither is rounded here, the request was checked for that. The
 * requisites were normalised before they got here (trimmed, blank as null, the IBAN checked).
 */
record SettingsValues(PlotStatusVisibility memberPlotStatusVisibility, LossesMode lossesMode, BigDecimal lossesPercent,
        CommonConsumerSplit commonConsumerDefaultSplit, BigDecimal redAmount, int redDays, Requisites requisites) {

    static final SettingsValues DEFAULTS = new SettingsValues(PlotStatusVisibility.OWN_ONLY, LossesMode.FIXED_PERCENT,
            new BigDecimal("0.00"), CommonConsumerSplit.CONNECTED_PLOTS, new BigDecimal("1000.00"), 90,
            Requisites.NONE);

    SettingsValues {
        Objects.requireNonNull(memberPlotStatusVisibility, "memberPlotStatusVisibility");
        Objects.requireNonNull(lossesMode, "lossesMode");
        Objects.requireNonNull(commonConsumerDefaultSplit, "commonConsumerDefaultSplit");
        Objects.requireNonNull(requisites, "requisites");
        // numeric(5,2) and numeric(14,2) come back with scale 2; a value from a request was checked to have at most 2
        lossesPercent = lossesPercent.setScale(2, RoundingMode.UNNECESSARY);
        redAmount = Money.of(redAmount).value();
    }

    /**
     * The audit changes from {@code before} to these values: one per changed field, codes and plain numbers, and the
     * requisites as text (public data of the legal entity, not personal data; null when not set).
     */
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
        add(changes, "officialName", before.requisites.officialName(), requisites.officialName());
        add(changes, "legalAddress", before.requisites.legalAddress(), requisites.legalAddress());
        add(changes, "bankName", before.requisites.bankName(), requisites.bankName());
        add(changes, "iban", before.requisites.iban(), requisites.iban());
        return changes;
    }

    private static void add(List<FieldChange> changes, String field, @Nullable String before, @Nullable String after) {
        if (!Objects.equals(before, after)) {
            changes.add(new FieldChange(field, before, after));
        }
    }
}
