package app.nadel.plots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The read facade other modules use for plot facts: facts with the street, many at once, by number, the natural and the
 * street order, the shared lock, the chargeable count, the active plots of a street, tenant isolation.
 */
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
        ownerJdbc().update("update plot set street = 'вул. Садова' where id = ?", id);
        UUID noStreet = plot(DEMO_TENANT, "PC-1a", "PLOT", "600.00", false);

        PlotFacts facts = as(DEMO_TENANT, () -> catalog.find(id)).orElseThrow();

        assertThat(facts).isEqualTo(new PlotFacts(id, "PC-1", "вул. Садова", false, false, new BigDecimal("612.50")));
        assertThat(as(DEMO_TENANT, () -> catalog.find(noStreet)).orElseThrow().street()).isNull();
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
    void inStreetOrderSortsByTheUkrainianAlphabetThenTheNumberWithPlotsWithoutAStreetLast() {
        UUID yablunevaTen = plot(DEMO_TENANT, "PS-10", "PLOT", "100.00", false, "PS Яблунева");
        UUID yablunevaNine = plot(DEMO_TENANT, "PS-9", "PLOT", "100.00", false, "PS Яблунева");
        UUID ganok = plot(DEMO_TENANT, "PS-1", "PLOT", "100.00", false, "PS Ґанкова");
        UUID hirska = plot(DEMO_TENANT, "PS-2", "PLOT", "100.00", false, "ps гірська");
        UUID yizhakova = plot(DEMO_TENANT, "PS-3", "PLOT", "100.00", false, "PS Їжакова");
        UUID ivanova = plot(DEMO_TENANT, "PS-4", "PLOT", "100.00", false, "PS Іванова");
        UUID none = plot(DEMO_TENANT, "PS-0", "PLOT", "100.00", false, null);
        UUID theirs = plot(OTHER_TENANT, "PS-5", "PLOT", "100.00", false, "PS Абрикосова");

        List<UUID> ordered = as(DEMO_TENANT, () -> catalog.inStreetOrder(List.of(none, yablunevaTen, yizhakova,
                theirs, ganok, yablunevaNine, ivanova, hirska, UUID.randomUUID())));

        assertThat(ordered).containsExactly(hirska, ganok, ivanova, yizhakova, yablunevaNine, yablunevaTen, none);
        assertThat(as(DEMO_TENANT, () -> catalog.inStreetOrder(List.of()))).isEmpty();
    }

    @Test
    void findActiveByNumbersIgnoresCaseArchivedPlotsAndOtherCooperatives() {
        UUID active = plot(DEMO_TENANT, "PB-12а", "PLOT", "100.00", false);
        plot(DEMO_TENANT, "PB-13", "PLOT", "100.00", true);
        plot(OTHER_TENANT, "PB-14", "PLOT", "100.00", false);

        List<PlotFacts> found = as(DEMO_TENANT, () -> catalog.findActiveByNumbers(
                List.of(" pb-12А ", "PB-13", "PB-14", "PB-unknown")));

        assertThat(found).extracting(PlotFacts::id).containsExactly(active);
        assertThat(as(DEMO_TENANT, () -> catalog.findActiveByNumbers(List.of()))).isEmpty();
    }

    @Test
    void findAndLockSharedHoldsAShareLockUntilTheEndOfTheTransaction() {
        UUID id = plot(DEMO_TENANT, "PL-1", "PLOT", "100.00", false);

        TenantContext.runAs(DEMO_TENANT, () -> tx.execute(status -> {
            assertThat(catalog.findAndLockShared(id)).map(PlotFacts::number).contains("PL-1");
            // another session may share the lock but not take the plot FOR UPDATE (a delete, archive or edit)
            assertThat(ownerJdbc().queryForList("select id from plot where id = ? for share nowait", UUID.class, id))
                    .containsExactly(id);
            assertThatThrownBy(() -> ownerJdbc().queryForList("select id from plot where id = ? for update nowait",
                    UUID.class, id)).isInstanceOf(DataAccessException.class);
            return null;
        }));
        // released at the end of the transaction
        assertThat(ownerJdbc().queryForList("select id from plot where id = ? for update nowait", UUID.class, id))
                .containsExactly(id);
        assertThat(as(DEMO_TENANT, () -> catalog.findAndLockShared(UUID.randomUUID()))).isEmpty();
        assertThat(as(OTHER_TENANT, () -> catalog.findAndLockShared(id))).isEmpty();
    }

    @Test
    void findAndLockSharedNeedsATransaction() {
        UUID id = plot(DEMO_TENANT, "PL-2", "PLOT", "100.00", false);

        assertThatThrownBy(() -> TenantContext.runAs(DEMO_TENANT, () -> catalog.findAndLockShared(id)))
                .isInstanceOf(IllegalTransactionStateException.class);
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

    @Test
    void idsOnStreetIgnoresCaseAndSpacesAndIncludesArchivedPlotsButNotOtherCooperatives() {
        UUID a = plot(DEMO_TENANT, "PC-S1", "PLOT", "600.00", false, "Каштанова-PC");
        UUID b = plot(DEMO_TENANT, "PC-S2", "PLOT", "600.00", true, "  каштанова-pc ");
        UUID road = plot(DEMO_TENANT, "PC-S3", "COMMON", "100.00", false, "КАШТАНОВА-PC");
        plot(DEMO_TENANT, "PC-S4", "PLOT", "600.00", false, "Каштанова-PC 2");
        plot(OTHER_TENANT, "PC-S5", "PLOT", "600.00", false, "Каштанова-PC");

        assertThat(as(DEMO_TENANT, () -> catalog.idsOnStreet(" каштанова-PC "))).containsExactlyInAnyOrder(a, b,
                road);
        assertThat(as(DEMO_TENANT, () -> catalog.idsOnStreet("   "))).isEmpty();
        assertThat(as(DEMO_TENANT, () -> catalog.idsOnStreet("Невідома-PC"))).isEmpty();
    }

    @Test
    void findActiveListsActivePlotsWithoutCommonLandInNaturalOrderOptionallyOfOneStreet() {
        UUID ten = plot(DEMO_TENANT, "PA-10", "PLOT", "600.00", false, "Липова-PA");
        UUID nine = plot(DEMO_TENANT, "PA-9", "PLOT", "500.00", false, "  липова-pa ");
        plot(DEMO_TENANT, "PA-8", "PLOT", "600.00", true, "Липова-PA");
        plot(DEMO_TENANT, "PA-7", "COMMON", "100.00", false, "Липова-PA");
        UUID elsewhere = plot(DEMO_TENANT, "PA-6", "PLOT", "600.00", false, "Кленова-PA");
        plot(OTHER_TENANT, "PA-5", "PLOT", "600.00", false, "Липова-PA");

        List<PlotFacts> onStreet = as(DEMO_TENANT, () -> catalog.findActive(" ЛИПОВА-pa "));
        List<PlotFacts> everyStreet = as(DEMO_TENANT, () -> catalog.findActive(null));

        assertThat(onStreet).extracting(PlotFacts::id).containsExactly(nine, ten);
        assertThat(onStreet.getFirst()).isEqualTo(new PlotFacts(nine, "PA-9", "  липова-pa ", false, false,
                new BigDecimal("500.00")));
        assertThat(everyStreet).isEqualTo(as(DEMO_TENANT, catalog::findChargeable));
        assertThat(everyStreet).extracting(PlotFacts::id).contains(nine, ten, elsewhere);
        assertThat(as(DEMO_TENANT, () -> catalog.findActive("   "))).isEqualTo(everyStreet);
        assertThat(as(DEMO_TENANT, () -> catalog.findActive("Невідома-PA"))).isEmpty();
    }

    private <T> T as(UUID tenant, Supplier<T> work) {
        return TenantContext.runAs(tenant, () -> tx.execute(status -> work.get()));
    }

    private long expectedChargeable(UUID tenant) {
        return ownerJdbc().queryForObject("select count(*) from plot where tenant_id = ? and archived_at is null "
                + "and kind <> 'COMMON'", Long.class, tenant);
    }

    private UUID plot(UUID tenant, String number, String kind, String area, boolean archived) {
        return plot(tenant, number, kind, area, archived, null);
    }

    private UUID plot(UUID tenant, String number, String kind, String area, boolean archived, String street) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, street, kind, area_m2, archived_at) "
                + "values (?, ?, ?, ?, ?, ?, case when ? then now() end)", id, tenant, number, street, kind,
                new BigDecimal(area), archived);
        created.add(id);
        return id;
    }
}
