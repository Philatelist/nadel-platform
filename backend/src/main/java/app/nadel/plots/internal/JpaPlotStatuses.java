package app.nadel.plots.internal;

import app.nadel.money.Money;
import app.nadel.plots.PlotStatus;
import app.nadel.plots.PlotStatuses;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves {@link PlotStatuses} with {@link PlotStatusPolicy}, the rule of the map, list and card: the plot rows with
 * their cached debt and current-owner flag ({@code PlotRepository.findRowsByIds}, 500 ids per {@code IN} list) and the
 * thresholds and today of {@link PlotStatusBasis}, read once per call.
 */
@Component
class JpaPlotStatuses implements PlotStatuses {

    /** Values per {@code IN} list. */
    private static final int CHUNK = 500;

    private final PlotRepository plots;
    private final PlotStatusBasis statusBasis;

    JpaPlotStatuses(PlotRepository plots, PlotStatusBasis statusBasis) {
        this.plots = plots;
        this.statusBasis = statusBasis;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, PlotStatus> current(Collection<UUID> plotIds) {
        PlotStatusPolicy.Basis basis = statusBasis.current();
        Map<UUID, PlotStatus> statuses = new LinkedHashMap<>();
        for (PlotRow row : rows(plotIds)) {
            Money debt = row.debt() == null ? Money.ZERO : Money.of(row.debt());
            statuses.put(row.plot().getId(), status(PlotStatusPolicy.statusOf(row.plot().getKind(), row.hasOwner(),
                    debt, row.oldestDebtDate(), basis)));
        }
        return statuses;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, PlotStatus> classify(Map<UUID, Debt> debts, LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        PlotStatusPolicy.Thresholds thresholds = statusBasis.current().thresholds();
        Map<UUID, PlotStatus> statuses = new LinkedHashMap<>();
        for (PlotRow row : rows(debts.keySet())) {
            Debt debt = debts.get(row.plot().getId());
            statuses.put(row.plot().getId(), status(PlotStatusPolicy.statusOf(row.plot().getKind(), row.hasOwner(),
                    debt.total(), debt.oldestUnpaid(), thresholds, asOf)));
        }
        return statuses;
    }

    private List<PlotRow> rows(Collection<UUID> plotIds) {
        List<UUID> ids = plotIds.stream().distinct().toList();
        List<PlotRow> rows = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += CHUNK) {
            rows.addAll(plots.findRowsByIds(ids.subList(from, Math.min(ids.size(), from + CHUNK))));
        }
        return rows;
    }

    private static PlotStatus status(PlotStatusCode code) {
        return PlotStatus.valueOf(code.name());
    }
}
