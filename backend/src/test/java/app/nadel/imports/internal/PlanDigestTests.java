package app.nadel.imports.internal;

import static app.nadel.imports.internal.ImportWorkbooks.MEMBER_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.OWNERSHIP_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.PLOT_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.member;
import static app.nadel.imports.internal.ImportWorkbooks.owner;
import static app.nadel.imports.internal.ImportWorkbooks.plot;
import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.ledger.OpeningBalanceImport;
import app.nadel.members.MemberImportAction;
import app.nadel.members.MemberImportItem;
import app.nadel.members.MemberImportPlan;
import app.nadel.members.MemberImportRow;
import app.nadel.meters.MeterImportAction;
import app.nadel.meters.MeterImportItem;
import app.nadel.meters.MeterImportPlan;
import app.nadel.meters.MeterImportRow;
import app.nadel.meters.WaterConnectionImport;
import app.nadel.plots.OwnershipImportItem;
import app.nadel.plots.OwnershipImportOutcome;
import app.nadel.plots.OwnershipImportPlan;
import app.nadel.plots.PlotImportAction;
import app.nadel.plots.PlotImportItem;
import app.nadel.plots.PlotImportPlan;
import app.nadel.plots.PlotImportRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The digest that makes a preview stale when anything it was based on changes: the same file gives the same digest, a
 * changed cell or changed data of the cooperative gives another one. Rows are digested as a set, so their order does not
 * matter: a commit replays the stored rows, and the digest must not depend on how they come back.
 */
class PlanDigestTests extends ImportsIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate SINCE = LocalDate.of(2020, 3, 1);

    private final String tag = UUID.randomUUID().toString().substring(0, 8);
    private final List<UUID> createdPlots = new ArrayList<>();

    @AfterEach
    void removeCreatedPlots() {
        createdPlots.forEach(id -> ownerJdbc().update("delete from plot where id = ?", id));
    }

    // ---- through the preview

    /** Plots a and b, members M-1 and M-2, a owned 1/2 + 1/2 and b by M-2 alone; each part can be changed. */
    private record Spec(String street, String share, LocalDate since, String phone, boolean reversed) {

        static final Spec BASE = new Spec("Garden", "1/2", SINCE, "0671110201", false);

        Spec street(String value) {
            return new Spec(value, share, since, phone, reversed);
        }

        Spec share(String value) {
            return new Spec(street, value, since, phone, reversed);
        }

        Spec since(LocalDate value) {
            return new Spec(street, share, value, phone, reversed);
        }

        Spec phone(String value) {
            return new Spec(street, share, since, value, reversed);
        }

        Spec inReverseOrder() {
            return new Spec(street, share, since, phone, true);
        }
    }

    private byte[] file(Spec spec) {
        List<List<?>> plots = new ArrayList<>(List.of(
                plot("a" + tag, spec.street(), "PLOT", 600, null, null, null, null),
                plot("b" + tag, "Orchard", "PLOT", 1000, null, null, null, null)));
        List<List<?>> members = new ArrayList<>(List.of(
                member("M-1", "One" + tag, "Person", null, spec.phone(), "one." + tag + "@import.example.com", null,
                        null, null, null),
                member("M-2", "Two" + tag, "Person", "two." + tag + "@import.example.com")));
        String second = spec.share().equals("1/2") ? "1/2" : "1/3";
        List<List<?>> ownership = new ArrayList<>(List.of(
                owner("a" + tag, "M-1", spec.share(), spec.since()),
                owner("a" + tag, "M-2", second, spec.since()),
                owner("b" + tag, "M-2", "1", SINCE)));
        if (spec.reversed()) {
            Collections.reverse(plots);
            Collections.reverse(members);
            Collections.reverse(ownership);
        }
        return ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER, plots.toArray(List<?>[]::new))
                .sheet("Members", MEMBER_HEADER, members.toArray(List<?>[]::new))
                .sheet("Ownership", OWNERSHIP_HEADER, ownership.toArray(List<?>[]::new))
                .build();
    }

    private String digestOf(byte[] file) throws Exception {
        String id = JSON.readTree(upload(file).andReturn().getResponse().getContentAsString()).get("id").asString();
        String digest = openedPlan(id).digest();
        assertThat(digest).as("a preview without problems stores its digest").matches("[0-9a-f]{64}");
        return digest;
    }

    @Test
    void theSameFileGivesTheSameDigestInTwoPreviews() throws Exception {
        assertThat(digestOf(file(Spec.BASE))).isEqualTo(digestOf(file(Spec.BASE)));
    }

    @Test
    void aChangedCellChangesTheDigest() throws Exception {
        String base = digestOf(file(Spec.BASE));

        assertThat(List.of(
                digestOf(file(Spec.BASE.street("Lake"))),
                digestOf(file(Spec.BASE.share("2/3"))),
                digestOf(file(Spec.BASE.since(SINCE.plusDays(1)))),
                digestOf(file(Spec.BASE.phone("0671110202")))))
                .doesNotContain(base).doesNotHaveDuplicates();
    }

    @Test
    void theOrderOfTheRowsDoesNotMatter() throws Exception {
        assertThat(digestOf(file(Spec.BASE.inReverseOrder()))).isEqualTo(digestOf(file(Spec.BASE)));
    }

    @Test
    void aPlotAddedToTheCooperativeAfterThePreviewChangesTheDigest() throws Exception {
        String before = digestOf(file(Spec.BASE));
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 600)",
                id, DEMO_TENANT, "a" + tag);
        createdPlots.add(id);

        // the plot of the file now exists: it is an update, and a re-plan at commit time would differ
        assertThat(digestOf(file(Spec.BASE))).isNotEqualTo(before);
    }

    // ---- the canonical text

    private static PlotImportRow plotRow(String number, String area) {
        return new PlotImportRow(2, number, "Garden", "PLOT", new BigDecimal(area), null, null, null);
    }

    private static MemberImportRow memberRow(String key, String lastName) {
        return new MemberImportRow(2, key, lastName, "First", null, List.of(), "x@example.com", null, null, null, null);
    }

    private static String digest(UUID plotId, UUID memberId, PlotImportRow plot, MemberImportRow member,
            OwnershipSheet.ParsedRow ownership, UUID ownedPlot) {
        return PlanDigest.of(List.of(plot),
                new PlotImportPlan(List.of(new PlotImportItem(2, PlotImportAction.NEW, plotId, List.of(), List.of()))),
                List.of(member),
                new MemberImportPlan(List.of(new MemberImportItem(2, MemberImportAction.NEW, memberId, List.of(),
                        List.of()))),
                List.of(ownership),
                new OwnershipImportPlan(List.of(new OwnershipImportItem(2, OwnershipImportOutcome.FIRST_OWNERS,
                        ownedPlot, List.of()))),
                List.of(), new MeterImportPlan(List.of()), List.of(), new WaterConnectionImport.Plan(List.of()),
                List.of(), new OpeningBalanceImport.Plan(List.of()));
    }

    /** One new plot and one meter row on it (or on an existing plot), with the given action and meter id. */
    private static String meterDigest(UUID newPlot, MeterImportRow meter, MeterImportAction action, UUID meterId,
            UUID meterPlot) {
        return PlanDigest.of(List.of(plotRow("P-1", "600")),
                new PlotImportPlan(List.of(new PlotImportItem(2, PlotImportAction.NEW, newPlot, List.of(), List.of()))),
                List.of(), new MemberImportPlan(List.of()), List.of(), new OwnershipImportPlan(List.of()),
                List.of(meter), new MeterImportPlan(List.of(new MeterImportItem(meter.row(), action, meterId,
                        meterPlot, List.of()))), List.of(), new WaterConnectionImport.Plan(List.of()), List.of(),
                new OpeningBalanceImport.Plan(List.of()));
    }

    private static MeterImportRow meterRow(String plot, String resource, String serial, String reading) {
        return new MeterImportRow(2, plot, resource, serial, SINCE, new BigDecimal(reading));
    }

    @Test
    void newMeterAndPlotIdsAreNotPartOfTheDigestButAnExistingMeterIsAndReadingsCompareByValue() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        MeterImportRow row = meterRow("P-1", "ELECTRICITY", "0456", "1250.5");

        String one = meterDigest(first, row, MeterImportAction.NEW, first, first);
        assertThat(meterDigest(second, row, MeterImportAction.NEW, second, second)).isEqualTo(one);
        assertThat(meterDigest(first, meterRow(" p-1 ", "electricity", "0456", "1250.500"), MeterImportAction.NEW,
                first, first)).isEqualTo(one);

        assertThat(meterDigest(first, meterRow("P-1", "ELECTRICITY", "0456", "1250.6"), MeterImportAction.NEW, first,
                first)).isNotEqualTo(one);
        assertThat(meterDigest(first, meterRow("P-1", "WATER", "0456", "1250.5"), MeterImportAction.NEW, first,
                first)).isNotEqualTo(one);
        assertThat(meterDigest(first, meterRow("P-1", "ELECTRICITY", "0457", "1250.5"), MeterImportAction.NEW, first,
                first)).isNotEqualTo(one);
        // an existing meter that is unchanged: its id is part of the digest, so another meter means another plan
        String unchanged = meterDigest(first, row, MeterImportAction.UNCHANGED, second, second);
        assertThat(unchanged).isNotEqualTo(one)
                .isNotEqualTo(meterDigest(first, row, MeterImportAction.UNCHANGED, UUID.randomUUID(), second));
    }

    @Test
    void theIdsOfRecordsTheImportCreatesAreNotPartOfTheDigestButTheIdsOfExistingOnesAre() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        OwnershipSheet.ParsedRow owned = new OwnershipSheet.ParsedRow(2, "P-1", "K", "1", SINCE);

        String one = digest(first, first, plotRow("P-1", "600"), memberRow("K", "Last"), owned, first);
        String other = digest(second, second, plotRow("P-1", "600"), memberRow("K", "Last"), owned, second);
        // the ownership names a plot the import does not create: its id is an existing record's and counts
        String existing = digest(first, first, plotRow("P-1", "600"), memberRow("K", "Last"), owned, second);
        String existingOther = digest(first, first, plotRow("P-1", "600"), memberRow("K", "Last"), owned,
                UUID.randomUUID());

        assertThat(other).isEqualTo(one);
        assertThat(existing).isNotEqualTo(one).isNotEqualTo(existingOther);
    }

    @Test
    void keysAreComparedIgnoringCaseAndSpacesAndAreasByValue() {
        UUID id = UUID.randomUUID();
        OwnershipSheet.ParsedRow owned = new OwnershipSheet.ParsedRow(2, "P-1", "K", "1", SINCE);
        OwnershipSheet.ParsedRow ownedOtherCase = new OwnershipSheet.ParsedRow(9, " p-1 ", "k", "1", SINCE);

        assertThat(digest(id, id, plotRow("P-1", "600"), memberRow("K", "Last"), owned, id))
                .isEqualTo(digest(id, id, plotRow(" p-1", "600.00"), memberRow("k ", "Last"), ownedOtherCase, id))
                .isEqualTo(digest(id, id, plotRow("P-1", "6E+2"), memberRow("K", "Last"), owned, id));
        assertThat(digest(id, id, plotRow("P-1", "600"), memberRow("K", "Last"), owned, id))
                .isNotEqualTo(digest(id, id, plotRow("P-1", "601"), memberRow("K", "Last"), owned, id))
                .isNotEqualTo(digest(id, id, plotRow("P-1", "600"), memberRow("K", "Other"), owned, id));
    }

    @Test
    void aValueCannotBeMistakenForAnotherByMovingTheBoundaryBetweenTwoValues() {
        UUID id = UUID.randomUUID();
        OwnershipSheet.ParsedRow owned = new OwnershipSheet.ParsedRow(2, "P-1", "K", "1", SINCE);
        MemberImportRow split = new MemberImportRow(2, "K", "ab", "c", null, List.of(), null, null, null, null, null);
        MemberImportRow moved = new MemberImportRow(2, "K", "a", "bc", null, List.of(), null, null, null, null, null);
        MemberImportRow empty = new MemberImportRow(2, "K", "-", "c", null, List.of(), null, null, null, null, null);
        MemberImportRow missing = new MemberImportRow(2, "K", null, "c", null, List.of(), null, null, null, null, null);

        assertThat(digest(id, id, plotRow("P-1", "600"), split, owned, id))
                .isNotEqualTo(digest(id, id, plotRow("P-1", "600"), moved, owned, id));
        assertThat(digest(id, id, plotRow("P-1", "600"), empty, owned, id))
                .isNotEqualTo(digest(id, id, plotRow("P-1", "600"), missing, owned, id));
    }

    /** One new plot P-1 (row 2) with a water cell and one opening balance row of plot P-1 or an existing plot. */
    private static String feature011Digest(UUID newPlot, WaterConnectionImport.Item water,
            OpeningBalanceImport.Row opening, OpeningBalanceImport.Item openingItem) {
        return PlanDigest.of(List.of(plotRow("P-1", "600")),
                new PlotImportPlan(List.of(new PlotImportItem(2, PlotImportAction.NEW, newPlot, List.of(), List.of()))),
                List.of(), new MemberImportPlan(List.of()), List.of(), new OwnershipImportPlan(List.of()),
                List.of(), new MeterImportPlan(List.of()),
                List.of(new WaterConnectionImport.Row(2, "P-1", null)), new WaterConnectionImport.Plan(List.of(water)),
                List.of(opening), new OpeningBalanceImport.Plan(List.of(openingItem)));
    }

    private static OpeningBalanceImport.Row openingRow(String plot, String amount, String reason) {
        return new OpeningBalanceImport.Row(5, plot, "Membership fee", new BigDecimal(amount), SINCE, reason);
    }

    @Test
    void waterCellsAndOpeningBalancesArePartOfTheDigestWithoutTheIdsOfNewPlots() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID type = UUID.randomUUID();
        UUID well = UUID.randomUUID();
        OpeningBalanceImport.Row row = openingRow("P-1", "1500.00", "Debt");

        String one = feature011Digest(first, new WaterConnectionImport.Item(2, WaterConnectionImport.Action.NEW, first,
                null, null), row, new OpeningBalanceImport.Item(5, OpeningBalanceImport.Action.NEW, first, type,
                List.of()));
        // the plot the import creates has another id in another preview of the same file: same digest
        assertThat(feature011Digest(second, new WaterConnectionImport.Item(2, WaterConnectionImport.Action.NEW,
                second, null, null), openingRow(" p-1 ", "1500", "Debt"), new OpeningBalanceImport.Item(5,
                OpeningBalanceImport.Action.NEW, second, type, List.of()))).isEqualTo(one);

        // another source, another action, another amount, reason or charge type: another plan
        assertThat(feature011Digest(first, new WaterConnectionImport.Item(2, WaterConnectionImport.Action.NEW, first,
                well, null), row, new OpeningBalanceImport.Item(5, OpeningBalanceImport.Action.NEW, first, type,
                List.of()))).isNotEqualTo(one);
        assertThat(feature011Digest(first, new WaterConnectionImport.Item(2, WaterConnectionImport.Action.UNCHANGED,
                first, null, null), row, new OpeningBalanceImport.Item(5, OpeningBalanceImport.Action.NEW, first,
                type, List.of()))).isNotEqualTo(one);
        WaterConnectionImport.Item water = new WaterConnectionImport.Item(2, WaterConnectionImport.Action.NEW, first,
                null, null);
        assertThat(feature011Digest(first, water, openingRow("P-1", "1500.01", "Debt"),
                new OpeningBalanceImport.Item(5, OpeningBalanceImport.Action.NEW, first, type, List.of())))
                .isNotEqualTo(one);
        assertThat(feature011Digest(first, water, openingRow("P-1", "1500.00", "Other"),
                new OpeningBalanceImport.Item(5, OpeningBalanceImport.Action.NEW, first, type, List.of())))
                .isNotEqualTo(one);
        assertThat(feature011Digest(first, water, row, new OpeningBalanceImport.Item(5,
                OpeningBalanceImport.Action.UNCHANGED, first, type, List.of()))).isNotEqualTo(one);
        assertThat(feature011Digest(first, water, row, new OpeningBalanceImport.Item(5,
                OpeningBalanceImport.Action.NEW, first, UUID.randomUUID(), List.of()))).isNotEqualTo(one);
    }
}
