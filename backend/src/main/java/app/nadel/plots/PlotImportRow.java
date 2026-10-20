package app.nadel.plots;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * One row of the Plots sheet after the cells were read: trimmed text, the area already in square metres, the
 * building flag as a boolean. A blank cell is {@code null}. The rules (required values, formats, lengths) are checked
 * by {@link PlotImport#plan}.
 *
 * @param row             the Excel row number, echoed in the plan
 * @param kind            {@code PLOT} or {@code COMMON}, any letter case
 * @param areaM2          area in square metres
 * @param cadastralNumber as typed; the format is checked by the plan
 */
public record PlotImportRow(
        int row,
        @Nullable String number,
        @Nullable String street,
        @Nullable String kind,
        @Nullable BigDecimal areaM2,
        @Nullable String cadastralNumber,
        @Nullable Boolean hasBuilding,
        @Nullable String notes) {
}
