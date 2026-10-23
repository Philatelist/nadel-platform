package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.IntegrationTest;
import app.nadel.meters.MeterCatalog;
import app.nadel.meters.MeterCatalog.MeterFacts;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterResource;
import app.nadel.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The public {@link MeterCatalog} that the tariffs module asks: which plots have an active plot meter of a resource now,
 * and whether a plot had one working on a day of a range (both ends included); and that the ledger asks about one meter
 * (its kind, name and whether it is active); always within the caller's cooperative.
 */
class MeterCatalogTests extends IntegrationTest {

    @Autowired
    MeterCatalog catalog;

    private final List<UUID> plots = new ArrayList<>();

    private final List<UUID> namedMeters = new ArrayList<>();

    @AfterEach
    void removeTestPlots() {
        for (UUID meter : namedMeters) {
            ownerJdbc().update("delete from meter where id = ?", meter);
        }
        for (UUID plot : plots) {
            ownerJdbc().update("delete from meter where plot_id = ?", plot);
            ownerJdbc().update("delete from plot where id = ?", plot);
        }
    }

    private UUID plot(UUID tenant) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 600)", id,
                tenant, "MC-" + id.toString().substring(0, 8));
        plots.add(id);
        return id;
    }

    private void meter(UUID plot, String resource, String installedOn, String removedOn) {
        ownerJdbc().update("insert into meter (tenant_id, kind, resource, serial, plot_id, installed_on, initial_value, "
                + "removed_on, final_value) select tenant_id, 'PLOT', ?, 'MC-1', id, ?::date, 0, ?::date, "
                + "case when ?::date is null then null else 0 end from plot where id = ?", resource, installedOn,
                removedOn, removedOn, plot);
    }

    @Test
    void onlyPlotsWithAnActiveMeterOfTheResourceOfTheCallersCooperativeAreListed() {
        UUID electricity = plot(DEMO_TENANT);
        UUID removed = plot(DEMO_TENANT);
        UUID water = plot(DEMO_TENANT);
        UUID other = plot(OTHER_TENANT);
        meter(electricity, "ELECTRICITY", "2026-01-10", null);
        meter(removed, "ELECTRICITY", "2026-01-10", "2026-05-01");
        meter(water, "WATER", "2026-01-10", null);
        meter(other, "ELECTRICITY", "2026-01-10", null);

        assertThat(TenantContext.runAs(DEMO_TENANT, () -> catalog.plotIdsWithActiveMeter(MeterResource.ELECTRICITY)))
                .contains(electricity).doesNotContain(removed, water, other);
        assertThat(TenantContext.runAs(DEMO_TENANT, () -> catalog.plotIdsWithActiveMeter(MeterResource.WATER)))
                .contains(water).doesNotContain(electricity, removed, other);
        assertThat(TenantContext.runAs(OTHER_TENANT, () -> catalog.plotIdsWithActiveMeter(MeterResource.ELECTRICITY)))
                .contains(other).doesNotContain(electricity);
    }

    @Test
    void aMeterCountsForARangeWhenItWorkedOnOneOfItsDaysBothEndsIncluded() {
        UUID plot = plot(DEMO_TENANT);
        meter(plot, "ELECTRICITY", "2026-03-31", "2026-09-01");

        assertThat(between(plot, MeterResource.ELECTRICITY, "2026-03-01", "2026-03-30")).isFalse();
        assertThat(between(plot, MeterResource.ELECTRICITY, "2026-03-01", "2026-03-31")).isTrue();
        assertThat(between(plot, MeterResource.ELECTRICITY, "2026-09-01", "2026-09-30")).isTrue();
        assertThat(between(plot, MeterResource.ELECTRICITY, "2026-09-02", "2026-09-30")).isFalse();
        assertThat(between(plot, MeterResource.WATER, "2026-05-01", "2026-05-31")).isFalse();
        // another cooperative does not see the meter
        assertThat(TenantContext.runAs(OTHER_TENANT, () -> catalog.hasMeterBetween(plot, MeterResource.ELECTRICITY,
                LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-31")))).isFalse();
    }

    private boolean between(UUID plot, MeterResource resource, String from, String to) {
        return TenantContext.runAs(DEMO_TENANT, () -> catalog.hasMeterBetween(plot, resource, LocalDate.parse(from),
                LocalDate.parse(to)));
    }

    @Test
    void oneMeterIsFoundWithItsKindNameAndStateInTheCallersCooperativeOnly() {
        UUID well = UUID.randomUUID();
        ownerJdbc().update("insert into meter (id, tenant_id, kind, resource, serial, name, installed_on, "
                + "initial_value, removed_on, final_value) values (?, ?, 'COMMON_CONSUMER', 'ELECTRICITY', 'MC-W', 'Свердловина', "
                + "date '2026-01-10', 0, date '2026-05-01', 10)", well, DEMO_TENANT);
        namedMeters.add(well);

        assertThat(TenantContext.runAs(DEMO_TENANT, () -> catalog.find(well))).contains(
                new MeterFacts(well, MeterKind.COMMON_CONSUMER, MeterResource.ELECTRICITY, "Свердловина", false));
        assertThat(TenantContext.runAs(OTHER_TENANT, () -> catalog.find(well))).isEmpty();
        assertThat(TenantContext.runAs(DEMO_TENANT, () -> catalog.find(UUID.randomUUID()))).isEmpty();
    }
}
