package app.nadel.meters.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.OwnedPlot;
import app.nadel.identity.OwnedPlotsSource;
import app.nadel.identity.Permission;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The member rule of the endpoints that a member may call for their own plots (the meters of a plot, the readings of a
 * meter): {@code METERS_VIEW} sees every meter, anyone else only the meters of a plot they own now, and everything
 * else, unknown ids and other cooperatives included, is the same 404 ({@code PLOT_NOT_FOUND} when the path names a
 * plot). The platform operator has no cooperative data.
 */
@Component
class MeterAccess {

    private final OwnedPlotsSource ownedPlots;

    MeterAccess(OwnedPlotsSource ownedPlots) {
        this.ownedPlots = ownedPlots;
    }

    /** 403 for the platform operator (and any caller without a cooperative). */
    void requireCooperativeData(AccessProfile viewer) {
        if (viewer.platformOperator() || viewer.tenantId() == null) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, ProblemCodes.PERMISSION_DENIED,
                    "The platform operator has no access to cooperative data.");
        }
    }

    /**
     * 404 {@code PLOT_NOT_FOUND}: the plot is unknown, of another cooperative or not one the caller may see; the same
     * answer in every case.
     */
    static ApiProblemException plotNotFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, ProblemCodes.PLOT_NOT_FOUND, "The plot was not found.");
    }

    /** Whether the viewer sees the whole register ({@code METERS_VIEW}); then also the board's note. */
    boolean seesEveryMeter(AccessProfile viewer) {
        return viewer.can(Permission.METERS_VIEW);
    }

    /** Whether the viewer may see the meter: with {@code METERS_VIEW}, or as a current owner of its plot. */
    boolean maySee(AccessProfile viewer, MeterRecord meter) {
        return seesEveryMeter(viewer) || (meter.getPlotId() != null && ownsNow(viewer, meter.getPlotId()));
    }

    /** Whether the viewer is one of the plot's owners today. */
    boolean ownsNow(AccessProfile viewer, UUID plotId) {
        UUID memberId = viewer.memberId();
        return memberId != null
                && ownedPlots.currentPlotsOf(memberId).stream().map(OwnedPlot::plotId).anyMatch(plotId::equals);
    }
}
