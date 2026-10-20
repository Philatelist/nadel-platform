package app.nadel.imports.internal;

import app.nadel.members.MemberImport;
import app.nadel.members.MemberImportItem;
import app.nadel.members.MemberImportPlan;
import app.nadel.members.MemberImportRow;
import app.nadel.plots.PlotImport;
import app.nadel.plots.PlotImportItem;
import app.nadel.plots.PlotImportPlan;
import app.nadel.plots.PlotImportRow;
import app.nadel.problem.FieldProblem;
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
 * found while reading the file: counts, problems in file order, the normalised rows to store for a later commit and
 * the member of every Members row. The caller provides the transaction.
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

    private final PlotImport plotImport;
    private final MemberImport memberImport;

    ImportPlanner(PlotImport plotImport, MemberImport memberImport) {
        this.plotImport = plotImport;
        this.memberImport = memberImport;
    }

    ImportPlan plan(UUID tenantId, UUID importId, PlotsSheet.Parsed plots, MembersSheet.Parsed members) {
        List<RowProblem> problems = new ArrayList<>(plots.problems());
        problems.addAll(members.problems());
        PlanCounts.EntityCounts plotCounts = planPlots(tenantId, importId, plots, problems);
        List<MemberImportRow> memberInput = members.rows();
        Map<String, UUID> memberIds = new LinkedHashMap<>();
        PlanCounts.EntityCounts memberCounts = planMembers(tenantId, importId, memberInput, problems, memberIds);
        problems.sort(Comparator.comparing((RowProblem problem) -> problem.sheet().ordinal())
                .thenComparingInt(RowProblem::row));

        PlanCounts counts = new PlanCounts(plotCounts, memberCounts, PlanCounts.EntityCounts.NONE);
        List<PlotImportRow> plotInput = plots.rows().stream().map(PlotsSheet.ParsedRow::row).toList();
        return new ImportPlan(counts, problems, problems.isEmpty() ? storedRows(plotInput, memberInput) : Map.of(),
                problems.isEmpty() ? memberIds : Map.of());
    }

    /** Adds the problems of the plot rows to {@code problems}; returns the counts of the rows without problems. */
    private PlanCounts.EntityCounts planPlots(UUID tenantId, UUID importId, PlotsSheet.Parsed plots,
            List<RowProblem> problems) {
        List<RowProblem> parseProblems = plots.problems();
        Set<Integer> rowsWithProblems = new HashSet<>();
        Set<String> reported = new HashSet<>();
        for (RowProblem problem : parseProblems) {
            rowsWithProblems.add(problem.row());
            reported.add(problem.row() + "/" + problem.column());
        }

        List<PlotImportRow> input = plots.rows().stream().map(PlotsSheet.ParsedRow::row).toList();
        PlotImportPlan plan = plotImport.plan(tenantId, importId, input);

        int created = 0;
        int updated = 0;
        int unchanged = 0;
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
                switch (item.action()) {
                    case NEW -> created++;
                    case UPDATE -> updated++;
                    case UNCHANGED -> unchanged++;
                }
            }
        }
        return new PlanCounts.EntityCounts(created, updated, unchanged);
    }

    /**
     * Adds the problems of the member rows to {@code problems} and the member of every importable row to
     * {@code memberIds}; returns the counts of the rows without problems.
     */
    private PlanCounts.EntityCounts planMembers(UUID tenantId, UUID importId, List<MemberImportRow> input,
            List<RowProblem> problems, Map<String, UUID> memberIds) {
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
            if (item.action() == null) {
                continue;
            }
            switch (item.action()) {
                case NEW -> created++;
                case UPDATE -> updated++;
                case UNCHANGED -> unchanged++;
            }
            String externalId = input.get(index).externalId();
            if (externalId != null && item.memberId() != null) {
                memberIds.put(externalId.strip().toLowerCase(Locale.ROOT), item.memberId());
            }
        }
        return new PlanCounts.EntityCounts(created, updated, unchanged);
    }

    /** The canonical column of a field of the plot form; null for a field with no column of its own. */
    private static @Nullable String column(String field, PlotColumn areaColumn) {
        PlotColumn column = AREA_FIELD.equals(field) ? areaColumn : COLUMN_OF_FIELD.get(field);
        return column == null ? null : column.key();
    }

    /** The rows as stored with a preview: only the cells that have a value, the area as plain decimal text. */
    private static Map<String, Object> storedRows(List<PlotImportRow> rows, List<MemberImportRow> memberRows) {
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
        return plan;
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
