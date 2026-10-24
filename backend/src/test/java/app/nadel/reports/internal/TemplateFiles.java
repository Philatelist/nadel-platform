package app.nadel.reports.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/**
 * Report template files for the tests: a valid template written by the spreadsheet library, and that template with one
 * thing changed (a macro part, a macro-enabled content type, ActiveX, an OLE object, a link to another workbook, a
 * document type in a sheet, a compressed bomb, ...). The archives are rebuilt by hand, so each file differs from the
 * valid one only in what the test is about.
 */
final class TemplateFiles {

    static final long MB = 1024L * 1024;
    static final String SHEET = "xl/worksheets/sheet1.xml";
    static final String WORKBOOK = "xl/workbook.xml";
    static final String WORKBOOK_RELS = "xl/_rels/workbook.xml.rels";
    static final String CONTENT_TYPES = "[Content_Types].xml";
    static final String MAIN_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";

    private TemplateFiles() {
    }

    /**
     * A one-sheet template "Statement": a title with {@code ${cooperative.name}} inside text, a whole-cell
     * {@code ${period.year}}, then one repeating row of plots and a totals row with a formula.
     */
    static byte[] valid() {
        return workbook(sheet -> {
            sheet.value(0, 0, "Відомість ${cooperative.name}");
            sheet.value(0, 1, "${period.year}");
            sheet.value(1, 0, "Ділянка");
            sheet.value(1, 1, "Площа");
            sheet.value(2, 0, "${rows.plots.number}");
            sheet.value(2, 1, "${rows.plots.area}");
            sheet.value(3, 0, "Разом");
            sheet.formula(3, 1, "SUM(B3:B3)");
        });
    }

    /** A one-sheet workbook with the cells the writer puts in. */
    static byte[] workbook(java.util.function.Consumer<Worksheet> cells) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(out, "Nadel tests", "1.0")) {
            cells.accept(workbook.newWorksheet("Statement"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** The template with an extra part. */
    static byte[] withPart(byte[] workbook, String name, byte[] content) {
        Map<String, byte[]> parts = parts(workbook);
        parts.put(name, content);
        return zip(parts);
    }

    /** The template with one part's text changed. */
    static byte[] withText(byte[] workbook, String name, UnaryOperator<String> change) {
        Map<String, byte[]> parts = parts(workbook);
        parts.put(name, change.apply(text(parts.get(name))).getBytes(StandardCharsets.UTF_8));
        return zip(parts);
    }

    /** The valid template with a VBA project, as Excel stores macros. */
    static byte[] withVbaProject() {
        return withPart(valid(), "xl/vbaProject.bin", new byte[] {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0});
    }

    /** The valid template declared a macro-enabled workbook in its content types, without a VBA part. */
    static byte[] withMacroEnabledContentType() {
        return withText(valid(), CONTENT_TYPES, types -> types.replace(MAIN_TYPE,
                "application/vnd.ms-excel.sheet.macroEnabled.main+xml"));
    }

    /** The valid template with an ActiveX control part. */
    static byte[] withActiveX() {
        return withPart(valid(), "xl/activeX/activeX1.xml", ("<?xml version=\"1.0\"?><ax:ocx xmlns:ax="
                + "\"http://schemas.microsoft.com/office/2006/activeX\" ax:classid=\"{8BD21D40-EC42-11CE-9E0D-00AA006002F3}\"/>")
                .getBytes(StandardCharsets.UTF_8));
    }

    /** The valid template with an embedded OLE object. */
    static byte[] withOleObject() {
        return withPart(valid(), "xl/embeddings/oleObject1.bin", new byte[] {(byte) 0xd0, (byte) 0xcf, 0x11});
    }

    /** The valid template with a document type (and an entity) in its sheet. */
    static byte[] withDoctype() {
        return withText(valid(), SHEET, sheet -> sheet.replaceFirst("<worksheet",
                "<!DOCTYPE worksheet [<!ENTITY boom \"boom\">]><worksheet"));
    }

    /** The valid template with a sheet that is not well-formed XML. */
    static byte[] withBrokenSheet() {
        return withText(valid(), SHEET, sheet -> sheet.replace("</worksheet>", ""));
    }

    /** The valid template with an extra part of zeros that packs to a few kilobytes and unpacks to {@code bytes}. */
    static byte[] withBomb(long bytes) {
        Map<String, byte[]> parts = parts(valid());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue());
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("xl/media/bomb.bin"));
            writeZeros(zip, bytes);
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * The valid template linked to another workbook as Excel stores the link: the link part with its relationship to
     * the external file, the workbook's relationship to it, {@code <externalReferences>} in the workbook, the
     * override in the content types, and a data connection.
     */
    static byte[] withExternalLink() {
        Map<String, byte[]> parts = parts(valid());
        put(parts, "xl/externalLinks/externalLink1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<externalLink xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<externalBook r:id=\"rId1\"><sheetNames><sheetName val=\"Data\"/></sheetNames></externalBook>"
                + "</externalLink>");
        put(parts, "xl/externalLinks/_rels/externalLink1.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                + "relationships/externalLinkPath\" Target=\"file:///C:/secret/budget.xlsx\" TargetMode=\"External\"/>"
                + "</Relationships>");
        put(parts, "xl/connections.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<connections xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<connection id=\"1\" name=\"db\" type=\"1\"><dbPr connection=\"DSN=secret\" command=\"x\"/>"
                + "</connection></connections>");
        put(parts, WORKBOOK_RELS, text(parts.get(WORKBOOK_RELS)).replace("</Relationships>",
                "<Relationship Id=\"rIdLink\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                        + "relationships/externalLink\" Target=\"externalLinks/externalLink1.xml\"/>"
                        + "<Relationship Id=\"rIdConn\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                        + "relationships/connections\" Target=\"connections.xml\"/></Relationships>"));
        put(parts, WORKBOOK, text(parts.get(WORKBOOK)).replace("</sheets>", "</sheets><externalReferences>"
                + "<externalReference r:id=\"rIdLink\"/></externalReferences>"));
        put(parts, CONTENT_TYPES, text(parts.get(CONTENT_TYPES)).replace("</Types>",
                "<Override PartName=\"/xl/externalLinks/externalLink1.xml\" ContentType=\"application/"
                        + "vnd.openxmlformats-officedocument.spreadsheetml.externalLink+xml\"/>"
                        + "<Override PartName=\"/xl/connections.xml\" ContentType=\"application/"
                        + "vnd.openxmlformats-officedocument.spreadsheetml.connections+xml\"/></Types>"));
        return zip(parts);
    }

    /** The template with its sheet replaced by rows of inline strings, one {@code String[]} of cell texts per row. */
    static byte[] withInlineRows(String[]... rows) {
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns="
                + "\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int r = 0; r < rows.length; r++) {
            sheet.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < rows[r].length; c++) {
                sheet.append("<c r=\"").append((char) ('A' + c)).append(r + 1).append("\" t=\"inlineStr\"><is><t>")
                        .append(rows[r][c].replace("&", "&amp;").replace("<", "&lt;")).append("</t></is></c>");
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");
        return withText(valid(), SHEET, ignored -> sheet.toString());
    }

    /** A file over 2 MiB by one byte; it starts as a zip but is never read that far. */
    static byte[] overTwoMegabytes() {
        byte[] content = new byte[(int) (2 * MB) + 1];
        content[0] = 'P';
        content[1] = 'K';
        content[2] = 3;
        content[3] = 4;
        return content;
    }

    /** A text file. */
    static byte[] text() {
        return "Відомість, не книга Excel".getBytes(StandardCharsets.UTF_8);
    }

    /** The archive's parts by name, in order. */
    static Map<String, byte[]> parts(byte[] workbook) {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(workbook)).get()) {
            for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
                parts.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return parts;
    }

    static String text(byte[] part) {
        return new String(part, StandardCharsets.UTF_8);
    }

    static byte[] zip(Map<String, byte[]> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void put(Map<String, byte[]> parts, String name, String text) {
        parts.put(name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeZeros(OutputStream out, long count) throws IOException {
        byte[] zeros = new byte[64 * 1024];
        Arrays.fill(zeros, (byte) 0);
        long left = count;
        while (left > 0) {
            int chunk = (int) Math.min(zeros.length, left);
            out.write(zeros, 0, chunk);
            left -= chunk;
        }
    }
}
