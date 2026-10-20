package app.nadel.imports.internal;

import app.nadel.plots.PlotImportRow;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * Reads the Plots sheet: finds the columns from the header row and turns every data row into a
 * {@link PlotImportRow}. Cell level problems (an unreadable number or yes/no, both areas filled) are collected
 * here; the rules about plots are checked by the plots module.
 * <p>
 * The header is the first row that is not empty and not a comment. A row whose first cell starts with {@code #} is a
 * comment (the template's instruction and example rows) and an empty row is skipped; Excel row numbers are kept.
 */
final class PlotsSheet {

    static final String NAME = "Plots";
    /** More plot rows than this are refused, before the rest of the file is read. */
    static final int MAX_ROWS = 2000;

    private static final String ALIASES_KEY = "plots";

    private PlotsSheet() {
    }

    /**
     * A plot row ready for the plots module, with the column its area came from, so a problem with the area is
     * shown on the column the person filled.
     */
    record ParsedRow(PlotImportRow row, PlotColumn areaColumn) {
    }

    /** The rows of the sheet and the problems found while reading them. */
    record Parsed(List<ParsedRow> rows, List<RowProblem> problems) {
    }

    /**
     * @throws ApiProblemException 400 {@code IMPORT_LIMIT_EXCEEDED} when the sheet has more than {@link #MAX_ROWS} rows
     */
    static Parsed parse(WorkbookReader reader) {
        Collector collector = new Collector();
        boolean present = reader.readSheet(NAME, collector);
        List<RowProblem> problems = collector.problems;
        if (!present) {
            problems.add(new RowProblem(ImportSheet.PLOTS, 0, null, ProblemCodes.SHEET_MISSING));
        } else if (collector.header == null) {
            // a sheet with no header row misses every required column
            for (PlotColumn column : PlotColumn.values()) {
                if (column.required()) {
                    problems.add(new RowProblem(ImportSheet.PLOTS, 1, column.key(), ProblemCodes.HEADER_MISSING));
                }
            }
        }
        return new Parsed(collector.rows, problems);
    }

    /** Receives the rows in file order: the header first, then the plots. */
    private static final class Collector implements Consumer<RawRow> {

        private final List<ParsedRow> rows = new ArrayList<>();
        private final List<RowProblem> problems = new ArrayList<>();
        private HeaderMatcher.@Nullable Match<PlotColumn> header;

        @Override
        public void accept(RawRow raw) {
            if (SheetRows.isEmpty(raw) || SheetRows.isComment(raw)) {
                return;
            }
            if (header == null) {
                header = HeaderMatcher.match(ALIASES_KEY, PlotColumn.class, raw);
                for (PlotColumn missing : header.missing()) {
                    problems.add(new RowProblem(ImportSheet.PLOTS, raw.number(), missing.key(),
                            ProblemCodes.HEADER_MISSING));
                }
            } else if (header.missing().isEmpty()) {
                if (rows.size() >= MAX_ROWS) {
                    throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.IMPORT_LIMIT_EXCEEDED,
                            "The file has more than " + MAX_ROWS + " plots.");
                }
                rows.add(parseRow(raw, header, problems));
            }
        }
    }

    private static ParsedRow parseRow(RawRow raw, HeaderMatcher.Match<PlotColumn> header, List<RowProblem> problems) {
        Cells.Result<Boolean> building = Cells.yesNo(header.cell(raw, PlotColumn.HAS_BUILDING));
        if (building.problem() != null) {
            problems.add(new RowProblem(ImportSheet.PLOTS, raw.number(), PlotColumn.HAS_BUILDING.key(),
                    building.problem()));
        }
        Cells.AreaResult area = Cells.area(header.cell(raw, PlotColumn.AREA_M2), header.cell(raw, PlotColumn.AREA_SOTKY));
        PlotColumn areaColumn = area.sotkyColumn() ? PlotColumn.AREA_SOTKY : PlotColumn.AREA_M2;
        if (area.problem() != null) {
            problems.add(new RowProblem(ImportSheet.PLOTS, raw.number(), areaColumn.key(), area.problem()));
        }
        BigDecimal squareMetres = area.area() == null ? null : area.area().squareMetres();
        PlotImportRow row = new PlotImportRow(raw.number(),
                text(raw, header, PlotColumn.NUMBER),
                text(raw, header, PlotColumn.STREET),
                text(raw, header, PlotColumn.KIND),
                squareMetres,
                text(raw, header, PlotColumn.CADASTRAL_NUMBER),
                building.value(),
                text(raw, header, PlotColumn.NOTES));
        return new ParsedRow(row, areaColumn);
    }

    private static @Nullable String text(RawRow raw, HeaderMatcher.Match<PlotColumn> header, PlotColumn column) {
        return Cells.text(header.cell(raw, column)).value();
    }
}
