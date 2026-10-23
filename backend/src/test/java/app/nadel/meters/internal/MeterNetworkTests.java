package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.meters.MeterNetwork;
import app.nadel.meters.MeterNetwork.StaleMeter;
import app.nadel.meters.MeterResource;
import app.nadel.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link MeterNetwork#staleMeters}: the plot meters that worked on the check date and whose last point on or before it
 * (the latest approved reading, else the installation) is more than the given days before it (feature 011 §2.2, the
 * closing check list); {@link MeterNetwork#network}: the electricity chains of a range with their histories and their
 * places in the tree by days (feature 011 §2.5, the losses runs). Fixtures are written as the database owner with
 * serials {@code MT-...}, which the base class removes.
 */
class MeterNetworkTests extends MeterTestSupport {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);

    @Autowired
    MeterNetwork network;

    @Autowired
    PlatformTransactionManager transactionManager;

    private UUID meter(UUID plot, String kind, String resource, String installedOn, String removedOn) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into meter (id, tenant_id, kind, resource, serial, name, plot_id, installed_on, "
                + "initial_value, removed_on, final_value) values (?, ?, ?, ?, ?, ?, ?, ?::date, 0, ?::date, "
                + "case when ?::date is null then null else 10 end)", id, DEMO_TENANT, kind, resource,
                "MT-" + id.toString().substring(0, 8), plot == null ? "Node " + id : null, plot, installedOn,
                removedOn, removedOn);
        return id;
    }

    private Map<UUID, StaleMeter> stale(UUID tenant, LocalDate asOf) {
        List<StaleMeter> found = TenantContext.runAs(tenant,
                () -> new TransactionTemplate(transactionManager).execute(s -> network.staleMeters(asOf, 60)));
        return found.stream().collect(Collectors.toMap(StaleMeter::meterId, Function.identity()));
    }

    @Test
    void aPlotMeterWithoutAReadingForMoreThanTheDaysIsStale() {
        UUID plot = newPlot("Стара");
        UUID never = meter(plot, "PLOT", "ELECTRICITY", "2026-01-10", null);
        UUID waterPlot = newPlot("Стара");
        UUID water = meter(waterPlot, "PLOT", "WATER", "2026-01-10", null);
        connectToWater(waterPlot, "2026-01-01", null);
        UUID readLongAgo = meter(newPlot("Стара"), "PLOT", "ELECTRICITY", "2026-01-10", null);
        reading(readLongAgo, "2026-07-31", "50", "APPROVED");

        Map<UUID, StaleMeter> stale = stale(DEMO_TENANT, AS_OF);

        assertThat(stale.get(never)).isEqualTo(new StaleMeter(never, plot, MeterResource.ELECTRICITY,
                LocalDate.of(2026, 1, 10)));
        assertThat(stale.get(water).resource()).isEqualTo(MeterResource.WATER);
        // 31.07 is 61 days before 30.09
        assertThat(stale.get(readLongAgo).lastReadingOn()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    void aWaterMeterOfAPlotNotConnectedToWaterOnTheCheckDateIsNeverStale() {
        UUID neverConnected = meter(newPlot("Суха"), "PLOT", "WATER", "2026-01-10", null);
        UUID disconnectedPlot = newPlot("Суха");
        UUID disconnected = meter(disconnectedPlot, "PLOT", "WATER", "2026-01-10", null);
        connectToWater(disconnectedPlot, "2026-01-01", "2026-09-29");
        UUID laterPlot = newPlot("Суха");
        UUID connectedLater = meter(laterPlot, "PLOT", "WATER", "2026-01-10", null);
        connectToWater(laterPlot, "2026-10-01", null);
        UUID connectedPlot = newPlot("Суха");
        UUID connected = meter(connectedPlot, "PLOT", "WATER", "2026-01-10", null);
        connectToWater(connectedPlot, "2026-09-30", null);

        Map<UUID, StaleMeter> stale = stale(DEMO_TENANT, AS_OF);

        assertThat(stale).doesNotContainKeys(neverConnected, disconnected, connectedLater).containsKey(connected);
    }

    /** A connection of the plot to the cooperative's water supply, written as the database owner. */
    private void connectToWater(UUID plot, String from, String until) {
        ownerJdbc().update("insert into plot_water_connection (tenant_id, plot_id, valid_from, valid_to) "
                + "values (?, ?, ?::date, ?::date)", DEMO_TENANT, plot, from, until);
    }

    @Test
    void aReadingWithinTheDaysOrAYoungMeterIsNotStale() {
        UUID read = meter(newPlot("Нова"), "PLOT", "ELECTRICITY", "2026-01-10", null);
        // 01.08 is exactly 60 days before 30.09
        reading(read, "2026-08-01", "50", "APPROVED");
        UUID young = meter(newPlot("Нова"), "PLOT", "ELECTRICITY", "2026-08-15", null);

        assertThat(stale(DEMO_TENANT, AS_OF)).doesNotContainKeys(read, young);
    }

    @Test
    void onlyApprovedReadingsUpToTheCheckDateCount() {
        UUID pending = meter(newPlot("Інша"), "PLOT", "ELECTRICITY", "2026-01-10", null);
        reading(pending, "2026-09-20", "50", "PENDING");
        UUID later = meter(newPlot("Інша"), "PLOT", "ELECTRICITY", "2026-01-10", null);
        reading(later, "2026-10-05", "50", "APPROVED");

        Map<UUID, StaleMeter> stale = stale(DEMO_TENANT, AS_OF);

        assertThat(stale.get(pending).lastReadingOn()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(stale.get(later).lastReadingOn()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(stale(DEMO_TENANT, LocalDate.of(2026, 10, 31))).doesNotContainKey(later);
    }

    @Test
    void onlyPlotMetersThatWorkedOnTheCheckDateAreListed() {
        UUID removed = meter(newPlot("Інша"), "PLOT", "ELECTRICITY", "2026-01-10", "2026-09-30");
        UUID removedLater = meter(newPlot("Інша"), "PLOT", "ELECTRICITY", "2026-01-10", "2026-10-15");
        UUID node = meter(null, "QUARTER", "ELECTRICITY", "2026-01-10", null);

        Map<UUID, StaleMeter> stale = stale(DEMO_TENANT, AS_OF);

        assertThat(stale).doesNotContainKeys(removed, node).containsKey(removedLater);
    }

    @Test
    void theNetworkHasTheChainsOfTheRangeWithTheirPlacesByDaysThroughTheRemovalDay() {
        UUID quarter = meter(null, "QUARTER", "ELECTRICITY", "2026-01-10", null);
        UUID old = meter(newPlot("Мережа"), "PLOT", "ELECTRICITY", "2026-01-10", "2026-09-20");
        UUID plot = ownerJdbc().queryForObject("select plot_id from meter where id = ?", UUID.class, old);
        UUID successor = UUID.randomUUID();
        ownerJdbc().update("insert into meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, "
                + "initial_value, replaces_meter_id) values (?, ?, 'PLOT', 'ELECTRICITY', ?, ?, date '2026-09-20', 3, ?)",
                successor, DEMO_TENANT, "MT-" + successor.toString().substring(0, 8), plot, old);
        UUID gone = meter(newPlot("Мережа"), "PLOT", "ELECTRICITY", "2026-01-10", "2026-09-15");
        parent(old, quarter, "2026-01-10", "2026-09-19");
        parent(successor, quarter, "2026-09-20", null);
        parent(gone, quarter, "2026-01-10", null);
        reading(old, "2026-08-31", "5", "APPROVED");
        reading(old, "2026-08-15", "4", "PENDING");

        Map<UUID, MeterNetwork.NetworkChain> september = chains(DEMO_TENANT, "2026-09-01", "2026-09-30");

        MeterNetwork.NetworkChain replaced = september.get(old);
        assertThat(replaced.meterIds()).containsExactly(old, successor);
        assertThat(replaced.shownMeterId()).isEqualTo(successor);
        assertThat(replaced.plotId()).isEqualTo(plot);
        assertThat(replaced.histories().getFirst().readings()).extracting(r -> r.takenOn().toString())
                .containsExactly("2026-08-31");
        // the periods of the replaced meter and its successor under the same quarter are one place
        assertThat(replaced.placements()).containsExactly(
                new MeterNetwork.Placement(quarter, LocalDate.of(2026, 1, 10), null));
        // a meter removed on 15.09 still counts under its parent on that day
        assertThat(september.get(gone).placements()).containsExactly(
                new MeterNetwork.Placement(quarter, LocalDate.of(2026, 1, 10), LocalDate.of(2026, 9, 15)));
        assertThat(september.get(quarter).placements()).containsExactly(
                new MeterNetwork.Placement(null, LocalDate.of(2026, 1, 10), null));
        assertThat(september.get(quarter).name()).isEqualTo("Node " + quarter);
        // a chain that did not work in the range is left out
        assertThat(chains(DEMO_TENANT, "2026-10-01", "2026-10-31")).containsKey(old).doesNotContainKey(gone);
        assertThat(chains(OTHER_TENANT, "2026-09-01", "2026-09-30")).doesNotContainKeys(old, quarter, gone);
        // the locked read gives the same network
        Map<UUID, MeterNetwork.NetworkChain> locked = TenantContext.runAs(DEMO_TENANT,
                () -> new TransactionTemplate(transactionManager).execute(s -> network.lockSharedNetwork(
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))).chains().stream()
                .collect(Collectors.toMap(MeterNetwork.NetworkChain::key, Function.identity()));
        assertThat(locked.get(old)).isEqualTo(replaced);
    }

    private void parent(UUID meter, UUID parent, String from, String to) {
        ownerJdbc().update("insert into meter_parent (tenant_id, meter_id, parent_meter_id, valid_from, valid_to) "
                + "values (?, ?, ?, ?::date, ?::date)", DEMO_TENANT, meter, parent, from, to);
    }

    private Map<UUID, MeterNetwork.NetworkChain> chains(UUID tenant, String from, String to) {
        MeterNetwork.Network found = TenantContext.runAs(tenant, () -> new TransactionTemplate(transactionManager)
                .execute(s -> network.network(LocalDate.parse(from), LocalDate.parse(to))));
        return found.chains().stream().collect(Collectors.toMap(MeterNetwork.NetworkChain::key,
                Function.identity()));
    }

    @Test
    void anotherCooperativeNeverSeesTheMeters() {
        UUID meter = meter(newPlot("Інша"), "PLOT", "ELECTRICITY", "2026-01-10", null);

        assertThat(stale(OTHER_TENANT, AS_OF)).doesNotContainKey(meter);
        assertThat(stale(DEMO_TENANT, AS_OF)).containsKey(meter);
    }
}
