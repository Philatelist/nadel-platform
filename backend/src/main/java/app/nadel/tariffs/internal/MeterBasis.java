package app.nadel.tariffs.internal;

import app.nadel.meters.MeterResource;
import app.nadel.tariffs.Basis;
import org.jspecify.annotations.Nullable;

/**
 * Which meter a basis is charged by: a per-kWh rate by the plot's electricity meter, a per-m3 rate by its water meter.
 * A tariff charged by meter applies only to the plots that have such a meter (spec 010, section 2.7).
 */
final class MeterBasis {

    private MeterBasis() {
    }

    /** The resource of a meter-based basis; null for every other basis. */
    static @Nullable MeterResource resourceOf(Basis basis) {
        return switch (basis) {
            case PER_KWH -> MeterResource.ELECTRICITY;
            case PER_M3 -> MeterResource.WATER;
            case PER_PLOT, PER_AREA_SOTKA, FIXED_ONE_TIME -> null;
        };
    }
}
