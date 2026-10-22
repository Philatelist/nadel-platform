package app.nadel.meters;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * One row of the Meters sheet after the cells were read. A blank cell is {@code null}; so is a cell the import could not
 * read (an unreadable date or number), which the caller has already reported. The rules (required values, the resource
 * word, lengths, the reading) are checked by {@link MeterImport#plan}.
 *
 * @param row            the Excel row number, echoed in the plan
 * @param plotNumber     the plot number as typed
 * @param resource       {@code ELECTRICITY} or {@code WATER}, any letter case; anything else is {@code INVALID}
 * @param serial         the serial number as typed (trimmed)
 * @param installedOn    the installation date
 * @param initialReading the reading on the installation date, as read from the cell; checked as a {@link MeterValue}
 */
public record MeterImportRow(
        int row,
        @Nullable String plotNumber,
        @Nullable String resource,
        @Nullable String serial,
        @Nullable LocalDate installedOn,
        @Nullable BigDecimal initialReading) {
}
