package app.nadel.imports.internal;

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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Asks the plots module what each parsed row would do and puts the answers together with the problems found while
 * reading the file: counts, problems in file order, and the normalised rows to store for a later commit. The caller
 * provides the transaction.
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

    private final PlotImport plotImport;

    ImportPlanner(PlotImport plotImport) {
        this.plotImport = plotImport;
    }

    ImportPlan plan(UUID tenantId, UUID importId, PlotsSheet.Parsed plots) {
        List<RowProblem> problems = new ArrayList<>(plots.problems());
        Set<Integer> rowsWithProblems = new HashSet<>();
        Set<String> reported = new HashSet<>();
        for (RowProblem problem : plots.problems()) {
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
        problems.sort(Comparator.comparing((RowProblem problem) -> problem.sheet().ordinal())
                .thenComparingInt(RowProblem::row));

        PlanCounts counts = new PlanCounts(new PlanCounts.EntityCounts(created, updated, unchanged),
                PlanCounts.EntityCounts.NONE, PlanCounts.EntityCounts.NONE);
        return new ImportPlan(counts, problems, problems.isEmpty() ? storedRows(input) : Map.of());
    }

    /** The canonical column of a field of the plot form; null for a field with no column of its own. */
    private static @Nullable String column(String field, PlotColumn areaColumn) {
        PlotColumn column = AREA_FIELD.equals(field) ? areaColumn : COLUMN_OF_FIELD.get(field);
        return column == null ? null : column.key();
    }

    /** The rows as stored with a preview: only the cells that have a value, the area as plain decimal text. */
    private static Map<String, Object> storedRows(List<PlotImportRow> rows) {
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
        return plan;
    }

    private static void putIfPresent(Map<String, Object> map, String key, @Nullable Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
