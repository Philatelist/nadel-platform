package app.nadel.reports.internal;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import app.nadel.reports.internal.TemplateField.Placeholder;
import app.nadel.reports.internal.TemplateUploadChecks.SharedString;
import app.nadel.reports.internal.TemplateUploadChecks.SheetScan;
import app.nadel.reports.internal.XmlTree.Node;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import nadel.contract.model.ReportTemplateValueReason;
import nadel.contract.model.ReportTemplateWarning;
import nadel.contract.model.ReportTemplateWarningCode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * Fills a stored report template for one period (feature 013 §4.3, ADR 0015): our own streaming OOXML filler, no
 * document model. Three steps:
 * <ol>
 *   <li><b>Check and plan</b>, without the database: the stored bytes pass every upload check again
 *       ({@link TemplateUploadChecks#check}: {@code WorkbookGuard} with the template limits, macros, active content,
 *       every part through {@link TemplateXml} with {@code <!DOCTYPE} refused, bounded reads, two row sets, arrays
 *       across the repeating row); then the workbook, its relationships, the shared strings and each sheet's
 *       {@link SheetScan} (the repeating row, the placeholder keys, the shared formulas) are read.</li>
 *   <li><b>Values</b> ({@link Values}, the caller's transaction): the rows of each repeating row's set and every single
 *       value; more rows for row sets than the reports' cap in all is 422 {@code REPORT_TOO_LARGE}.</li>
 *   <li><b>Write</b>: every part not named here is copied byte for byte; the sheets are rewritten by
 *       {@link SheetFiller}; drawing anchors and tables of a repeated sheet below its repeating row move;
 *       {@code xl/calcChain.xml} is dropped with its relationship and content type, {@code <calcPr fullCalcOnLoad="1">}
 *       set and defined names shifted in {@code xl/workbook.xml}; the warnings sheet is appended when there is a
 *       warning; {@code docProps/core.xml} names the template (title) and its version (added when absent). More than
 *       {@value #MAX_OUTPUT_BYTES} bytes unpacked is 422 {@code REPORT_TOO_LARGE}.</li>
 * </ol>
 * Steps 1 and 3 refuse everything but a refusal as 400 {@code TEMPLATE_INVALID} {@code BROKEN_ZIP}
 * ({@link TemplateUploadChecks#refusingFailures}), stack overflows included; nothing read from the file is logged or
 * answered. Instances are stateless and thread-safe; one {@link Fill} holds the state of one call.
 */
final class TemplateFiller {

    /** What the output may unpack to: far above 20 000 rows of 20 cells, far below what would exhaust the heap. */
    static final long MAX_OUTPUT_BYTES = 256L * 1024 * 1024;

    private static final String WORKBOOK = "xl/workbook.xml";
    private static final String WORKBOOK_RELS = "xl/_rels/workbook.xml.rels";
    private static final String CONTENT_TYPES = "[Content_Types].xml";
    private static final String ROOT_RELS = "_rels/.rels";
    private static final String CORE = "docProps/core.xml";
    private static final String CALC_CHAIN = "xl/calcchain.xml";
    private static final String SHARED_STRINGS = "xl/sharedstrings.xml";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String WORKSHEET_REL = REL + "/worksheet";
    private static final String CORE_REL =
            "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties";
    private static final String WORKSHEET_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml";
    private static final String CORE_TYPE = "application/vnd.openxmlformats-package.core-properties+xml";
    private static final String CP = "http://schemas.openxmlformats.org/package/2006/metadata/core-properties";
    private static final String DC = "http://purl.org/dc/elements/1.1/";
    private static final Set<String> AFTER_CALC_PR = Set.of("oleSize", "customWorkbookViews", "pivotCaches",
            "smartTagPr", "smartTagTypes", "webPublishing", "fileRecoveryPr", "webPublishObjects", "extLst");
    private static final XMLOutputFactory OUTPUT = XMLOutputFactory.newDefaultFactory();

    /** The values of one fill; {@link TemplateValues.Resolver} in the application. */
    interface Values {

        /** The placeholder of a key; empty when the app does not know it. */
        Optional<Placeholder> placeholder(String key);

        /** The value of a single placeholder (not of a row set). */
        Optional<TemplateValue> single(String key);

        /** The rows of a row set; 422 {@code REPORT_TOO_LARGE} past the cap. */
        List<Map<String, TemplateValue>> rows(TemplateRowSet set);
    }

    /** A filled workbook, its warnings in order and the rows written for row sets. */
    record Filled(byte[] content, List<ReportTemplateWarning> warnings, int rows) {
    }

    /** One worksheet: its name, its part and what its scan found. */
    record SheetPlan(String name, String part, SheetScan scan, List<String> related) {
    }

    private final TemplateUploadChecks checks = new TemplateUploadChecks();
    private final long maxOutputBytes;

    TemplateFiller() {
        this(MAX_OUTPUT_BYTES);
    }

    /** A filler with a lower output bound, for tests. */
    TemplateFiller(long maxOutputBytes) {
        this.maxOutputBytes = maxOutputBytes;
    }

    /**
     * @param template the stored file of the latest version
     * @param name     the template's name, the document title
     * @param version  the version number
     * @param rowCap   the reports' row cap
     * @throws ApiProblemException 400 {@code TEMPLATE_INVALID}, 422 {@code REPORT_TOO_LARGE}
     */
    Filled fill(byte[] template, String name, int version, Values values, ReportTexts texts, int rowCap) {
        byte[] content = checks.check("template" + TemplateUploadChecks.EXTENSION, template).content();
        Fill fill = TemplateUploadChecks.refusingFailures(() -> new Fill(content, texts));
        fill.resolve(values, rowCap);
        byte[] filled = TemplateUploadChecks.refusingFailures(() -> fill.write(name, version, maxOutputBytes));
        return new Filled(filled, List.copyOf(fill.warnings), fill.copies);
    }

    /** The state of one fill. */
    static final class Fill {

        private final Map<String, byte[]> parts = new LinkedHashMap<>();
        private final Map<String, String> names = new HashMap<>();
        private final List<SheetPlan> sheets = new ArrayList<>();
        private final List<String[]> workbookSheets = new ArrayList<>();
        private final Map<String, Optional<Placeholder>> placeholders = new HashMap<>();
        private final Map<String, TemplateValue> singles = new HashMap<>();
        private final Map<SheetPlan, List<Map<String, TemplateValue>>> rows = new HashMap<>();
        private final Map<SheetPlan, FormulaShifter> shifters = new LinkedHashMap<>();
        private final List<ReportTemplateWarning> warnings = new ArrayList<>();
        private final ReportTexts texts;
        private final List<SharedString> sharedStrings;
        private @Nullable Values values;
        private boolean date1904;
        private int copies;

        Fill(byte[] content, ReportTexts texts) throws IOException {
            this.texts = texts;
            try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(content))
                    .get()) {
                for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
                    if (!entry.isDirectory()) {
                        parts.put(entry.getName(), TemplateUploadChecks.read(zip, entry));
                        names.put(entry.getName().toLowerCase(Locale.ROOT), entry.getName());
                    }
                }
            }
            byte[] strings = part(SHARED_STRINGS);
            sharedStrings = strings == null ? List.of()
                    : unsafe(() -> TemplateUploadChecks.sharedStrings(new ByteArrayInputStream(strings)));
            Map<String, String> targets = new HashMap<>();
            for (String[] relationship : relationships(WORKBOOK_RELS)) {
                targets.put(relationship[0], relationship[2]);
            }
            read(WORKBOOK, reader -> {
                if (reader.getEventType() == XMLStreamConstants.START_ELEMENT) {
                    Node element = Node.of(reader);
                    if (element.local.equals("sheet")) {
                        workbookSheets.add(new String[] {String.valueOf(element.attr("name")),
                                String.valueOf(element.attr("sheetId")), String.valueOf(reader.getAttributeValue(REL,
                                "id"))});
                    } else if (element.local.equals("workbookPr")) {
                        String flag = String.valueOf(element.attr("date1904"));
                        date1904 = flag.equals("1") || flag.equals("true");
                    }
                }
            });
            List<String> stringTexts = sharedStrings.stream().map(SharedString::text).toList();
            for (String[] sheet : workbookSheets) {
                String part = names.get(ExternalLinkStripper.resolve("xl/", String.valueOf(targets.get(sheet[2]))));
                // a chart sheet or a dialog sheet is copied as it is
                if (part != null && part.toLowerCase(Locale.ROOT).startsWith("xl/worksheets/")) {
                    sheets.add(plan(sheet[0], part, stringTexts));
                }
            }
        }

        /**
         * One worksheet's plan: its scan and the drawings and tables it relates to. A sheet without a name, or a
         * repeating row outside the sheet, is not a workbook Excel writes: {@code BROKEN_ZIP}.
         */
        private SheetPlan plan(String name, String part, List<String> strings) {
            SheetScan scan = new SheetScan(strings);
            read(part, scan);
            boolean outside = scan.repeatSet() != null
                    && (scan.repeatRow() < 1 || scan.repeatRow() > FormulaShifter.MAX_ROW);
            if (name.isEmpty() || outside) {
                throw TemplateUploadChecks.invalid(ProblemCodes.BROKEN_ZIP);
            }
            String folder = part.substring(0, part.lastIndexOf('/') + 1);
            List<String> related = new ArrayList<>();
            for (String[] relationship : relationships(folder + "_rels/" + part.substring(folder.length()) + ".rels")) {
                String target = names.get(ExternalLinkStripper.resolve(folder, relationship[2]));
                if ((relationship[1].endsWith("/drawing") || relationship[1].endsWith("/table")) && target != null) {
                    related.add(target);
                }
            }
            return new SheetPlan(name, part, scan, related);
        }

        // ---- values

        void resolve(Values source, int rowCap) {
            values = source;
            for (SheetPlan sheet : sheets) {
                TemplateRowSet set = sheet.scan().repeatSet();
                if (set != null) {
                    List<Map<String, TemplateValue>> setRows = source.rows(set);
                    rows.put(sheet, setRows);
                    shifters.put(sheet, new FormulaShifter(sheet.name(), sheet.scan().repeatRow(), setRows.size()));
                    copies += setRows.size();
                }
                for (String key : sheet.scan().keys()) {
                    Placeholder placeholder = placeholder(key);
                    if (placeholder != null && placeholder.field().rowSet() == null) {
                        singles.computeIfAbsent(key, single -> source.single(single).orElse(TemplateValue.EMPTY));
                    }
                }
            }
            if (copies > rowCap) {
                throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, ProblemCodes.REPORT_TOO_LARGE,
                        "The report is too large. Narrow the filters.");
            }
        }

        @Nullable Placeholder placeholder(String key) {
            return placeholders.computeIfAbsent(key, Objects.requireNonNull(values)::placeholder).orElse(null);
        }

        /** The value of a placeholder: a single value, or the row's value for a row set's placeholder. */
        TemplateValue value(Placeholder placeholder, @Nullable Map<String, TemplateValue> row) {
            TemplateField field = placeholder.field();
            if (field.rowSet() == null) {
                return singles.getOrDefault(placeholder.key(), TemplateValue.EMPTY);
            }
            String key = field.family() ? field.key() + Objects.requireNonNull(placeholder.chargeType()).id()
                    : field.key();
            return row == null ? TemplateValue.EMPTY : row.getOrDefault(key, TemplateValue.EMPTY);
        }

        @Nullable SharedString sharedString(String index) {
            int at = number(index.strip(), -1);
            return at >= 0 && at < sharedStrings.size() ? sharedStrings.get(at) : null;
        }

        List<Map<String, TemplateValue>> rows(SheetPlan sheet) {
            return rows.getOrDefault(sheet, List.of());
        }

        @Nullable FormulaShifter shifter(SheetPlan sheet) {
            return shifters.get(sheet);
        }

        List<FormulaShifter> shiftersBut(SheetPlan sheet) {
            return shifters.entrySet().stream().filter(entry -> entry.getKey() != sheet).map(Map.Entry::getValue)
                    .toList();
        }

        /** Whether the text names a sheet with a repeating row ({@code Sheet1!}, {@code 'My Sheet'!}), ignoring case. */
        boolean namesRepeatedSheet(String text) {
            return shifters.keySet().stream().anyMatch(sheet -> names(text, sheet.name()));
        }

        private static boolean names(String text, String sheet) {
            String lower = text.toLowerCase(Locale.ROOT);
            String name = sheet.toLowerCase(Locale.ROOT);
            return lower.contains(name + "!") || lower.contains("'" + name.replace("'", "''") + "'!");
        }

        boolean date1904() {
            return date1904;
        }

        ReportTexts texts() {
            return texts;
        }

        void warn(ReportTemplateWarningCode code, @Nullable String sheet, @Nullable String cell,
                @Nullable String placeholder, @Nullable ReportTemplateValueReason reason) {
            warnings.add(new ReportTemplateWarning(code).sheet(sheet).cell(cell).placeholder(placeholder)
                    .reason(reason));
        }

        // ---- writing

        byte[] write(String title, int version, long maxOutputBytes) throws IOException {
            chartWarnings();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                Bounded out = new Bounded(zip, maxOutputBytes);
                Map<String, SheetPlan> sheetParts = new HashMap<>();
                Map<String, SheetPlan> related = new HashMap<>();
                for (SheetPlan sheet : sheets) {
                    sheetParts.put(sheet.part(), sheet);
                    if (shifters.containsKey(sheet)) {
                        sheet.related().forEach(part -> related.put(part, sheet));
                    }
                }
                Set<String> last = Set.of(WORKBOOK, WORKBOOK_RELS, CONTENT_TYPES.toLowerCase(Locale.ROOT), ROOT_RELS,
                        CORE.toLowerCase(Locale.ROOT), CALC_CHAIN);
                for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                    String name = part.getKey();
                    if (last.contains(name.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    zip.putNextEntry(new ZipEntry(name));
                    SheetPlan sheet = sheetParts.get(name);
                    SheetPlan moved = related.get(name);
                    if (sheet != null) {
                        xml(out, part.getValue(), writer -> new SheetFiller(writer, this, sheet));
                    } else if (moved != null) {
                        xml(out, part.getValue(), writer -> moved(writer, moved));
                    } else {
                        out.write(part.getValue());
                    }
                    zip.closeEntry();
                }
                String[] added = warnings.isEmpty() ? null : warningsSheet(zip, out);
                boolean core = part(CORE) != null;
                entry(zip, out, WORKBOOK, writer -> workbook(writer, added));
                entry(zip, out, WORKBOOK_RELS, writer -> relationships(writer, added == null ? null
                        : relationship(added[2], WORKSHEET_REL, added[3].substring("xl/".length()))));
                entry(zip, out, CONTENT_TYPES, writer -> contentTypes(writer, added == null ? null : added[3], core));
                if (part(ROOT_RELS) != null) {
                    entry(zip, out, ROOT_RELS, writer -> relationships(writer, core ? null
                            : relationship(unusedId(ROOT_RELS), CORE_REL, CORE)));
                }
                if (core) {
                    entry(zip, out, CORE, writer -> coreProperties(writer, title, version));
                } else {
                    zip.putNextEntry(new ZipEntry(CORE));
                    newCoreProperties(out, title, version);
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        }

        /** Charts are not shifted: one warning per repeated sheet a chart refers to. */
        private void chartWarnings() {
            Set<String> warned = new HashSet<>();
            for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                String name = part.getKey().toLowerCase(Locale.ROOT);
                if (name.startsWith("xl/charts/") && name.endsWith(".xml")) {
                    chartWarnings(part.getKey(), warned);
                }
            }
        }

        private void chartWarnings(String chart, Set<String> warned) {
            StringBuilder texts = new StringBuilder();
            read(chart, reader -> {
                if (reader.isCharacters()) {
                    texts.append(reader.getText()).append(' ');
                }
            });
            for (SheetPlan sheet : shifters.keySet()) {
                if (names(texts.toString(), sheet.name()) && warned.add(sheet.name())) {
                    warn(ReportTemplateWarningCode.CHART_REFS_NOT_SHIFTED, sheet.name(), null, null, null);
                }
            }
        }

        /** Drawing anchors and tables of a repeated sheet: rows below the repeating row move with them. */
        private XmlTree.Copy moved(XMLStreamWriter writer, SheetPlan sheet) {
            FormulaShifter shifter = Objects.requireNonNull(shifters.get(sheet));
            int repeatRow = sheet.scan().repeatRow();
            int delta = rows(sheet).size() - 1;
            return new XmlTree.Copy(writer) {
                @Override
                Node start(Node element, int depth) {
                    String ref = element.attr("ref");
                    return ref == null || !(element.local.equals("table") || element.local.equals("autoFilter"))
                            ? element : element.shallow().attr("ref", shifter.shift(ref, sheet.name()));
                }

                @Override
                String text(String element, String text) {
                    int row = number(text.strip(), -1); // 0-based in anchors
                    return element.equals("row") && row + 1 > repeatRow ? Integer.toString(row + delta) : text;
                }
            };
        }

        /** {@code xl/workbook.xml}: the warnings sheet, defined names shifted, {@code fullCalcOnLoad}. */
        private XmlTree.Copy workbook(XMLStreamWriter writer, String @Nullable [] added) {
            return new XmlTree.Copy(writer) {
                private @Nullable Node root;
                private boolean calc;

                @Override
                @Nullable Node start(Node element, int depth) throws XMLStreamException {
                    if (depth == 1) {
                        root = element;
                    } else if (depth == 2 && element.local.equals("calcPr")) {
                        calc = true;
                        return element.shallow().attr("fullCalcOnLoad", "1");
                    } else if (depth == 2 && !calc && AFTER_CALC_PR.contains(element.local)) {
                        calcPr();
                    }
                    return element;
                }

                @Override
                String text(String element, String text) {
                    String shifted = text;
                    for (FormulaShifter shifter : shifters.values()) {
                        shifted = element.equals("definedName") ? shifter.shift(shifted, null) : shifted;
                    }
                    return shifted;
                }

                @Override
                void end(String element, int depth) throws XMLStreamException {
                    if (element.equals("sheets") && added != null) {
                        Node sheet = Objects.requireNonNull(root).sibling("sheet").attr("name", added[0])
                                .attr("sheetId", added[1]);
                        sheet.namespaces.add(new String[] {"r", REL});
                        sheet.attributes.add(new String[] {"r", REL, "id", added[2]});
                        XmlTree.write(out, sheet);
                    } else if (depth == 1 && !calc) {
                        calcPr();
                    }
                }

                private void calcPr() throws XMLStreamException {
                    calc = true;
                    XmlTree.write(out, Objects.requireNonNull(root).sibling("calcPr").attr("fullCalcOnLoad", "1"));
                }
            };
        }

        /** A relationships part without the calculation chain, with one relationship added. */
        private static XmlTree.Copy relationships(XMLStreamWriter writer, String @Nullable [] added) {
            return new XmlTree.Copy(writer) {
                @Override
                @Nullable Node start(Node element, int depth) {
                    return String.valueOf(element.attr("Type")).endsWith("/calcChain") ? null : element;
                }

                @Override
                void end(String element, int depth) throws XMLStreamException {
                    if (depth == 1 && added != null) {
                        XmlTree.write(out, new Node("", "Relationship",
                                "http://schemas.openxmlformats.org/package/2006/relationships").attr("Id", added[0])
                                .attr("Type", added[1]).attr("Target", added[2]));
                    }
                }
            };
        }

        private static String[] relationship(String id, String type, String target) {
            return new String[] {id, type, target};
        }

        /** {@code [Content_Types].xml} without the calculation chain, with the added parts. */
        private static XmlTree.Copy contentTypes(XMLStreamWriter writer, @Nullable String sheet, boolean core) {
            return new XmlTree.Copy(writer) {
                private boolean coreListed = core;

                @Override
                @Nullable Node start(Node element, int depth) {
                    String part = String.valueOf(element.attr("PartName")).toLowerCase(Locale.ROOT);
                    coreListed |= part.equals("/docprops/core.xml");
                    return part.equals("/" + CALC_CHAIN) ? null : element;
                }

                @Override
                void end(String element, int depth) throws XMLStreamException {
                    String types = "http://schemas.openxmlformats.org/package/2006/content-types";
                    if (depth == 1 && sheet != null) {
                        XmlTree.write(out, new Node("", "Override", types).attr("PartName", "/" + sheet)
                                .attr("ContentType", WORKSHEET_TYPE));
                    }
                    if (depth == 1 && !coreListed) {
                        XmlTree.write(out, new Node("", "Override", types).attr("PartName", "/" + CORE)
                                .attr("ContentType", CORE_TYPE));
                    }
                }
            };
        }

        /** {@code docProps/core.xml} with the title and version replaced, every other property kept. */
        private static XmlTree.Copy coreProperties(XMLStreamWriter writer, String title, int version) {
            return new XmlTree.Copy(writer) {
                @Override
                @Nullable Node start(Node element, int depth) {
                    boolean replaced = element.local.equals("title") && element.uri.equals(DC)
                            || element.local.equals("version") && element.uri.equals(CP);
                    return replaced ? null : element;
                }

                @Override
                void end(String element, int depth) throws XMLStreamException {
                    if (depth == 1) {
                        properties(out, title, version);
                    }
                }
            };
        }

        private static void newCoreProperties(OutputStream out, String title, int version) throws IOException {
            document(out, writer -> {
                writer.writeStartElement("cp", "coreProperties", CP);
                writer.writeNamespace("cp", CP);
                properties(writer, title, version);
                writer.writeEndElement();
            });
        }

        private static void properties(XMLStreamWriter writer, String title, int version) throws XMLStreamException {
            writer.writeStartElement("dc", "title", DC);
            writer.writeNamespace("dc", DC);
            writer.writeCharacters(title);
            writer.writeEndElement();
            writer.writeStartElement("cp", "version", CP);
            writer.writeNamespace("cp", CP);
            writer.writeCharacters(Integer.toString(version));
            writer.writeEndElement();
        }

        /**
         * The warnings sheet: its name in the file's language, unique among the sheets, a header row and one row per
         * warning. Returns its name, sheet id, relationship id and part name.
         */
        private String[] warningsSheet(ZipOutputStream zip, Bounded out) throws IOException {
            String base = texts.text("reportTemplates.warningsSheet.name");
            Set<String> taken = new HashSet<>();
            int sheetId = 0;
            for (String[] sheet : workbookSheets) {
                taken.add(sheet[0].toLowerCase(Locale.ROOT));
                sheetId = Math.max(sheetId, number(sheet[1], 0));
            }
            String name = base;
            for (int n = 2; taken.contains(name.toLowerCase(Locale.ROOT)); n++) {
                name = base + " (" + n + ")";
            }
            int index = 1;
            while (names.containsKey("xl/worksheets/sheet" + index + ".xml")) {
                index++;
            }
            String part = "xl/worksheets/sheet" + index + ".xml";
            zip.putNextEntry(new ZipEntry(part));
            document(out, writer -> {
                writer.writeStartElement("", "worksheet", XmlTree.MAIN);
                writer.writeDefaultNamespace(XmlTree.MAIN);
                writer.writeStartElement("sheetData");
                List<String> header = new ArrayList<>();
                for (String column : List.of("sheet", "cell", "placeholder", "warning", "reason")) {
                    header.add(texts.text("reportTemplates.warningsSheet.columns." + column));
                }
                textRow(writer, 1, header);
                int row = 2;
                for (ReportTemplateWarning warning : warnings) {
                    textRow(writer, row++, Arrays.asList(warning.getSheet(), warning.getCell(),
                            warning.getPlaceholder(),
                            texts.text("reportTemplates.warnings." + warning.getCode().getValue()),
                            warning.getReason() == null ? null
                                    : texts.text("reportTemplates.valueReasons." + warning.getReason().getValue())));
                }
                writer.writeEndElement();
                writer.writeEndElement();
            });
            zip.closeEntry();
            return new String[] {name, Integer.toString(sheetId + 1), unusedId(WORKBOOK_RELS), part};
        }

        private static void textRow(XMLStreamWriter writer, int row, List<@Nullable String> texts)
                throws XMLStreamException {
            writer.writeStartElement("row");
            writer.writeAttribute("r", Integer.toString(row));
            for (int column = 0; column < texts.size(); column++) {
                String text = texts.get(column);
                if (text != null) {
                    writer.writeStartElement("c");
                    writer.writeAttribute("r", FormulaShifter.letters(column + 1) + row);
                    writer.writeAttribute("t", "inlineStr");
                    writer.writeStartElement("is");
                    writer.writeStartElement("t");
                    writer.writeAttribute("xml", XmlTree.XML, "space", "preserve");
                    writer.writeCharacters(text);
                    writer.writeEndElement();
                    writer.writeEndElement();
                    writer.writeEndElement();
                }
            }
            writer.writeEndElement();
        }

        /** A relationship id the part does not use yet. */
        private String unusedId(String rels) {
            Set<String> used = new HashSet<>();
            relationships(rels).forEach(relationship -> used.add(relationship[0]));
            int n = 1;
            while (used.contains("rIdNadel" + n)) {
                n++;
            }
            return "rIdNadel" + n;
        }

        // ---- reading and writing parts

        private byte @Nullable [] part(String name) {
            String actual = names.get(name.toLowerCase(Locale.ROOT));
            return actual == null ? null : parts.get(actual);
        }

        /** The relationships of a part as {@code {Id, Type, Target}}; none when it has no relationships part. */
        private List<String[]> relationships(String rels) {
            List<String[]> relationships = new ArrayList<>();
            if (part(rels) != null) {
                read(rels, reader -> {
                    if (reader.isStartElement() && reader.getLocalName().equals("Relationship")) {
                        Node element = Node.of(reader);
                        relationships.add(new String[] {String.valueOf(element.attr("Id")),
                                String.valueOf(element.attr("Type")), String.valueOf(element.attr("Target"))});
                    }
                });
            }
            return relationships;
        }

        private void read(String name, TemplateXml.Handler handler) {
            byte[] content = Objects.requireNonNull(part(name), name);
            unsafe(() -> {
                TemplateXml.read(new ByteArrayInputStream(content), handler);
                return null;
            });
        }

        private void entry(ZipOutputStream zip, Bounded out, String name, Rewrite rewrite) throws IOException {
            zip.putNextEntry(new ZipEntry(names.getOrDefault(name.toLowerCase(Locale.ROOT), name)));
            xml(out, Objects.requireNonNull(part(name), name), rewrite);
            zip.closeEntry();
        }

        private static void xml(OutputStream out, byte[] part, Rewrite rewrite) throws IOException {
            document(out, writer -> unsafe(() -> {
                TemplateXml.read(new ByteArrayInputStream(part), rewrite.copy(writer));
                return null;
            }));
        }
    }

    // ---- helpers

    /** Makes the copier of a part for a writer. */
    @FunctionalInterface
    private interface Rewrite {

        XmlTree.Copy copy(XMLStreamWriter writer);
    }

    /** Writes one XML document. */
    @FunctionalInterface
    private interface Document {

        void write(XMLStreamWriter writer) throws XMLStreamException, IOException;
    }

    /** Parsing that may meet an unsafe part. */
    @FunctionalInterface
    private interface Parse<T> {

        T run() throws TemplateXml.UnsafeXmlException;
    }

    /** One XML document, buffered: the JDK's writer hands an output stream one byte at a time. */
    private static void document(OutputStream out, Document document) throws IOException {
        BufferedOutputStream buffered = new BufferedOutputStream(out, 1 << 16);
        try {
            XMLStreamWriter writer = OUTPUT.createXMLStreamWriter(buffered, "UTF-8");
            writer.writeStartDocument("UTF-8", "1.0");
            document.write(writer);
            writer.writeEndDocument();
            writer.close(); // does not close the archive
            buffered.flush();
        } catch (XMLStreamException e) {
            throw new IOException("The part could not be written", e);
        }
    }

    private static <T> T unsafe(Parse<T> parse) {
        try {
            return parse.run();
        } catch (TemplateXml.UnsafeXmlException e) {
            throw TemplateUploadChecks.invalid(ProblemCodes.BROKEN_ZIP);
        }
    }

    /** The integer, or {@code otherwise} when the text is absent or not one. */
    static int number(@Nullable String text, int otherwise) {
        try {
            return text == null ? otherwise : Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    /**
     * Counts what the output unpacks to: past the bound 422 {@code REPORT_TOO_LARGE}. Closing it leaves the archive
     * open (its owner closes it).
     */
    private static final class Bounded extends OutputStream {

        private final OutputStream out;
        private final long limit;
        private long written;

        Bounded(OutputStream out, long limit) {
            this.out = out;
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            count(length);
            out.write(buffer, offset, length);
        }

        private void count(long bytes) {
            written += bytes;
            if (written > limit) {
                throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, ProblemCodes.REPORT_TOO_LARGE,
                        "The report is too large. Narrow the filters.");
            }
        }
    }
}
