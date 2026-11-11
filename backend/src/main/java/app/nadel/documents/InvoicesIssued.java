package app.nadel.documents;

import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * New invoices were stored (feature 021 §2.2: the owners get the push «Новий рахунок за {місяць}»). Published inside
 * the transaction that stores them, once per build of a month's invoices with the plots whose invoice got version 1 or
 * a new version (never the unchanged or skipped ones; a build that stores nothing publishes nothing), and once per
 * invoice issued on request when it is a new version, with its one plot. Ids and the month only: a listener reads the
 * plots' owners through {@code plots.PlotOwners} after binding {@code tenantId} with {@code TenantContext.runAs}
 * (ADR 0007).
 *
 * @param tenantId the cooperative
 * @param month    the billing month of the invoices
 * @param buildId  the build: a new id per month build, the stored version's id for an invoice issued on request; a
 *                 listener uses it to act once per build
 * @param plotIds  the plots with a new invoice version, each once, never empty
 */
public record InvoicesIssued(UUID tenantId, YearMonth month, UUID buildId, List<UUID> plotIds) {

    public InvoicesIssued {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(buildId, "buildId");
        plotIds = List.copyOf(plotIds);
        if (plotIds.isEmpty()) {
            throw new IllegalArgumentException("An issue has at least one plot");
        }
    }
}
