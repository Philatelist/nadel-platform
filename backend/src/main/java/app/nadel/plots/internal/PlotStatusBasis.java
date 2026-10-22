package app.nadel.plots.internal;

import app.nadel.settings.CooperativeSettings;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Reads what every status of a request is derived from: the red threshold of the cooperative (settings
 * {@code redAmount} and {@code redDays}) and today in Europe/Kyiv. Read once per request, inside its transaction, so a
 * change of the threshold and the passing days apply at the next read without rewriting any cached row.
 */
@Component
class PlotStatusBasis {

    private final CooperativeSettings settings;
    private final Clock clock;

    PlotStatusBasis(CooperativeSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    PlotStatusPolicy.Basis current() {
        return new PlotStatusPolicy.Basis(new PlotStatusPolicy.Thresholds(settings.redAmount(), settings.redDays()),
                LocalDate.now(clock));
    }
}
