package app.nadel.plots.internal;

import app.nadel.identity.OwnedPlot;
import app.nadel.identity.OwnedPlotsSource;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Serves {@code GET /me} with the plots a member owns now. */
@Component
class OwnedPlotsAdapter implements OwnedPlotsSource {

    private final OwnershipQueries queries;

    OwnedPlotsAdapter(OwnershipQueries queries) {
        this.queries = queries;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OwnedPlot> currentPlotsOf(UUID memberId) {
        return queries.currentPlotsOf(memberId);
    }
}
