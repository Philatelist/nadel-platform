package app.nadel.imports.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/**
 * Builds import workbooks for tests with the fastexcel writer. A cell value is a {@code String}, a number
 * ({@code int}, {@code long}, {@code double}, {@link BigDecimal}), a {@code Boolean}, a {@link LocalDate} (written as a
 * date) or {@code null} for an empty cell.
 */
final class ImportWorkbooks {

    static final List<String> PLOT_HEADER = List.of(
            "number", "street", "kind", "area_m2", "area_sotky", "cadastral_number", "has_building", "notes");

    private ImportWorkbooks() {
    }

    /** A row of the Plots sheet in {@link #PLOT_HEADER} order. */
    static List<Object> plot(Object number, Object street, Object kind, Object areaM2, Object areaSotky,
            Object cadastral, Object hasBuilding, Object notes) {
        List<Object> row = new ArrayList<>();
        row.add(number);
        row.add(street);
        row.add(kind);
        row.add(areaM2);
        row.add(areaSotky);
        row.add(cadastral);
        row.add(hasBuilding);
        row.add(notes);
        return row;
    }

    /** A plain plot: number, kind PLOT, area in square metres. */
    static List<Object> plot(String number, int areaM2) {
        return plot(number, null, "PLOT", areaM2, null, null, null, null);
    }

    /** A workbook with the standard Plots header and the given rows. */
    static byte[] plots(List<?>... rows) {
        return builder().sheet("Plots", PLOT_HEADER, rows).build();
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {

        private final Map<String, List<List<?>>> sheets = new LinkedHashMap<>();

        /** Adds a sheet: the header row, then the data rows. */
        Builder sheet(String name, List<String> header, List<?>... rows) {
            List<List<?>> all = new ArrayList<>();
            all.add(header);
            all.addAll(List.of(rows));
            return sheetWithRows(name, all);
        }

        /** Adds a sheet from complete rows (the header, if any, is the caller's first row). */
        Builder sheetWithRows(String name, List<List<?>> rows) {
            sheets.put(name, rows);
            return this;
        }

        byte[] build() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (Workbook workbook = new Workbook(out, "Nadel tests", "1.0")) {
                for (Map.Entry<String, List<List<?>>> sheet : sheets.entrySet()) {
                    Worksheet worksheet = workbook.newWorksheet(sheet.getKey());
                    List<List<?>> rows = sheet.getValue();
                    for (int r = 0; r < rows.size(); r++) {
                        List<?> row = rows.get(r);
                        for (int c = 0; c < row.size(); c++) {
                            write(worksheet, r, c, row.get(c));
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return out.toByteArray();
        }

        private static void write(Worksheet worksheet, int row, int column, Object value) {
            switch (value) {
                case null -> { }
                case String text -> worksheet.value(row, column, text);
                case Boolean flag -> worksheet.value(row, column, flag);
                case LocalDate date -> {
                    worksheet.value(row, column, date);
                    worksheet.style(row, column).format("dd.mm.yyyy").set();
                }
                case BigDecimal number -> worksheet.value(row, column, number);
                case Number number -> worksheet.value(row, column, number.doubleValue());
                default -> throw new IllegalArgumentException("Unsupported cell value " + value.getClass());
            }
        }
    }

    /**
     * A minimal workbook assembled by hand from the given parts (name to XML text), for files the writer would never
     * produce: a DOCTYPE with an external entity, for instance. The two parts every workbook has are added when the
     * caller does not give them.
     */
    static byte[] handMade(Map<String, String> parts) {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                </Types>""");
        all.put("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>""");
        all.put("xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets><sheet name="Plots" sheetId="1" r:id="rId1"/></sheets>
                </workbook>""");
        all.put("xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>""");
        all.put("xl/styles.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <fonts count="1"><font><sz val="11"/></font></fonts>
                <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
                <borders count="1"><border/></borders>
                <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                <cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
                </styleSheet>""");
        all.putAll(parts);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> part : all.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
