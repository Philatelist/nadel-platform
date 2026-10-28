package app.nadel.ledger.internal;

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
 * Who may see the account of a plot: {@code CHARGES_VIEW} sees every plot of the cooperative, anyone else only a plot
 * they own now, and everything else, unknown ids and other cooperatives included, is the same 404
 * {@code PLOT_NOT_FOUND}. The payments on an account: {@code PAYMENTS_VIEW}, or an owner of the plot today. The
 * platform operator has no cooperative data.
 */
@Component
class LedgerAccess {

    private final OwnedPlotsSource ownedPlots;

    LedgerAccess(OwnedPlotsSource ownedPlots) {
        this.ownedPlots = ownedPlots;
    }

    /** 403 for the platform operator (and any caller without a cooperative). */
    void requireCooperativeData(AccessProfile viewer) {
        if (viewer.platformOperator() || viewer.tenantId() == null) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, ProblemCodes.PERMISSION_DENIED,
                    "The platform operator has no access to cooperative data.");
        }
    }

    /** Whether the viewer may see the plot's account: with {@code CHARGES_VIEW}, or as one of its owners today. */
    boolean maySee(AccessProfile viewer, UUID plotId) {
        return viewer.can(Permission.CHARGES_VIEW) || ownsNow(viewer, plotId);
    }

    /**
     * Whether the viewer may see the payments of the plot on its account (feature 015 §2.9, §2.10): with
     * {@code PAYMENTS_VIEW}, or as one of its owners today. A viewer who sees the account without either (a custom
     * role with {@code CHARGES_VIEW} only) sees its debts and per-charge amounts but no payment.
     */
    boolean maySeePayments(AccessProfile viewer, UUID plotId) {
        return viewer.can(Permission.PAYMENTS_VIEW) || ownsNow(viewer, plotId);
    }

    /** Whether the viewer is a member who owns the plot today (any share). */
    private boolean ownsNow(AccessProfile viewer, UUID plotId) {
        UUID memberId = viewer.memberId();
        return memberId != null
                && ownedPlots.currentPlotsOf(memberId).stream().map(OwnedPlot::plotId).anyMatch(plotId::equals);
    }

    /** 404 {@code PLOT_NOT_FOUND}: the same answer for an unknown plot, another cooperative's and one not the caller's. */
    static ApiProblemException plotNotFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, ProblemCodes.PLOT_NOT_FOUND, "The plot was not found.");
    }
}
