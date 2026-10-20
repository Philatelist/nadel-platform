package app.nadel.plots;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.IntegrationTest;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** The read facade other modules use for plot facts: facts, many at once, the chargeable count, tenant isolation. */
class PlotCatalogTests extends IntegrationTest {

    @Autowired
    PlotCatalog catalog;

    @Autowired
    TransactionTemplate tx;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void removeTempPlots() {
        for (UUID id : created) {
            ownerJdbc().update("delete from plot where id = ?", id);
        }
        created.clear();
    }

    @Test
    void findsThePlotFactsOfAnActivePlot() {
        UUID id = plot(DEMO_TENANT, "PC-1", "PLOT", "612.50", false);

        PlotFacts facts = as(DEMO_TENANT, () -> catalog.find(id)).orElseThrow();

        assertThat(facts).isEqualTo(new PlotFacts(id, "PC-1", false, false, new BigDecimal("612.50")));
    }

    @Test
    void commonLandAndArchivedPlotsAreFlagged() {
        UUID road = plot(DEMO_TENANT, "PC-R", "COMMON", "1000.00", false);
        UUID old = plot(DEMO_TENANT, "PC-2", "PLOT", "100.00", true);

        assertThat(as(DEMO_TENANT, () -> catalog.find(road)).orElseThrow().commonLand()).isTrue();
        assertThat(as(DEMO_TENANT, () -> catalog.find(road)).orElseThrow().archived()).isFalse();
        PlotFacts archived = as(DEMO_TENANT, () -> catalog.find(old)).orElseThrow();
        assertThat(archived.archived()).isTrue();
        assertThat(archived.commonLand()).isFalse();
    }

    @Test
    void anUnknownPlotIsEmpty() {
        assertThat(as(DEMO_TENANT, () -> catalog.find(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void findAllReturnsTheKnownPlotsAndSkipsTheRest() {
        UUID a = plot(DEMO_TENANT, "PC-3", "PLOT", "600.00", false);
        UUID b = plot(DEMO_TENANT, "PC-4", "PLOT", "700.00", true);
        UUID unknown = UUID.randomUUID();

        Map<UUID, PlotFacts> found = as(DEMO_TENANT, () -> catalog.findAll(Set.of(a, b, unknown)));

        assertThat(found).containsOnlyKeys(a, b);
        assertThat(found.get(b).archived()).isTrue();
        assertThat(found.get(a).areaM2()).isEqualByComparingTo("600.00");
        assertThat(as(DEMO_TENANT, () -> catalog.findAll(List.of()))).isEmpty();
    }

    @Test
    void inNaturalOrderSortsByTheNumberAndSkipsUnknownPlotsAndOtherCooperatives() {
        UUID hundred = plot(DEMO_TENANT, "PN-100", "PLOT", "100.00", false);
        UUID tenB = plot(DEMO_TENANT, "PN-10б", "PLOT", "100.00", false);
        UUID nine = plot(DEMO_TENANT, "PN-9", "PLOT", "100.00", false);
        UUID tenA = plot(DEMO_TENANT, "PN-10а", "PLOT", "100.00", true);
        UUID theirs = plot(OTHER_TENANT, "PN-1", "PLOT", "100.00", false);

        List<UUID> ordered = as(DEMO_TENANT, () -> catalog.inNaturalOrder(
                List.of(hundred, tenB, nine, tenA, theirs, UUID.randomUUID())));

        assertThat(ordered).containsExactly(nine, tenA, tenB, hundred);
        assertThat(as(DEMO_TENANT, () -> catalog.inNaturalOrder(List.of()))).isEmpty();
    }

    @Test
    void countChargeableCountsActivePlotsWithoutCommonLand() {
        long before = expectedChargeable(DEMO_TENANT);
        plot(DEMO_TENANT, "PC-5", "PLOT", "100.00", false);
        plot(DEMO_TENANT, "PC-6", "PLOT", "100.00", true);
        plot(DEMO_TENANT, "PC-7", "COMMON", "100.00", false);

        long count = as(DEMO_TENANT, catalog::countChargeable);

        assertThat(count).isEqualTo(before + 1).isEqualTo(expectedChargeable(DEMO_TENANT));
        assertThat(before).as("the demo cooperative has plots").isPositive();
    }

    @Test
    void anotherCooperativeIsInvisibleAndCountedSeparately() {
        UUID mine = plot(DEMO_TENANT, "PC-8", "PLOT", "100.00", false);
        UUID theirs = plot(OTHER_TENANT, "PC-9", "PLOT", "100.00", false);

        assertThat(as(OTHER_TENANT, () -> catalog.find(mine))).isEmpty();
        assertThat(as(DEMO_TENANT, () -> catalog.find(theirs))).isEmpty();
        assertThat(as(OTHER_TENANT, () -> catalog.findAll(List.of(mine, theirs)))).containsOnlyKeys(theirs);
        assertThat(as(OTHER_TENANT, catalog::countChargeable)).isEqualTo(expectedChargeable(OTHER_TENANT));
    }

    @Test
    void withoutACooperativeNothingIsVisible() {
        UUID id = plot(DEMO_TENANT, "PC-10", "PLOT", "100.00", false);

        Optional<PlotFacts> found = tx.execute(status -> catalog.find(id));

        assertThat(found).isEmpty();
    }

    private <T> T as(UUID tenant, Supplier<T> work) {
        return TenantContext.runAs(tenant, () -> tx.execute(status -> work.get()));
    }

    private long expectedChargeable(UUID tenant) {
        return ownerJdbc().queryForObject("select count(*) from plot where tenant_id = ? and archived_at is null "
                + "and kind <> 'COMMON'", Long.class, tenant);
    }

    private UUID plot(UUID tenant, String number, String kind, String area, boolean archived) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2, archived_at) "
                + "values (?, ?, ?, ?, ?, case when ? then now() end)", id, tenant, number, kind,
                new BigDecimal(area), archived);
        created.add(id);
        return id;
    }
}
