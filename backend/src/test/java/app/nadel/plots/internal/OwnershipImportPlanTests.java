package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.IntegrationTest;
import app.nadel.plots.OwnershipImportItem;
import app.nadel.plots.OwnershipImportOutcome;
import app.nadel.plots.OwnershipImportProblem;
import app.nadel.plots.OwnershipImportRow;
import app.nadel.plots.PlotImport;
import app.nadel.plots.PlotImportAction;
import app.nadel.plots.PlotImportPlan;
import app.nadel.plots.PlotImportRow;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Planning the ownership rows of an import against the cooperative's plots and recorded owners. The demo data gives the
 * cases: plot 7 is owned by members 3 and 7 at 1/2 each, plot 9 by member 11 alone (after an archived member 20), plots
 * 25-29 and the road R-1 have no owner; members 32-35 are free.
 */
class OwnershipImportPlanTests extends IntegrationTest {

    private static final UUID BONDARENKO = member(3);
    private static final UUID MOROZ = member(7);
    private static final UUID LYTVYNENKO = member(11);
    private static final UUID SPARE_1 = member(32);
    private static final UUID SPARE_2 = member(33);
    private static final UUID SPARE_3 = member(34);
    private static final LocalDate PAST = LocalDate.of(2022, 5, 17);

    @Autowired
    PlotImport plotImport;

    @Autowired
    PlatformTransactionManager transactions;

    private final List<UUID> createdPlots = new ArrayList<>();
    private final String prefix = "oi" + UUID.randomUUID().toString().substring(0, 6);
    private int counter;

    @AfterEach
    void removeFixtures() {
        for (UUID plot : createdPlots) {
            ownerJdbc().update("delete from ownership where plot_id = ?", plot);
            ownerJdbc().update("delete from plot where id = ?", plot);
        }
        createdPlots.clear();
    }

    // ---- fixtures

    private static UUID member(int number) {
        return UUID.fromString("aaaaaaaa-0000-0000-0000-%012d".formatted(number));
    }

    private String number() {
        return prefix + "-" + (++counter);
    }

    private UUID demoPlot(String number) {
        return ownerJdbc().queryForObject("select id from plot where tenant_id = ? and number = ? and archived_at is null",
                UUID.class, DEMO_TENANT, number);
    }

    private UUID plot(String number, String kind, boolean archived) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2, archived_at) "
                + "values (?, ?, ?, ?, 600, " + (archived ? "now()" : "null") + ")", id, DEMO_TENANT, number, kind);
        createdPlots.add(id);
        return id;
    }

    /** A stored plot with one past owner (1/1 from 2015-01-01 until {@code until}) and no current owner. */
    private UUID plotWithPastOwner(String number, LocalDate until) {
        UUID id = plot(number, "PLOT", false);
        ownerJdbc().update("insert into ownership (tenant_id, plot_id, member_id, share_numerator, share_denominator, "
                + "valid_from, valid_to) values (?, ?, ?, 1, 1, date '2015-01-01', ?)", DEMO_TENANT, id, SPARE_3, until);
        return id;
    }

    private static OwnershipImportRow owner(int row, String plot, UUID member, String share, LocalDate from) {
        return new OwnershipImportRow(row, plot, member, share, from);
    }

    private static PlotImportRow plotRow(int row, String number, String kind) {
        return new PlotImportRow(row, number, null, kind, kind != null && kind.equalsIgnoreCase("COMMON") ? null
                : new BigDecimal("600"), null, null, null);
    }

    /** Plans the plot rows, then the ownership rows, the way the importer does; returns the ownership items. */
    private List<OwnershipImportItem> plan(List<PlotImportRow> plotRows, OwnershipImportRow... rows) {
        return TenantContext.runAs(DEMO_TENANT, () -> new TransactionTemplate(transactions).execute(status -> {
            PlotImportPlan plotPlan = plotImport.plan(DEMO_TENANT, UUID.randomUUID(), plotRows);
            return plotImport.planOwnership(DEMO_TENANT, plotRows, plotPlan, List.of(rows)).items();
        }));
    }

    private List<OwnershipImportItem> plan(OwnershipImportRow... rows) {
        return plan(List.of(), rows);
    }

    private static List<String> problems(OwnershipImportItem item) {
        return item.problems().stream().map(p -> p.field() + ":" + p.code()).toList();
    }

    // ---- first owners

    @Test
    void anUnownedDemoPlotGetsItsFirstOwners() {
        List<OwnershipImportItem> items = plan(owner(2, "29", SPARE_1, "1/1", PAST));

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.row()).isEqualTo(2);
            assertThat(item.outcome()).isEqualTo(OwnershipImportOutcome.FIRST_OWNERS);
            assertThat(item.plotId()).isEqualTo(demoPlot("29"));
            assertThat(item.problems()).isEmpty();
        });
    }

    @Test
    void aBareOneIsTheWholePlot() {
        assertThat(plan(owner(2, "28", SPARE_1, "1", PAST))).singleElement()
                .satisfies(item -> assertThat(item.outcome()).isEqualTo(OwnershipImportOutcome.FIRST_OWNERS));
    }

    @Test
    void aNewPlotOfTheFileGetsItsFirstOwnersAndTheIdItWillHave() {
        String number = number();
        List<PlotImportRow> plotRows = List.of(plotRow(2, number, "PLOT"));

        List<OwnershipImportItem> items = plan(plotRows, owner(2, number.toUpperCase(), SPARE_1, "1/1", PAST));

        assertThat(items.getFirst().outcome()).isEqualTo(OwnershipImportOutcome.FIRST_OWNERS);
        assertThat(items.getFirst().plotId()).isNotNull();
        assertThat(ownerJdbc().queryForObject("select count(*) from plot where id = ?", Integer.class,
                items.getFirst().plotId())).isZero(); // the plot does not exist yet
    }

    @Test
    void coOwnersWithHalvesAndWithTwoThirdsAndOneThirdAreAccepted() {
        List<OwnershipImportItem> items = plan(
                owner(2, "26", SPARE_1, "1/2", PAST), owner(3, "26", SPARE_2, "2/4", PAST),
                owner(4, "27", SPARE_1, "2/3", PAST), owner(5, "27", SPARE_2, "1/3", PAST));

        assertThat(items).extracting(OwnershipImportItem::outcome)
                .containsOnly(OwnershipImportOutcome.FIRST_OWNERS);
        assertThat(items).extracting(OwnershipImportItem::plotId)
                .containsExactly(demoPlot("26"), demoPlot("26"), demoPlot("27"), demoPlot("27"));
    }

    @Test
    void aPlotWithOnlyPastOwnersGetsNewOnesFromALaterDateAndNotFromAnEarlierOne() {
        String later = number();
        String earlier = number();
        plotWithPastOwner(later, LocalDate.of(2020, 12, 31));
        plotWithPastOwner(earlier, LocalDate.of(2020, 12, 31));

        List<OwnershipImportItem> items = plan(
                owner(2, later, SPARE_1, "1/1", LocalDate.of(2021, 1, 1)),
                owner(3, earlier, SPARE_1, "1/1", LocalDate.of(2020, 12, 31)));

        assertThat(items.get(0).outcome()).isEqualTo(OwnershipImportOutcome.FIRST_OWNERS);
        assertThat(items.get(1).outcome()).isNull();
        assertThat(problems(items.get(1))).containsExactly("validFrom:OWNERSHIP_DATE_INVALID");
    }

    // ---- skip and conflict

    @Test
    void theExactCurrentOwnersOfDemoPlot7AreSkippedWhateverTheDates() {
        List<OwnershipImportItem> items = plan(
                owner(2, "7", MOROZ, "1/2", LocalDate.of(2024, 1, 1)),
                owner(3, "7", BONDARENKO, "2/4", LocalDate.of(2024, 1, 1)));

        assertThat(items).extracting(OwnershipImportItem::outcome).containsOnly(OwnershipImportOutcome.SKIP);
        assertThat(items).extracting(OwnershipImportItem::plotId).containsOnly(demoPlot("7"));
        assertThat(items).allSatisfy(item -> assertThat(item.problems()).isEmpty());
    }

    @Test
    void aDifferentOwnerOfDemoPlot9IsAConflictOnTheFirstRowWithThePlotInTheParams() {
        List<OwnershipImportItem> items = plan(owner(4, " 9 ", SPARE_1, "1/1", PAST));

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.outcome()).isNull();
            assertThat(item.plotId()).isNull();
            assertThat(item.problems()).containsExactly(new OwnershipImportProblem("plotNumber",
                    "OWNERSHIP_CONFLICT", Map.of("plot", "9")));
        });
    }

    @Test
    void theSameMembersWithOtherSharesOrOnlyOneOfThemConflict() {
        List<OwnershipImportItem> otherShares = plan(
                owner(2, "7", BONDARENKO, "2/3", PAST), owner(3, "7", MOROZ, "1/3", PAST));
        List<OwnershipImportItem> oneOfThem = plan(owner(2, "7", BONDARENKO, "1/1", PAST));
        List<OwnershipImportItem> moreOfThem = plan(
                owner(2, "7", BONDARENKO, "1/3", PAST), owner(3, "7", MOROZ, "1/3", PAST),
                owner(4, "7", SPARE_1, "1/3", PAST));

        for (List<OwnershipImportItem> items : List.of(otherShares, oneOfThem, moreOfThem)) {
            assertThat(problems(items.getFirst())).containsExactly("plotNumber:OWNERSHIP_CONFLICT");
            assertThat(items).extracting(OwnershipImportItem::outcome).containsOnlyNulls();
            assertThat(items.stream().skip(1)).allSatisfy(item -> assertThat(item.problems()).isEmpty());
        }
    }

    // ---- the rows of a plot

    @Test
    void sharesThatDoNotAddUpToOneAreReportedOnTheShareOfTheFirstRow() {
        List<OwnershipImportItem> items = plan(
                owner(2, "28", SPARE_1, "1/2", PAST), owner(3, "28", SPARE_2, "1/3", PAST));
        List<OwnershipImportItem> tooMuch = plan(
                owner(2, "29", SPARE_1, "1/1", PAST), owner(3, "29", SPARE_2, "1/2", PAST));

        assertThat(problems(items.get(0))).containsExactly("share:SHARES_NOT_ONE");
        assertThat(items.get(1).problems()).isEmpty();
        assertThat(items).extracting(OwnershipImportItem::outcome).containsOnlyNulls();
        assertThat(problems(tooMuch.get(0))).containsExactly("share:SHARES_NOT_ONE");
    }

    @Test
    void rowsOfOnePlotWithDifferentDatesAreAMismatchOnTheRowsThatDifferFromTheFirst() {
        List<OwnershipImportItem> items = plan(
                owner(2, "26", SPARE_1, "1/3", PAST), owner(3, "26", SPARE_2, "1/3", PAST.plusDays(1)),
                owner(4, "26", SPARE_3, "1/3", PAST));

        assertThat(problems(items.get(0))).isEmpty();
        assertThat(problems(items.get(1))).containsExactly("validFrom:OWNERSHIP_DATE_MISMATCH");
        assertThat(problems(items.get(2))).isEmpty();
        assertThat(items).extracting(OwnershipImportItem::outcome).containsOnlyNulls();
    }

    @Test
    void aDateAfterTodayIsInvalidAndNoShareCheckFollows() {
        List<OwnershipImportItem> items = plan(
                owner(2, "26", SPARE_1, "1/2", LocalDate.now().plusDays(3)));

        assertThat(problems(items.getFirst())).containsExactly("validFrom:OWNERSHIP_DATE_INVALID");
    }

    @Test
    void theSameMemberTwiceOnOnePlotIsADuplicateOnTheLaterRow() {
        List<OwnershipImportItem> items = plan(
                owner(2, "26", SPARE_1, "1/2", PAST), owner(3, "26", SPARE_1, "1/2", PAST));

        assertThat(problems(items.get(0))).isEmpty();
        assertThat(problems(items.get(1))).containsExactly("memberId:DUPLICATE_OWNER");
        assertThat(items).extracting(OwnershipImportItem::outcome).containsOnlyNulls();
    }

    @Test
    void eachRowIsCheckedForItsOwnCellsBeforeAnythingAboutThePlot() {
        List<OwnershipImportItem> items = plan(
                new OwnershipImportRow(2, null, SPARE_1, "1/1", PAST),
                new OwnershipImportRow(3, "26", SPARE_1, null, PAST),
                new OwnershipImportRow(4, "26", SPARE_2, "0.5", null),
                new OwnershipImportRow(5, "26", SPARE_3, "3/2", PAST),
                new OwnershipImportRow(6, "27", SPARE_1, "  ", PAST));

        assertThat(problems(items.get(0))).containsExactly("plotNumber:REQUIRED");
        assertThat(problems(items.get(1))).containsExactly("share:REQUIRED");
        assertThat(problems(items.get(2))).containsExactly("share:SHARE_INVALID", "validFrom:REQUIRED");
        assertThat(problems(items.get(3))).containsExactly("share:SHARE_INVALID");
        assertThat(problems(items.get(4))).containsExactly("share:REQUIRED");
    }

    // ---- plots

    @Test
    void commonLandAndUnknownPlotsAreReportedOnEveryRowOfThePlot() {
        List<OwnershipImportItem> items = plan(
                owner(2, "R-1", SPARE_1, "1/2", PAST), owner(3, "r-1", SPARE_2, "1/2", PAST),
                owner(4, "no-such-plot", SPARE_1, "1/1", PAST));

        assertThat(problems(items.get(0))).containsExactly("plotNumber:PLOT_COMMON_LAND");
        assertThat(problems(items.get(1))).containsExactly("plotNumber:PLOT_COMMON_LAND");
        assertThat(problems(items.get(2))).containsExactly("plotNumber:UNKNOWN_PLOT");
        assertThat(items).extracting(OwnershipImportItem::outcome).containsOnlyNulls();
    }

    @Test
    void aPlotOfTheFileThatIsCommonLandHasNoOwnersWhetherNewOrChangedFromAPlot() {
        String fresh = number();
        String stored = number();
        plot(stored, "PLOT", false);
        List<PlotImportRow> plotRows = List.of(plotRow(2, fresh, "common"), plotRow(3, stored, "COMMON"),
                plotRow(4, "R-1", null));

        List<OwnershipImportItem> items = plan(plotRows,
                owner(2, fresh, SPARE_1, "1/1", PAST), owner(3, stored, SPARE_1, "1/1", PAST),
                owner(4, "R-1", SPARE_1, "1/1", PAST));

        assertThat(items).allSatisfy(item -> assertThat(problems(item)).containsExactly("plotNumber:PLOT_COMMON_LAND"));
    }

    @Test
    void aStoredCommonPlotTheFileTurnsIntoAPlotCanHaveOwners() {
        List<PlotImportRow> plotRows = List.of(plotRow(2, "R-1", "PLOT"));

        List<OwnershipImportItem> items = plan(plotRows, owner(2, "R-1", SPARE_1, "1/1", PAST));

        assertThat(items.getFirst().outcome()).isEqualTo(OwnershipImportOutcome.FIRST_OWNERS);
        assertThat(items.getFirst().plotId()).isEqualTo(demoPlot("R-1"));
    }

    @Test
    void aPlotRowWithProblemsSuppressesEveryCheckOfItsOwnershipRows() {
        String number = number();
        // the kind is not PLOT or COMMON: the plot row is invalid; its ownership rows are wrong in every way and silent
        List<PlotImportRow> plotRows = List.of(plotRow(2, number, "garden"));

        List<OwnershipImportItem> items = plan(plotRows,
                owner(2, number, SPARE_1, "1/2", PAST), owner(3, number, SPARE_1, "banana", PAST.plusDays(1)));

        assertThat(items).allSatisfy(item -> {
            assertThat(item.problems()).isEmpty();
            assertThat(item.outcome()).isNull();
            assertThat(item.plotId()).isNull();
        });
    }

    @Test
    void anArchivedPlotIsNeverMatchedButTheFileMayCreateANewPlotWithItsNumber() {
        String archived = number();
        String recreated = number();
        plot(archived, "PLOT", true);
        plot(recreated, "PLOT", true);

        List<OwnershipImportItem> unknown = plan(owner(2, archived, SPARE_1, "1/1", PAST));
        List<OwnershipImportItem> inTheFile = plan(List.of(plotRow(2, recreated, "PLOT")),
                owner(2, recreated, SPARE_1, "1/1", PAST));

        assertThat(problems(unknown.getFirst())).containsExactly("plotNumber:UNKNOWN_PLOT");
        assertThat(inTheFile.getFirst().outcome()).isEqualTo(OwnershipImportOutcome.FIRST_OWNERS);
    }

    @Test
    void anExistingPlotInTheFileKeepsItsRecordedOwnersAsTheComparison() {
        // demo plot 7 appears in the Plots sheet unchanged: its ownership rows are compared with the stored owners
        List<PlotImportRow> plotRows = List.of(plotRow(2, "7", null));

        List<OwnershipImportItem> items = plan(plotRows,
                owner(2, "7", BONDARENKO, "1/2", PAST), owner(3, "7", MOROZ, "1/2", PAST));
        PlotImportPlan plotPlan = TenantContext.runAs(DEMO_TENANT, () -> new TransactionTemplate(transactions)
                .execute(status -> plotImport.plan(DEMO_TENANT, UUID.randomUUID(), plotRows)));

        assertThat(plotPlan.items().getFirst().action()).isIn(PlotImportAction.UNCHANGED, PlotImportAction.UPDATE);
        assertThat(items).extracting(OwnershipImportItem::outcome).containsOnly(OwnershipImportOutcome.SKIP);
    }

    // ---- members the caller could not resolve

    @Test
    void aRowWithoutAMemberIsLeftToTheCallerAndItsPlotIsNotCheckedAgainstTheOthers() {
        // 1/2 + (unknown member): the sum cannot be judged, so no SHARES_NOT_ONE and no outcome
        List<OwnershipImportItem> items = plan(
                owner(2, "28", SPARE_1, "1/2", PAST), new OwnershipImportRow(3, "28", null, "1/2", PAST));

        assertThat(items).allSatisfy(item -> {
            assertThat(item.problems()).isEmpty();
            assertThat(item.outcome()).isNull();
        });
    }
}
