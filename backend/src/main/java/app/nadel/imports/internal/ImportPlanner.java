package app.nadel.imports.internal;

import app.nadel.members.MemberImport;
import app.nadel.members.MemberImportItem;
import app.nadel.members.MemberImportPlan;
import app.nadel.members.MemberImportRow;
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
 * Asks the plots and members modules what each parsed row would do and puts the answers together with the problems
 * found while reading the file: counts, problems in file order, the normalised rows to store for a later commit, the
 * member of every Members row and the digest of the whole plan. The caller provides the transaction.
 * <p>
 * The Ownership sheet is planned last. The planner resolves each row's member key to a member through the Members
 * sheet (case-insensitive). A key with no Members row is {@code UNKNOWN_MEMBER_KEY}. A key whose Members row has a
 * problem is not reported again: the problem is already listed on the Members sheet, and every plot with such a row is
 * checked no further until it is fixed (a second message per ownership row would only repeat it). A plot whose own
 * Plots row has a problem is treated the same way.
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

    private final PlotImport plotImport;
    private final MemberImport memberImport;

    ImportPlanner(PlotImport plotImport, MemberImport memberImport) {
        this.plotImport = plotImport;
        this.memberImport = memberImport;
    }

    ImportPlan plan(UUID tenantId, UUID importId, PlotsSheet.Parsed plots, MembersSheet.Parsed members,
            OwnershipSheet.Parsed ownership) {
        List<RowProblem> problems = new ArrayList<>(plots.problems());
        problems.addAll(members.problems());
        problems.addAll(ownership.problems());
        List<PlotImportRow> plotInput = plots.rows().stream().map(PlotsSheet.ParsedRow::row).toList();
        PlotsPlanned plotsPlanned = planPlots(tenantId, importId, plots, plotInput, problems);
        List<MemberImportRow> memberInput = members.rows();
        MembersPlanned membersPlanned = planMembers(tenantId, importId, memberInput, problems);
        OwnershipPlanned ownershipPlanned = planOwnership(tenantId, plotInput, plotsPlanned.effective(), ownership,
                membersPlanned, problems);
        problems.sort(Comparator.comparing((RowProblem problem) -> problem.sheet().ordinal())
                .thenComparingInt(RowProblem::row));

        PlanCounts counts = new PlanCounts(plotsPlanned.counts(), membersPlanned.counts(), ownershipPlanned.counts());
        boolean clean = problems.isEmpty();
        return new ImportPlan(counts, problems,
                clean ? storedRows(plotInput, memberInput, ownership.rows()) : Map.of(),
                clean ? membersPlanned.ids() : Map.of(),
                clean ? PlanDigest.of(plotInput, plotsPlanned.plan(), memberInput, membersPlanned.plan(),
                        ownership.rows(), ownershipPlanned.plan()) : null);
    }

    private record PlotsPlanned(PlanCounts.EntityCounts counts, PlotImportPlan plan, PlotImportPlan effective) {
    }

    /** {@code invalidKeys}: the keys of Members rows with a problem, in lower case. */
    private record MembersPlanned(PlanCounts.EntityCounts counts, MemberImportPlan plan, Map<String, UUID> ids,
            Set<String> invalidKeys) {
    }

    private record OwnershipPlanned(PlanCounts.EntityCounts counts, OwnershipImportPlan plan) {
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
            return new OwnershipPlanned(PlanCounts.EntityCounts.NONE, new OwnershipImportPlan(List.of()));
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
        return new OwnershipPlanned(new PlanCounts.EntityCounts(created, 0, unchanged), plan);
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

    /** The rows as stored with a preview: only the cells that have a value, the area as plain decimal text. */
    private static Map<String, Object> storedRows(List<PlotImportRow> rows, List<MemberImportRow> memberRows,
            List<OwnershipSheet.ParsedRow> ownershipRows) {
        List<Map<String, Object>> stored = new ArrayList<>(rows.size());
        for (PlotImportRow row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("row", row.row());
            putIfPresent(map, "number", row.number());
            putIfPresent(map, "street", row.street());
            putIfPresent(map, "kind", row.kind());
            putIfPresent(map, "areaM2", row.areaM2() == null ? null : row.areaM2().toPlainString());
            putIfPresent(map, "cadastralNumber", row.cadastralNumber());
            putIfPresent(map, "hasBuilding", row.hasBuilding());
            putIfPresent(map, "notes", row.notes());
            stored.add(map);
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("version", 1);
        plan.put("plots", stored);
        plan.put("members", storedMembers(memberRows));
        plan.put("ownership", storedOwnership(ownershipRows));
        return plan;
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
