package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.IntegrationTest;
import app.nadel.plots.PlotImport;
import app.nadel.plots.PlotImportAction;
import app.nadel.plots.PlotImportItem;
import app.nadel.plots.PlotImportRow;
import app.nadel.problem.FieldProblem;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Planning plot rows of an import against the plots of the cooperative: matching, overlay, duplicates, uniqueness. */
class PlotImportPlanTests extends IntegrationTest {

    private static final UUID DEMO_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000032");

    @Autowired
    PlotImport plotImport;

    @Autowired
    PlatformTransactionManager transactions;

    private final List<UUID> createdPlots = new ArrayList<>();
    private final String prefix = "pi" + UUID.randomUUID().toString().substring(0, 6);
    private int counter;

    @AfterEach
    void removeFixtures() {
        JdbcTemplate owner = ownerJdbc();
        for (UUID plot : createdPlots) {
            owner.update("delete from ownership where plot_id = ?", plot);
            owner.update("delete from plot where id = ?", plot);
        }
        createdPlots.clear();
    }

    // ---- fixtures

    private String number() {
        return prefix + "-" + (++counter);
    }

    private static String cadastral() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "%010d:%02d:%03d:%04d".formatted(random.nextLong(1_000_000_000L, 9_999_999_999L), random.nextInt(100),
                random.nextInt(1000), random.nextInt(10000));
    }

    private UUID plot(String number, String street, String kind, String area, String cadastral, boolean building,
            String notes, boolean archived) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, street, kind, area_m2, cadastral_number, "
                + "has_building, notes, archived_at) values (?, ?, ?, ?, ?, ?::numeric, ?, ?, ?, "
                + (archived ? "now()" : "null") + ")",
                id, DEMO_TENANT, number, street, kind, area, cadastral, building, notes);
        createdPlots.add(id);
        return id;
    }

    private UUID simplePlot(String number) {
        return plot(number, "Garden", "PLOT", "600", null, false, "note", false);
    }

    private void giveHistory(UUID plot) {
        ownerJdbc().update("insert into ownership (tenant_id, plot_id, member_id, share_numerator, share_denominator, "
                + "valid_from, valid_to) values (?, ?, ?, 1, 1, date '2020-01-01', date '2020-12-31')",
                DEMO_TENANT, plot, DEMO_MEMBER);
    }

    private static PlotImportRow row(int row, String number, String street, String kind, String area,
            String cadastral, Boolean building, String notes) {
        return new PlotImportRow(row, number, street, kind, area == null ? null : new BigDecimal(area), cadastral,
                building, notes);
    }

    /** A row that only names a plot and gives its kind and area. */
    private static PlotImportRow row(int row, String number) {
        return row(row, number, null, "PLOT", "600", null, null, null);
    }

    private List<PlotImportItem> plan(UUID importId, PlotImportRow... rows) {
        return TenantContext.runAs(DEMO_TENANT, () ->
                new TransactionTemplate(transactions).execute(status ->
                        plotImport.plan(DEMO_TENANT, importId, List.of(rows)).items()));
    }

    private List<PlotImportItem> plan(PlotImportRow... rows) {
        return plan(UUID.randomUUID(), rows);
    }

    private static List<String> problems(PlotImportItem item) {
        return item.problems().stream().map(p -> p.field() + ":" + p.code()).toList();
    }

    // ---- new, update, unchanged

    @Test
    void aNumberNobodyHasIsANewPlotWithAnIdFromTheImportAndTheNumber() {
        UUID importId = UUID.randomUUID();
        String number = number();

        PlotImportItem item = plan(importId, row(7, number, "Garden", "PLOT", "600", null, true, null)).getFirst();
        PlotImportItem again = plan(importId, row(9, number.toUpperCase(), "Other", "PLOT", "100", null, null, null))
                .getFirst();

        assertThat(item.row()).isEqualTo(7);
        assertThat(item.action()).isEqualTo(PlotImportAction.NEW);
        assertThat(item.problems()).isEmpty();
        assertThat(item.changedFields()).isEmpty();
        assertThat(item.plotId()).isEqualTo(
                UUID.nameUUIDFromBytes((importId + "/plot/" + number).getBytes(StandardCharsets.UTF_8)));
        // the same import and number (whatever the letter case) always give the same id; another import another id
        assertThat(again.plotId()).isEqualTo(item.plotId());
        assertThat(plan(UUID.randomUUID(), row(7, number)).getFirst().plotId()).isNotEqualTo(item.plotId());
    }

    @Test
    void anExistingPlotWithDifferentValuesIsAnUpdateListingTheChangedFields() {
        String number = number();
        UUID existing = plot(number, "Garden", "PLOT", "600", null, false, "note", false);

        PlotImportItem item = plan(row(2, number, "Orchard", "PLOT", "650.5", null, true, "new note")).getFirst();

        assertThat(item.action()).isEqualTo(PlotImportAction.UPDATE);
        assertThat(item.plotId()).isEqualTo(existing);
        assertThat(item.changedFields()).containsExactly("street", "areaM2", "hasBuilding", "notes");
        assertThat(item.problems()).isEmpty();
    }

    @Test
    void aPlotThatIsAlreadyAsInTheFileIsUnchangedAndMatchingIgnoresCase() {
        String number = number();
        String cadastral = cadastral();
        UUID existing = plot(number, "Garden", "PLOT", "600", cadastral, true, "note", false);

        PlotImportItem item = plan(row(2, number.toUpperCase(), "Garden", "plot", "600.00", cadastral, true, "note"))
                .getFirst();

        assertThat(item.action()).isEqualTo(PlotImportAction.UNCHANGED);
        assertThat(item.plotId()).isEqualTo(existing);
        assertThat(item.changedFields()).isEmpty();
    }

    @Test
    void blankCellsLeaveTheValuesOfAnExistingPlotAlone() {
        String number = number();
        String cadastral = cadastral();
        plot(number, "Garden", "PLOT", "600", cadastral, true, "note", false);

        // no street, kind, area, cadastral number, building flag or notes: nothing to change
        PlotImportItem blank = plan(row(2, number, null, null, null, null, null, null)).getFirst();
        // only the street is given
        PlotImportItem street = plan(row(3, number, "Orchard", null, null, null, null, null)).getFirst();

        assertThat(blank.action()).isEqualTo(PlotImportAction.UNCHANGED);
        assertThat(street.action()).isEqualTo(PlotImportAction.UPDATE);
        assertThat(street.changedFields()).containsExactly("street");
    }

    @Test
    void aMatchedPlotKeepsTheSpellingOfItsNumber() {
        String number = prefix + "-X";
        plot(number, "Garden", "PLOT", "600", null, false, null, false);

        PlotImportItem item = plan(row(2, number.toLowerCase(), "Garden", "PLOT", "600", null, null, "note"))
                .getFirst();

        assertThat(item.changedFields()).containsExactly("notes");
    }

    @Test
    void anArchivedPlotDoesNotMatchItsNumber() {
        String number = number();
        UUID archived = plot(number, "Garden", "PLOT", "600", null, false, null, true);

        PlotImportItem item = plan(row(2, number)).getFirst();

        assertThat(item.action()).isEqualTo(PlotImportAction.NEW);
        assertThat(item.plotId()).isNotEqualTo(archived);
    }

    @Test
    void aPlotOfAnotherCooperativeDoesNotMatch() {
        String number = number();
        UUID other = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 100)",
                other, OTHER_TENANT, number);
        createdPlots.add(other);

        assertThat(plan(row(2, number)).getFirst().action()).isEqualTo(PlotImportAction.NEW);
    }

    @Test
    void manyRowsAreLookedUpInChunks() {
        List<PlotImportRow> rows = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            String number = prefix + "-bulk-" + i;
            numbers.add(number);
            rows.add(row(i + 2, number));
        }
        // plots near the start, the middle and the end of the list exist
        UUID first = simplePlot(numbers.getFirst());
        UUID middle = plot(numbers.get(700), "Garden", "PLOT", "600", null, false, null, false);
        UUID last = plot(numbers.getLast(), "Garden", "PLOT", "600", null, false, null, false);

        List<PlotImportItem> items = plan(rows.toArray(PlotImportRow[]::new));

        assertThat(items).hasSize(1200);
        assertThat(items.stream().filter(i -> i.action() == PlotImportAction.NEW)).hasSize(1197);
        assertThat(items.getFirst().plotId()).isEqualTo(first);
        assertThat(items.get(700).plotId()).isEqualTo(middle);
        assertThat(items.getLast().plotId()).isEqualTo(last);
    }

    @Test
    void noRowsGiveAnEmptyPlan() {
        assertThat(plan()).isEmpty();
    }

    // ---- required values on new plots

    @Test
    void aNewPlotNeedsANumberAndAKind() {
        PlotImportItem noNumber = plan(row(2, null, null, "PLOT", "600", null, null, null)).getFirst();
        PlotImportItem noKind = plan(row(3, number(), null, null, "600", null, null, null)).getFirst();

        assertThat(noNumber.action()).isNull();
        assertThat(noNumber.plotId()).isNull();
        assertThat(problems(noNumber)).containsExactly("number:REQUIRED");
        assertThat(problems(noKind)).containsExactly("kind:REQUIRED");
    }

    @Test
    void anUnknownKindIsInvalid() {
        PlotImportItem item = plan(row(2, number(), null, "garden", "600", null, null, null)).getFirst();

        assertThat(problems(item)).containsExactly("kind:INVALID");
    }

    @Test
    void aNewPlotWithoutAnAreaGetsTheBothOrNoneProblemExceptCommonLand() {
        PlotImportItem plot = plan(row(2, number(), null, "PLOT", null, null, null, null)).getFirst();
        PlotImportItem common = plan(row(3, number(), null, "COMMON", null, null, null, null)).getFirst();
        PlotImportItem noKindNoArea = plan(row(4, number(), null, null, null, null, null, null)).getFirst();

        assertThat(problems(plot)).containsExactly("areaM2:AREA_BOTH_OR_NONE");
        assertThat(common.action()).isEqualTo(PlotImportAction.NEW);
        assertThat(problems(noKindNoArea)).containsExactlyInAnyOrder("kind:REQUIRED", "areaM2:AREA_BOTH_OR_NONE");
    }

    @Test
    void theRulesOfTheFormApplyToEveryField() {
        PlotImportItem item = plan(row(2, "n".repeat(21), "s".repeat(101), "PLOT", "600.123", "12345", null,
                "x".repeat(2001))).getFirst();

        assertThat(problems(item)).containsExactlyInAnyOrder("number:TOO_LONG", "street:TOO_LONG",
                "areaM2:AREA_INVALID", "cadastralNumber:CADASTRAL_INVALID", "notes:TOO_LONG");
        assertThat(plan(row(3, number(), null, "PLOT", "0", null, null, null)).getFirst().problems())
                .extracting(FieldProblem::code).containsExactly("AREA_INVALID");
    }

    // ---- duplicates in the file

    @Test
    void aNumberRepeatedInTheFileIsAProblemOnTheLaterRowsIgnoringCase() {
        String number = number();

        List<PlotImportItem> items = plan(row(2, number), row(3, number.toUpperCase()), row(4, " " + number + " "),
                row(5, number()));

        assertThat(items.get(0).action()).isEqualTo(PlotImportAction.NEW);
        assertThat(problems(items.get(1))).containsExactly("number:DUPLICATE_IN_FILE");
        assertThat(problems(items.get(2))).containsExactly("number:DUPLICATE_IN_FILE");
        assertThat(items.get(3).action()).isEqualTo(PlotImportAction.NEW);
    }

    // ---- cadastral numbers

    @Test
    void aCadastralNumberOfAnotherActivePlotIsTaken() {
        String cadastral = cadastral();
        plot(number(), "Garden", "PLOT", "600", cadastral, false, null, false);
        String number = number();
        simplePlot(number);

        PlotImportItem newPlot = plan(row(2, number(), null, "PLOT", "600", cadastral, null, null)).getFirst();
        PlotImportItem update = plan(row(3, number, null, null, null, cadastral, null, null)).getFirst();

        assertThat(problems(newPlot)).containsExactly("cadastralNumber:CADASTRAL_NUMBER_TAKEN");
        assertThat(problems(update)).containsExactly("cadastralNumber:CADASTRAL_NUMBER_TAKEN");
    }

    @Test
    void aCadastralNumberOfAnArchivedPlotIsTakenToo() {
        String cadastral = cadastral();
        plot(number(), "Garden", "PLOT", "600", cadastral, false, null, true);

        PlotImportItem item = plan(row(2, number(), null, "PLOT", "600", cadastral, null, null)).getFirst();

        assertThat(problems(item)).containsExactly("cadastralNumber:CADASTRAL_NUMBER_TAKEN");
    }

    @Test
    void aCadastralNumberClaimedByAnEarlierRowIsTaken() {
        String cadastral = cadastral();

        List<PlotImportItem> items = plan(
                row(2, number(), null, "PLOT", "600", cadastral, null, null),
                row(3, number(), null, "PLOT", "600", cadastral, null, null));

        assertThat(items.get(0).action()).isEqualTo(PlotImportAction.NEW);
        assertThat(problems(items.get(1))).containsExactly("cadastralNumber:CADASTRAL_NUMBER_TAKEN");
    }

    @Test
    void thePlotsOwnCadastralNumberIsNotTaken() {
        String number = number();
        String cadastral = cadastral();
        plot(number, "Garden", "PLOT", "600", cadastral, false, null, false);

        PlotImportItem item = plan(row(2, number, null, null, null, cadastral, null, null)).getFirst();

        assertThat(item.action()).isEqualTo(PlotImportAction.UNCHANGED);
    }

    // ---- kind change

    @Test
    void aPlotWithOwnershipHistoryCannotBecomeCommonLand() {
        String withHistory = number();
        giveHistory(simplePlot(withHistory));
        String withoutHistory = number();
        simplePlot(withoutHistory);

        PlotImportItem refused = plan(row(2, withHistory, null, "COMMON", null, null, null, null)).getFirst();
        PlotImportItem allowed = plan(row(3, withoutHistory, null, "COMMON", null, null, null, null)).getFirst();

        assertThat(problems(refused)).containsExactly("kind:PLOT_HAS_OWNERSHIP_HISTORY");
        assertThat(allowed.action()).isEqualTo(PlotImportAction.UPDATE);
        assertThat(allowed.changedFields()).containsExactly("kind");
    }

    @Test
    void aPlotWithHistoryThatStaysAPlotIsFine() {
        String number = number();
        giveHistory(simplePlot(number));

        assertThat(plan(row(2, number, null, "PLOT", null, null, null, null)).getFirst().action())
                .isEqualTo(PlotImportAction.UNCHANGED);
    }
}
