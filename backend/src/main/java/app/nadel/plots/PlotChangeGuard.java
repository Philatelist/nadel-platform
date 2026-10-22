package app.nadel.plots;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * A veto on deleting, archiving or turning a plot into common land, implemented by other modules that hold data about
 * plots (for example {@code meters}, which refuses while the plot has a meter). The interface lives here and the
 * implementation in the module that has the data, so the dependency stays one-way (ADR 0011).
 * <p>
 * The plots module asks every guard bean: inside {@code PlotCommands.delete}, {@code archive} and {@code update} (the
 * change of a plot to common land), after the plot row has been locked {@code FOR UPDATE}, and while planning the Plots
 * sheet of an Excel import, where a refusal becomes a row problem on the {@code kind} column (the commit plans again under
 * the same plot locks). A guard answers with the plots it refuses instead of throwing, so the import can ask for many
 * plots at once; the plots module turns a refusal of a single change into a 409 with the guard's code.
 * <p>
 * Implementations must only read, must not call back into the {@code plots} module and must not take locks: the writers
 * of the guard's own data that depend on a plot take the plot's {@code FOR SHARE} lock (see
 * {@link PlotCatalog#findAndLockShared}), which conflicts with the {@code FOR UPDATE} lock held here, so a check and
 * such a write run one after another.
 */
public interface PlotChangeGuard {

    /** The change of a plot a guard is asked about. */
    enum Change {
        /** The plot is deleted. */
        DELETE,
        /** The plot is archived. */
        ARCHIVE,
        /** A plot of kind {@code PLOT} becomes common land. */
        BECOME_COMMON_LAND
    }

    /**
     * Why a guard refuses a change.
     *
     * @param code   the stable problem code of the API contract (for example {@code PLOT_HAS_METER})
     * @param detail an English sentence for developers, without personal data
     */
    record Refusal(String code, String detail) {
    }

    /**
     * @param change  the change about to be made
     * @param plotIds the plots of the caller's cooperative (bound to the transaction) about to change; never empty
     * @return the plots among {@code plotIds} this guard refuses the change for, each with its refusal; empty when it
     *         allows all of them
     */
    Map<UUID, Refusal> refusals(Change change, Collection<UUID> plotIds);
}
