package app.nadel.settings;

import app.nadel.money.Money;
import java.math.BigDecimal;

/**
 * Settings of the current cooperative, read by other modules. The cooperative is the one bound to the current
 * transaction or request ({@code TenantContext}); a cooperative that never saved its settings has the defaults. Every
 * method reads the stored row; call it inside a transaction, and once per request rather than once per plot.
 */
public interface CooperativeSettings {

    /** Who sees the colour of plots that are not theirs; {@link PlotStatusVisibility#OWN_ONLY} when never saved. */
    PlotStatusVisibility memberPlotStatusVisibility();

    /** How losses are charged; {@link LossesMode#FIXED_PERCENT} when never saved. */
    LossesMode lossesMode();

    /** The percentage of a fixed-percentage losses charge, 0 to 100 with two decimals; {@code 0.00} when never saved. */
    BigDecimal lossesPercent();

    /** The split of a common consumer without its own; {@link CommonConsumerSplit#CONNECTED_PLOTS} when never saved. */
    CommonConsumerSplit commonConsumerDefaultSplit();

    /** A plot whose debt is above this amount is red; {@code 1000.00} when never saved. */
    Money redAmount();

    /** A plot whose oldest unpaid charge is older than this many days is red; 90 when never saved. */
    int redDays();

    /** The legal details of the cooperative; {@link Requisites#NONE} when never saved. */
    Requisites requisites();
}
