package app.nadel.plots.internal;

import app.nadel.plots.PlotChangeGuard;
import app.nadel.plots.PlotChangeGuard.Change;
import app.nadel.plots.PlotChangeGuard.Refusal;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Asks every {@link PlotChangeGuard} bean of the other modules (ADR 0011). The callers have locked the plots
 * {@code FOR UPDATE} before they ask; the guards only read.
 */
@Component
class PlotChangeGuards {

    private final List<PlotChangeGuard> guards;

    PlotChangeGuards(List<PlotChangeGuard> guards) {
        this.guards = List.copyOf(guards);
    }

    /**
     * Refuses the change of one plot with 409 and the code of the first guard that refuses it.
     *
     * @param field the request field the refusal is about ({@code kind} for a change to common land), or null for
     *              the whole plot
     */
    void ensureAllowed(Change change, UUID plotId, @Nullable String field) {
        Refusal refusal = refusals(change, List.of(plotId)).get(plotId);
        if (refusal != null) {
            List<FieldProblem> errors = field == null ? List.of() : List.of(new FieldProblem(field, refusal.code()));
            throw new ApiProblemException(HttpStatus.CONFLICT, refusal.code(), refusal.detail(), errors);
        }
    }

    /** The plots among {@code plotIds} that some guard refuses the change for; the first guard's refusal wins. */
    Map<UUID, Refusal> refusals(Change change, Collection<UUID> plotIds) {
        if (plotIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Refusal> refused = new HashMap<>();
        for (PlotChangeGuard guard : guards) {
            guard.refusals(change, plotIds).forEach(refused::putIfAbsent);
        }
        return refused;
    }
}
