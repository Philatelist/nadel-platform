package app.nadel.tariffs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.money.Rate;
import app.nadel.tariffs.TariffBook.ChargeTypeEntry;
import app.nadel.tariffs.TariffBook.Scope;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
import app.nadel.tariffs.TariffBook.VersionRef;
import app.nadel.tariffs.TariffResolver.Conflict;
import app.nadel.tariffs.TariffResolver.None;
import app.nadel.tariffs.TariffResolver.NoneReason;
import app.nadel.tariffs.TariffResolver.PlotFacts;
import app.nadel.tariffs.TariffResolver.Resolution;
import app.nadel.tariffs.TariffResolver.Single;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Which tariff of a charge type charges a plot over a range (feature 011 §2.3): one, none with the reason, or a
 * conflict naming both tariffs.
 */
class TariffResolverTests {

    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID PLOT_7 = UUID.randomUUID();
    private static final UUID PLOT_12 = UUID.randomUUID();
    private static final LocalDate OCT_1 = LocalDate.parse("2026-10-01");
    private static final LocalDate OCT_31 = LocalDate.parse("2026-10-31");

    private static PlotFacts plot(UUID id) {
        return new PlotFacts(id, false, false, new BigDecimal("600.00"));
    }

    private static TariffEntry tariff(String name, Scope scope, Set<UUID> selected, Set<UUID> excluded,
            String... validFrom) {
        List<VersionRef> versions = Arrays.stream(validFrom)
                .map(from -> new VersionRef(UUID.randomUUID(), new VersionTerms(LocalDate.parse(from),
                        Basis.PER_AREA_SOTKA, Frequency.YEARLY, Rate.parse("50"), null)))
                .toList();
        return new TariffEntry(UUID.randomUUID(), MEMBERSHIP, name, scope, selected, excluded, versions);
    }

    private static TariffEntry allPlots(String name, String... validFrom) {
        return tariff(name, Scope.ALL_PLOTS, Set.of(), Set.of(), validFrom);
    }

    private static Snapshot snapshot(boolean active, boolean archived, TariffEntry... tariffs) {
        return new Snapshot(List.of(new ChargeTypeEntry(MEMBERSHIP, "MEMBERSHIP", active, archived)),
                List.of(tariffs));
    }

    private static Resolution resolve(Snapshot snapshot, PlotFacts plot) {
        return TariffResolver.resolve(snapshot, MEMBERSHIP, plot, OCT_1, OCT_31);
    }

    @Test
    void oneTariffForAllPlotsIsSingle() {
        TariffEntry membership = allPlots("Membership", "2026-01-01");

        assertThat(resolve(snapshot(true, false, membership), plot(PLOT_7))).isEqualTo(new Single(membership));
    }

    @Test
    void aTariffStartingInsideTheRangeApplies() {
        TariffEntry membership = allPlots("Membership", "2026-10-16");

        assertThat(resolve(snapshot(true, false, membership), plot(PLOT_7))).isEqualTo(new Single(membership));
    }

    @Test
    void twoTariffsOfTheTypeOnTheSamePlotConflictAndBothAreNamed() {
        TariffEntry main = allPlots("Main", "2026-01-01");
        TariffEntry extra = tariff("Extra", Scope.SELECTED_PLOTS, Set.of(PLOT_12), Set.of(), "2026-10-20");

        Resolution resolution = resolve(snapshot(true, false, extra, main), plot(PLOT_12));

        assertThat(resolution).isInstanceOf(Conflict.class);
        assertThat(((Conflict) resolution).tariffs()).containsExactly(extra, main);
        // plot 7 is only in the scope of the main tariff
        assertThat(resolve(snapshot(true, false, extra, main), plot(PLOT_7))).isEqualTo(new Single(main));
    }

    @Test
    void aTariffThatStartsAfterTheRangeNeitherAppliesNorConflicts() {
        TariffEntry main = allPlots("Main", "2026-01-01");
        TariffEntry next = allPlots("Next year", "2027-01-01");

        assertThat(resolve(snapshot(true, false, main, next), plot(PLOT_7))).isEqualTo(new Single(main));
        assertThat(TariffResolver.resolve(snapshot(true, false, main, next), MEMBERSHIP, plot(PLOT_7),
                LocalDate.parse("2027-01-01"), LocalDate.parse("2027-01-31"))).isInstanceOf(Conflict.class);
    }

    @Test
    void noTariffValidInTheRangeIsNoTariff() {
        assertThat(resolve(snapshot(true, false), plot(PLOT_7))).isEqualTo(new None(NoneReason.NO_TARIFF));
        assertThat(resolve(snapshot(true, false, allPlots("Later", "2026-11-01")), plot(PLOT_7)))
                .isEqualTo(new None(NoneReason.NO_TARIFF));
        assertThat(resolve(snapshot(true, false, tariff("No versions", Scope.ALL_PLOTS, Set.of(), Set.of())),
                plot(PLOT_7))).isEqualTo(new None(NoneReason.NO_TARIFF));
    }

    @Test
    void anExcludedPlotIsExcludedAndAnUnselectedOneOutsideTheScope() {
        TariffEntry withExclusion = tariff("Membership", Scope.ALL_PLOTS, Set.of(), Set.of(PLOT_7), "2026-01-01");
        TariffEntry selected = tariff("Selected", Scope.SELECTED_PLOTS, Set.of(PLOT_12), Set.of(), "2026-01-01");

        assertThat(resolve(snapshot(true, false, withExclusion), plot(PLOT_7)))
                .isEqualTo(new None(NoneReason.EXCLUDED));
        assertThat(resolve(snapshot(true, false, selected), plot(PLOT_7)))
                .isEqualTo(new None(NoneReason.OUTSIDE_SCOPE));
        // excluded from one, selected in another: the other applies alone
        assertThat(resolve(snapshot(true, false, withExclusion, selected), plot(PLOT_12)))
                .isInstanceOf(Conflict.class);
        assertThat(resolve(snapshot(true, false, withExclusion,
                tariff("Selected 7", Scope.SELECTED_PLOTS, Set.of(PLOT_7), Set.of(), "2026-01-01")), plot(PLOT_7)))
                .isInstanceOf(Single.class);
    }

    @Test
    void anInactiveArchivedOrUnknownTypeChargesNothing() {
        TariffEntry membership = allPlots("Membership", "2026-01-01");

        assertThat(resolve(snapshot(false, false, membership), plot(PLOT_7)))
                .isEqualTo(new None(NoneReason.TYPE_INACTIVE));
        assertThat(resolve(snapshot(true, true, membership), plot(PLOT_7)))
                .isEqualTo(new None(NoneReason.TYPE_INACTIVE));
        assertThat(TariffResolver.resolve(snapshot(true, false, membership), UUID.randomUUID(), plot(PLOT_7), OCT_1,
                OCT_31)).isEqualTo(new None(NoneReason.TYPE_INACTIVE));
    }

    @Test
    void anArchivedPlotAndCommonLandAreOutsideEveryScope() {
        Snapshot snapshot = snapshot(true, false, allPlots("Membership", "2026-01-01"));

        assertThat(resolve(snapshot, new PlotFacts(PLOT_7, false, true, new BigDecimal("600.00"))))
                .isEqualTo(new None(NoneReason.OUTSIDE_SCOPE));
        assertThat(resolve(snapshot, new PlotFacts(PLOT_7, true, false, BigDecimal.ZERO)))
                .isEqualTo(new None(NoneReason.OUTSIDE_SCOPE));
    }

    @Test
    void aRangeEndsOnOrAfterItsFirstDay() {
        assertThatThrownBy(() -> TariffResolver.resolve(snapshot(true, false), MEMBERSHIP, plot(PLOT_7), OCT_31,
                OCT_1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSnapshotFindsTariffsVersionsAndCharges() {
        TariffEntry membership = allPlots("Membership", "2026-11-01", "2026-01-01");
        Snapshot snapshot = snapshot(true, false, membership);

        assertThat(membership.terms()).extracting(VersionTerms::validFrom)
                .containsExactly(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-11-01"));
        assertThat(membership.firstDay()).contains(LocalDate.parse("2026-01-01"));
        assertThat(membership.versionStartingOn(LocalDate.parse("2026-11-01")))
                .contains(membership.versions().get(1).id());
        assertThat(membership.versionStartingOn(LocalDate.parse("2026-05-01"))).isEmpty();
        assertThat(snapshot.tariff(membership.id())).contains(membership);
        assertThat(snapshot.tariff(UUID.randomUUID())).isEmpty();
        assertThat(snapshot.chargeType(MEMBERSHIP).orElseThrow().charges()).isTrue();
    }
}
