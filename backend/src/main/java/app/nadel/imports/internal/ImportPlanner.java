package app.nadel.imports.internal;

import app.nadel.ledger.OpeningBalanceImport;
import app.nadel.members.MemberImport;
import app.nadel.members.MemberImportItem;
import app.nadel.members.MemberImportPlan;
import app.nadel.members.MemberImportRow;
import app.nadel.meters.MeterImport;
import app.nadel.meters.MeterImportItem;
import app.nadel.meters.MeterImportPlan;
import app.nadel.meters.MeterImportProblem;
import app.nadel.meters.MeterImportRow;
import app.nadel.meters.WaterConnectionImport;
import app.nadel.plots.OwnershipImportItem;
import app.nadel.plots.OwnershipImportOutcome;
import app.nadel.plots.OwnershipImportPlan;
import app.nadel.plots.OwnershipImportProblem;
import app.nadel.plots.OwnershipImportRow;
import app.nadel.plots.PlotImport;
import app.nadel.plots.PlotImportItem;
import app.nadel.plots.PlotImportPlan;
import app.nadel.plots.PlotImportRow;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Asks the plots, members and meters modules what each parsed row would do and puts the answers together with the
 * problems found while reading the file: counts, problems in file order, the normalised rows to store for a later
 * commit, the member of every Members row and the digest of the whole plan. The caller provides the transaction.
 * <p>
 * The Ownership sheet is planned last. The planner resolves each row's member key to a member through the Members
 * sheet (case-insensitive). A key with no Members row is {@code UNKNOWN_MEMBER_KEY}. A key whose Members row has a
 * problem is not reported again: the problem is already listed on the Members sheet, and every plot with such a row is
 * checked no further until it is fixed (a second message per ownership row would only repeat it). A plot whose own
 * Plots row has a problem is treated the same way. The Meters sheet is planned after the Plots sheet with the same rule:
 * a meter of a plot whose Plots row has a problem is not checked further. The water column of the Plots sheet and the
 * Opening balances sheet (feature 011) are planned last, by the meters and ledger modules, with the same rule.
 * <p>
 * The water cell: a yes word ({@code так}, {@code yes}, {@code true}) asks for the cooperative's water supply, a no
 * word and an empty cell ask for nothing (the import never disconnects), any other text is the name of a water source.
 */
@Component
class ImportPlanner {

    /** The field of the plot form that holds the area; its column depends on the unit the row used. */
    private static final String AREA_FIELD = "areaM2";
    private static final Map<String, PlotColumn> COLUMN_OF_FIELD = Map.of(
            "number", PlotColumn.NUMBER,
            "street", PlotColumn.STREET,
            "kind", PlotColumn.KIND,
            "cadastralNumber", PlotColumn.CADASTRAL_NUMBER,
            "notes", PlotColumn.NOTES);

    private static final Map<String, MemberColumn> COLUMN_OF_MEMBER_FIELD = Map.of(
            "externalId", MemberColumn.EXTERNAL_ID,
            "lastName", MemberColumn.LAST_NAME,
            "firstName", MemberColumn.FIRST_NAME,
            "middleName", MemberColumn.MIDDLE_NAME,
            "phones", MemberColumn.PHONES,
            "email", MemberColumn.EMAIL,
            "postalAddress", MemberColumn.POSTAL_ADDRESS,
            "deliveryChannel", MemberColumn.DELIVERY_CHANNEL,
            "telegramUsername", MemberColumn.TELEGRAM_USERNAME,
            "viberPhone", MemberColumn.VIBER_PHONE);

    private static final Map<String, OwnershipColumn> COLUMN_OF_OWNERSHIP_FIELD = Map.of(
            "plotNumber", OwnershipColumn.PLOT_NUMBER,
            "memberId", OwnershipColumn.MEMBER_EXTERNAL_ID,
            "share", OwnershipColumn.SHARE,
            "validFrom", OwnershipColumn.VALID_FROM);

    private static final Map<String, MeterColumn> COLUMN_OF_METER_FIELD = Map.of(
            "plotNumber", MeterColumn.PLOT_NUMBER,
            "resource", MeterColumn.RESOURCE,
            "serial", MeterColumn.SERIAL,
            "installedOn", MeterColumn.INSTALLED_ON,
            "initialReading", MeterColumn.INITIAL_READING);

    private static final Map<String, OpeningBalanceColumn> COLUMN_OF_OPENING_FIELD = Map.of(
            "plotNumber", OpeningBalanceColumn.PLOT_NUMBER,
            "chargeType", OpeningBalanceColumn.CHARGE_TYPE,
            "amount", OpeningBalanceColumn.AMOUNT,
            "date", OpeningBalanceColumn.DATE,
            "reason", OpeningBalanceColumn.REASON);

    private final PlotImport plotImport;
    private final MemberImport memberImport;
    private final MeterImport meterImport;
    private final WaterConnectionImport waterImport;
    private final OpeningBalanceImport openingImport;

    ImportPlanner(PlotImport plotImport, MemberImport memberImport, MeterImport meterImport,
            WaterConnectionImport waterImport, OpeningBalanceImport openingImport) {
        this.plotImport = plotImport;
        this.memberImport = memberImport;
        this.meterImport = meterImport;
        this.waterImport = waterImport;
        this.openingImport = openingImport;
    }

    ImportPlan plan(UUID tenantId, UUID importId, PlotsSheet.Parsed plots, MembersSheet.Parsed members,
            OwnershipSheet.Parsed ownership, MetersSheet.Parsed meters, OpeningBalancesSheet.Parsed openings) {
        List<RowProblem> problems = new ArrayList<>(plots.problems());
        problems.addAll(members.problems());
        problems.addAll(ownership.problems());
        problems.addAll(meters.problems());
        problems.addAll(openings.problems());
        List<PlotImportRow> plotInput = plots.rows().stream().map(PlotsSheet.ParsedRow::row).toList();
        PlotsPlanned plotsPlanned = planPlots(tenantId, importId, plots, plotInput, problems);
        List<MemberImportRow> memberInput = members.rows();
        MembersPlanned membersPlanned = planMembers(tenantId, importId, memberInput, problems);
        OwnershipPlanned ownershipPlanned = planOwnership(tenantId, plotInput, plotsPlanned.effective(), ownership,
                membersPlanned, problems);
        MetersPlanned metersPlanned = planMeters(tenantId, importId, plotInput, plotsPlanned.effective(), meters,
                problems);
        WaterPlanned waterPlanned = planWater(tenantId, plots, plotInput, plotsPlanned.effective(), problems);
        OpeningPlanned openingPlanned = planOpenings(tenantId, plotInput, plotsPlanned.effective(), openings,
                problems);
        problems.sort(Comparator.comparing((RowProblem problem) -> problem.sheet().ordinal())
                .thenComparingInt(RowProblem::row));

        PlanCounts counts = new PlanCounts(plotsPlanned.counts(), membersPlanned.counts(), ownershipPlanned.counts(),
                metersPlanned.counts(), waterPlanned.counts(), openingPlanned.counts());
        boolean clean = problems.isEmpty();
        return new ImportPlan(counts, problems,
                clean ? storedRows(plots.rows(), memberInput, ownership.rows(), meters.rows(), openings.rows())
                        : Map.of(),
                clean ? membersPlanned.ids() : Map.of(),
                clean ? PlanDigest.of(plotInput, plotsPlanned.plan(), memberInput, membersPlanned.plan(),
                        ownership.rows(), ownershipPlanned.plan(), meters.rows(), metersPlanned.plan(),
                        waterPlanned.rows(), waterPlanned.plan(), openings.rows(), openingPlanned.plan()) : null,
                clean ? new ImportPlan.Apply(plotInput, plotsPlanned.plan(), memberInput, membersPlanned.plan(),
                        ownershipPlanned.input(), ownershipPlanned.plan(), meters.rows(), metersPlanned.plan(),
                        waterPlanned.rows(), waterPlanned.plan(), openings.rows(), openingPlanned.plan())
                        : null);
    }

    private record PlotsPlanned(PlanCounts.EntityCounts counts, PlotImportPlan plan, PlotImportPlan effective) {
    }

    /** {@code invalidKeys}: the keys of Members rows with a problem, in lower case. */
    private record MembersPlanned(PlanCounts.EntityCounts counts, MemberImportPlan plan, Map<String, UUID> ids,
            Set<String> invalidKeys) {
    }

    private record OwnershipPlanned(PlanCounts.EntityCounts counts, OwnershipImportPlan plan,
            List<OwnershipImportRow> input) {
    }

    private record MetersPlanned(PlanCounts.EntityCounts counts, MeterImportPlan plan) {
    }

    /** {@code rows}: the water cells that ask for a connection, in file order. */
    private record WaterPlanned(PlanCounts.EntityCounts counts, List<WaterConnectionImport.Row> rows,
            WaterConnectionImport.Plan plan) {
    }

    private record OpeningPlanned(PlanCounts.EntityCounts counts, OpeningBalanceImport.Plan plan) {
    }

    /**
     * Adds the problems of the plot rows to {@code problems}; returns the counts of the rows without problems, the
     * plan and the plan as the ownership planning needs it: a row with any problem, also one found while reading the
     * sheet, has no action there.
     */
    private PlotsPlanned planPlots(UUID tenantId, UUID importId, PlotsSheet.Parsed plots, List<PlotImportRow> input,
            List<RowProblem> problems) {
        List<RowProblem> parseProblems = plots.problems();
        Set<Integer> rowsWithProblems = new HashSet<>();
        Set<String> reported = new HashSet<>();
        for (RowProblem problem : parseProblems) {
            rowsWithProblems.add(problem.row());
            reported.add(problem.row() + "/" + problem.column());
        }

        PlotImportPlan plan = plotImport.plan(tenantId, importId, input);

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        List<PlotImportItem> effective = new ArrayList<>(plan.items().size());
        for (int index = 0; index < plan.items().size(); index++) {
            PlotImportItem item = plan.items().get(index);
            PlotColumn areaColumn = plots.rows().get(index).areaColumn();
            for (FieldProblem field : item.problems()) {
                String column = column(field.field(), areaColumn);
                // a problem already reported for the same cell (an unreadable number) is not repeated
                if (reported.add(item.row() + "/" + column)) {
                    problems.add(new RowProblem(ImportSheet.PLOTS, item.row(), column, field.code()));
                    rowsWithProblems.add(item.row());
                }
            }
            if (item.action() != null && !rowsWithProblems.contains(item.row())) {
                effective.add(item);
                switch (item.action()) {
                    case NEW -> created++;
                    case UPDATE -> updated++;
                    case UNCHANGED -> unchanged++;
                }
            } else {
                effective.add(new PlotImportItem(item.row(), null, null, List.of(), item.problems()));
            }
        }
        return new PlotsPlanned(new PlanCounts.EntityCounts(created, updated, unchanged), plan,
                new PlotImportPlan(effective));
    }

    /**
     * Adds the problems of the member rows to {@code problems}; returns the counts of the rows without problems, the
     * member of every importable row by its key and the keys of the rows that have problems.
     */
    private MembersPlanned planMembers(UUID tenantId, UUID importId, List<MemberImportRow> input,
            List<RowProblem> problems) {
        Map<String, UUID> memberIds = new LinkedHashMap<>();
        Set<String> invalidKeys = new HashSet<>();
        MemberImportPlan plan = memberImport.plan(tenantId, importId, input);
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (int index = 0; index < plan.items().size(); index++) {
            MemberImportItem item = plan.items().get(index);
            for (FieldProblem field : item.problems()) {
                MemberColumn column = COLUMN_OF_MEMBER_FIELD.get(field.field());
                problems.add(new RowProblem(ImportSheet.MEMBERS, item.row(), column == null ? null : column.key(),
                        field.code()));
            }
            String key = key(input.get(index).externalId());
            if (item.action() == null) {
                if (key != null) {
                    invalidKeys.add(key);
                }
                continue;
            }
            switch (item.action()) {
                case NEW -> created++;
                case UPDATE -> updated++;
                case UNCHANGED -> unchanged++;
            }
            if (key != null && item.memberId() != null) {
                memberIds.put(key, item.memberId());
            }
        }
        return new MembersPlanned(new PlanCounts.EntityCounts(created, updated, unchanged), plan, memberIds,
                invalidKeys);
    }

    /**
     * Adds the problems of the ownership rows to {@code problems}: the key problems found here, the rest from the
     * plots module. A row counts when its plot has no problem at all.
     */
    private OwnershipPlanned planOwnership(UUID tenantId, List<PlotImportRow> plotInput, PlotImportPlan plotPlan,
            OwnershipSheet.Parsed sheet, MembersPlanned members, List<RowProblem> problems) {
        if (sheet.rows().isEmpty()) {
            return new OwnershipPlanned(PlanCounts.EntityCounts.NONE, new OwnershipImportPlan(List.of()), List.of());
        }
        Set<String> reported = new HashSet<>();
        for (RowProblem problem : sheet.problems()) {
            reported.add(problem.row() + "/" + problem.column());
        }
        List<OwnershipImportRow> input = new ArrayList<>(sheet.rows().size());
        for (OwnershipSheet.ParsedRow row : sheet.rows()) {
            String key = key(row.memberKey());
            UUID memberId = key == null ? null : members.ids().get(key);
            String keyProblem = null;
            if (key == null) {
                keyProblem = ProblemCodes.REQUIRED;
            } else if (memberId == null && !members.invalidKeys().contains(key)) {
                keyProblem = ProblemCodes.UNKNOWN_MEMBER_KEY;
            }
            if (keyProblem != null && reported.add(row.row() + "/" + OwnershipColumn.MEMBER_EXTERNAL_ID.key())) {
                problems.add(new RowProblem(ImportSheet.OWNERSHIP, row.row(), OwnershipColumn.MEMBER_EXTERNAL_ID.key(),
                        keyProblem));
            }
            input.add(new OwnershipImportRow(row.row(), row.plotNumber(), memberId, row.share(), row.validFrom()));
        }

        OwnershipImportPlan plan = plotImport.planOwnership(tenantId, plotInput, plotPlan, input);
        int created = 0;
        int unchanged = 0;
        for (OwnershipImportItem item : plan.items()) {
            for (OwnershipImportProblem problem : item.problems()) {
                String column = COLUMN_OF_OWNERSHIP_FIELD.get(problem.field()).key();
                if (reported.add(item.row() + "/" + column)) {
                    problems.add(new RowProblem(ImportSheet.OWNERSHIP, item.row(), column, problem.code(),
                            problem.params()));
                }
            }
            if (item.outcome() == OwnershipImportOutcome.FIRST_OWNERS) {
                created++;
            } else if (item.outcome() == OwnershipImportOutcome.SKIP) {
                unchanged++;
            }
        }
        return new OwnershipPlanned(new PlanCounts.EntityCounts(created, 0, unchanged), plan, input);
    }

    /**
     * Adds the problems of the meter rows to {@code problems}: those of the meters module, except where the same cell
     * already has a problem found while reading it (an unreadable date is not also {@code REQUIRED}). A row counts when
     * it has no problem at all; meters are never updated.
     */
    private MetersPlanned planMeters(UUID tenantId, UUID importId, List<PlotImportRow> plotInput,
            PlotImportPlan plotPlan, MetersSheet.Parsed sheet, List<RowProblem> problems) {
        if (sheet.rows().isEmpty()) {
            return new MetersPlanned(PlanCounts.EntityCounts.NONE, new MeterImportPlan(List.of()));
        }
        Set<String> reported = new HashSet<>();
        Set<Integer> rowsWithProblems = new HashSet<>();
        for (RowProblem problem : sheet.problems()) {
            reported.add(problem.row() + "/" + problem.column());
            rowsWithProblems.add(problem.row());
        }
        MeterImportPlan plan = meterImport.plan(tenantId, importId, plotInput, plotPlan, sheet.rows());
        int created = 0;
        int unchanged = 0;
        for (MeterImportItem item : plan.items()) {
            for (MeterImportProblem problem : item.problems()) {
                String column = COLUMN_OF_METER_FIELD.get(problem.field()).key();
                if (reported.add(item.row() + "/" + column)) {
                    problems.add(new RowProblem(ImportSheet.METERS, item.row(), column, problem.code(),
                            problem.params()));
                }
            }
            if (item.action() == null || rowsWithProblems.contains(item.row())) {
                continue;
            }
            switch (item.action()) {
                case NEW -> created++;
                case UNCHANGED -> unchanged++;
            }
        }
        return new MetersPlanned(new PlanCounts.EntityCounts(created, 0, unchanged), plan);
    }

    /**
     * Adds the problems of the water cells to {@code problems} (on the Plots sheet, column {@code water}). A no word and
     * an empty cell ask for nothing; a cell whose row already has a problem is not checked (its plan item has no
     * action). A plot counts when its cell has no problem: created when it is connected, unchanged when it stays.
     */
    private WaterPlanned planWater(UUID tenantId, PlotsSheet.Parsed plots, List<PlotImportRow> plotInput,
            PlotImportPlan plotPlan, List<RowProblem> problems) {
        List<WaterConnectionImport.Row> rows = new ArrayList<>();
        for (PlotsSheet.ParsedRow row : plots.rows()) {
            WaterConnectionImport.Row water = waterRow(row);
            if (water != null) {
                rows.add(water);
            }
        }
        if (rows.isEmpty()) {
            return new WaterPlanned(PlanCounts.EntityCounts.NONE, List.of(), new WaterConnectionImport.Plan(List.of()));
        }
        WaterConnectionImport.Plan plan = waterImport.plan(tenantId, plotInput, plotPlan, rows);
        int created = 0;
        int unchanged = 0;
        for (WaterConnectionImport.Item item : plan.items()) {
            if (item.problem() != null) {
                problems.add(new RowProblem(ImportSheet.PLOTS, item.row(), PlotColumn.WATER.key(), item.problem()));
            } else if (item.action() == WaterConnectionImport.Action.NEW) {
                created++;
            } else if (item.action() == WaterConnectionImport.Action.UNCHANGED) {
                unchanged++;
            }
        }
        return new WaterPlanned(new PlanCounts.EntityCounts(created, 0, unchanged), rows, plan);
    }

    /** What the water cell of a Plots row asks for; null for a no word and an empty cell. */
    private static WaterConnectionImport.@Nullable Row waterRow(PlotsSheet.ParsedRow row) {
        if (row.water() == null) {
            return null;
        }
        String yesNo = ImportVocabulary.get().canonicalValue(ImportVocabulary.YES_NO, row.water());
        PlotImportRow plot = row.row();
        if (yesNo == null) {
            return new WaterConnectionImport.Row(plot.row(), plot.number(), row.water());
        }
        return Boolean.parseBoolean(yesNo) ? new WaterConnectionImport.Row(plot.row(), plot.number(), null) : null;
    }

    /**
     * Adds the problems of the opening balance rows to {@code problems}: those of the ledger, except where the same
     * cell already has a problem found while reading it. A row counts when it has no problem at all; opening balances
     * are never updated.
     */
    private OpeningPlanned planOpenings(UUID tenantId, List<PlotImportRow> plotInput, PlotImportPlan plotPlan,
            OpeningBalancesSheet.Parsed sheet, List<RowProblem> problems) {
        if (sheet.rows().isEmpty()) {
            return new OpeningPlanned(PlanCounts.EntityCounts.NONE, new OpeningBalanceImport.Plan(List.of()));
        }
        Set<String> reported = new HashSet<>();
        Set<Integer> rowsWithProblems = new HashSet<>();
        for (RowProblem problem : sheet.problems()) {
            reported.add(problem.row() + "/" + problem.column());
            rowsWithProblems.add(problem.row());
        }
        OpeningBalanceImport.Plan plan = openingImport.plan(tenantId, plotInput, plotPlan, sheet.rows());
        int created = 0;
        int unchanged = 0;
        for (OpeningBalanceImport.Item item : plan.items()) {
            for (OpeningBalanceImport.Problem problem : item.problems()) {
                String column = COLUMN_OF_OPENING_FIELD.get(problem.field()).key();
                if (reported.add(item.row() + "/" + column)) {
                    problems.add(new RowProblem(ImportSheet.OPENING_BALANCES, item.row(), column, problem.code(),
                            problem.params()));
                }
            }
            if (item.action() == null || rowsWithProblems.contains(item.row())) {
                continue;
            }
            switch (item.action()) {
                case NEW -> created++;
                case UNCHANGED -> unchanged++;
            }
        }
        return new OpeningPlanned(new PlanCounts.EntityCounts(created, 0, unchanged), plan);
    }

    /** The key of a Members row or an Ownership row: trimmed, lower case; null when empty. */
    private static @Nullable String key(@Nullable String text) {
        return text == null || text.isBlank() ? null : text.strip().toLowerCase(Locale.ROOT);
    }

    /** The canonical column of a field of the plot form; null for a field with no column of its own. */
    private static @Nullable String column(String field, PlotColumn areaColumn) {
        PlotColumn column = AREA_FIELD.equals(field) ? areaColumn : COLUMN_OF_FIELD.get(field);
        return column == null ? null : column.key();
    }

    /**
     * The rows as stored with a preview: only the cells that have a value, the area as plain decimal text, the water
     * cell as typed.
     */
    private static Map<String, Object> storedRows(List<PlotsSheet.ParsedRow> parsedRows,
            List<MemberImportRow> memberRows, List<OwnershipSheet.ParsedRow> ownershipRows,
            List<MeterImportRow> meterRows, List<OpeningBalanceImport.Row> openingRows) {
        List<Map<String, Object>> stored = new ArrayList<>(parsedRows.size());
        for (PlotsSheet.ParsedRow parsed : parsedRows) {
            PlotImportRow row = parsed.row();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("row", row.row());
            putIfPresent(map, "number", row.number());
            putIfPresent(map, "street", row.street());
            putIfPresent(map, "kind", row.kind());
            putIfPresent(map, "areaM2", row.areaM2() == null ? null : row.areaM2().toPlainString());
            putIfPresent(map, "cadastralNumber", row.cadastralNumber());
            putIfPresent(map, "hasBuilding", row.hasBuilding());
            putIfPresent(map, "notes", row.notes());
            putIfPresent(map, "water", parsed.water());
            stored.add(map);
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("version", 1);
        plan.put("plots", stored);
        plan.put("members", storedMembers(memberRows));
        plan.put("ownership", storedOwnership(ownershipRows));
        plan.put("meters", storedMeters(meterRows));
        plan.put("openingBalances", storedOpenings(openingRows));
        return plan;
    }

    /** The amount as plain decimal text (never rounded), the date as an ISO date, the reason as typed. */
    private static List<Map<String, Object>> storedOpenings(List<OpeningBalanceImport.Row> rows) {
        List<Map<String, Object>> stored = new ArrayList<>(rows.size());
        for (OpeningBalanceImport.Row row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("row", row.row());
            putIfPresent(map, "plotNumber", row.plotNumber());
            putIfPresent(map, "chargeType", row.chargeType());
            putIfPresent(map, "amount", row.amount() == null ? null : row.amount().toPlainString());
            putIfPresent(map, "date", row.date() == null ? null : row.date().toString());
            putIfPresent(map, "reason", row.reason());
            stored.add(map);
        }
        return stored;
    }

    /** The reading as plain decimal text, the date as an ISO date. */
    private static List<Map<String, Object>> storedMeters(List<MeterImportRow> rows) {
        List<Map<String, Object>> stored = new ArrayList<>(rows.size());
        for (MeterImportRow row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("row", row.row());
            putIfPresent(map, "plotNumber", row.plotNumber());
            putIfPresent(map, "resource", row.resource());
            putIfPresent(map, "serial", row.serial());
            putIfPresent(map, "installedOn", row.installedOn() == null ? null : row.installedOn().toString());
            putIfPresent(map, "initialReading",
                    row.initialReading() == null ? null : row.initialReading().toPlainString());
            stored.add(map);
        }
        return stored;
    }

    /** The key is stored as typed; the member is found again from it when the plan is recomputed. */
    private static List<Map<String, Object>> storedOwnership(List<OwnershipSheet.ParsedRow> rows) {
        List<Map<String, Object>> stored = new ArrayList<>(rows.size());
        for (OwnershipSheet.ParsedRow row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("row", row.row());
            putIfPresent(map, "plotNumber", row.plotNumber());
            putIfPresent(map, "memberKey", row.memberKey());
            putIfPresent(map, "share", row.share());
            putIfPresent(map, "validFrom", row.validFrom() == null ? null : row.validFrom().toString());
            stored.add(map);
        }
        return stored;
    }

    private static List<Map<String, Object>> storedMembers(List<MemberImportRow> rows) {
        List<Map<String, Object>> stored = new ArrayList<>(rows.size());
        for (MemberImportRow row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("row", row.row());
            putIfPresent(map, "externalId", row.externalId());
            putIfPresent(map, "lastName", row.lastName());
            putIfPresent(map, "firstName", row.firstName());
            putIfPresent(map, "middleName", row.middleName());
            putIfPresent(map, "phones", row.phones().isEmpty() ? null : row.phones());
            putIfPresent(map, "email", row.email());
            putIfPresent(map, "postalAddress", row.postalAddress());
            putIfPresent(map, "deliveryChannel", row.deliveryChannel());
            putIfPresent(map, "telegramUsername", row.telegramUsername());
            putIfPresent(map, "viberPhone", row.viberPhone());
            stored.add(map);
        }
        return stored;
    }

    private static void putIfPresent(Map<String, Object> map, String key, @Nullable Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
