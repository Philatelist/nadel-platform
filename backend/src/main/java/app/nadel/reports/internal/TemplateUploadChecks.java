package app.nadel.reports.internal;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.xlsx.UnsafeWorkbookException;
import app.nadel.xlsx.UnsafeWorkbookReason;
import app.nadel.xlsx.WorkbookGuard;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import nadel.contract.model.ReportTemplateUploadWarning;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * Decides whether an uploaded file may be stored as a report template, and what is stored. A template is data, never
 * code: nothing here runs a formula or anything else from the file. The checks run in this order and the first one
 * that fails answers (nothing is stored):
 * <ol>
 *   <li>size over {@value #MAX_FILE_BYTES} bytes (2 MiB): 422 {@code TEMPLATE_TOO_LARGE}, before anything is read
 *       ({@link #requireSize});</li>
 *   <li>the name does not end in {@code .xlsx} (ignoring case; {@code .xlsm}, {@code .xls}, {@code .xltx} and every
 *       other name included): {@code NOT_XLSX}; a macro-enabled workbook renamed to {@code .xlsx} is caught by its
 *       content below ({@code MACROS});</li>
 *   <li>the content does not start with the zip signature {@code PK\3\4}: {@code NOT_XLSX};</li>
 *   <li>{@link WorkbookGuard} with limits for a 2 MiB template ({@value #MAX_ENTRIES} entries, 20 MiB per entry,
 *       40 MiB in total, counted from the bytes decompression really produces): not a zip, not a workbook
 *       {@code NOT_XLSX}; too many entries, an entry or the whole too large unpacked (a compressed bomb)
 *       {@code UNPACKED_TOO_LARGE}; a corrupt, truncated or encrypted archive {@code BROKEN_ZIP};</li>
 *   <li>two entries with the same name: {@code BROKEN_ZIP} (a reader could take either);</li>
 *   <li>macros: a {@code vbaProject} part, an Excel 4.0 macro sheet ({@code xl/macrosheets/}) or a macro-enabled or
 *       VBA content type in {@code [Content_Types].xml}: {@code MACROS};</li>
 *   <li>active content: ActiveX controls ({@code xl/activeX/}) or embedded OLE objects ({@code xl/embeddings/}), by
 *       part name or content type: {@code ACTIVE_CONTENT};</li>
 *   <li>every {@code .xml} and {@code .rels} part is read with {@link TemplateXml} (StAX, no DTD, no external
 *       entities): a part that is not well-formed or declares a document type is {@code BROKEN_ZIP};</li>
 *   <li>the dry parse of the repeating rows: a row of a sheet that mixes placeholders of two row sets, or a sheet with
 *       two repeating rows: {@code TWO_ROW_SETS} (placeholders are read from the shared strings, rich-text runs joined,
 *       and from inline strings).</li>
 * </ol>
 * Links to other workbooks and data connections are then removed ({@link ExternalLinkStripper}); the stored file is
 * the cleaned one, with the warning {@code EXTERNAL_LINKS_REMOVED}, and its size and SHA-256 are those of the stored
 * bytes. A file without links is stored byte for byte as uploaded.
 * <p>
 * Every refusal is an {@link ApiProblemException} with a fixed English detail; neither the file name nor anything read
 * from the file is logged or put into an answer. Instances are stateless and thread-safe.
 */
final class TemplateUploadChecks {

    /** 2 MiB: the largest template file stored (and the {@code size_bytes} CHECK of V20). */
    static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    /** Parts a template may have; a workbook of a few sheets has 15 to 40. */
    static final int MAX_ENTRIES = 200;
    /** What one part may unpack to: ten times the largest file, far more than any real part of a 2 MiB workbook. */
    static final long MAX_ENTRY_BYTES = 20L * 1024 * 1024;
    /** What all parts together may unpack to. */
    static final long MAX_TOTAL_BYTES = 40L * 1024 * 1024;

    static final String EXTENSION = ".xlsx";

    private static final byte[] ZIP_SIGNATURE = {'P', 'K', 3, 4};
    private static final String CONTENT_TYPES = "[content_types].xml";
    private static final String SHARED_STRINGS = "xl/sharedstrings.xml";
    private static final Pattern SHEET_PART = Pattern.compile("xl/worksheets/[^/]+\\.xml");
    static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_.\\-]*)}");
    private static final int MAX_FILE_NAME_CODE_POINTS = 255;
    /** Control and invisible formatting characters (bidirectional overrides, zero-width marks). */
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final String DETAIL = "The template file is not valid.";

    /** A file that may be stored. */
    record Checked(String fileName, byte[] content, String sha256, List<ReportTemplateUploadWarning> warnings) {

        Checked {
            warnings = List.copyOf(warnings);
        }
    }

    private final WorkbookGuard guard = WorkbookGuard.withLimits(MAX_ENTRIES, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES);

    /**
     * Refuses a file over {@value #MAX_FILE_BYTES} bytes with 422 {@code TEMPLATE_TOO_LARGE}. Called with the size the
     * upload declares before the bytes are read, and again by {@link #check} with the bytes read.
     */
    static void requireSize(long size) {
        if (size > MAX_FILE_BYTES) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_CONTENT, ProblemCodes.TEMPLATE_TOO_LARGE,
                    "The template file is larger than 2 MB.");
        }
    }

    /**
     * Runs every check of the class comment in order.
     *
     * @param originalName the name the client gave the file (only its base name is kept)
     * @param content      the whole file
     * @return the sanitised file name, the bytes to store, their SHA-256 and the warnings
     * @throws ApiProblemException 422 {@code TEMPLATE_TOO_LARGE} or 400 {@code TEMPLATE_INVALID} with the reason
     */
    Checked check(@Nullable String originalName, byte[] content) {
        requireSize(content.length);
        String fileName = baseName(originalName);
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(EXTENSION) || !startsWithZipSignature(content)) {
            throw invalid(ProblemCodes.NOT_XLSX);
        }
        try {
            guard.check(fileName, content);
        } catch (UnsafeWorkbookException e) {
            throw invalid(reasonCode(e.reason()));
        }
        byte[] stored;
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(content)).get()) {
            Map<String, ZipArchiveEntry> parts = partsByLowerName(zip);
            refuseCode(zip, parts);
            refuseUnsafeXmlAndTwoRowSets(zip, parts);
            stored = ExternalLinkStripper.strip(zip, new ArrayList<>(parts.values()), entry -> read(zip, entry))
                    .orElse(content);
        } catch (IOException e) {
            throw invalid(ProblemCodes.BROKEN_ZIP);
        }
        if (stored == content) {
            return new Checked(fileName, content, sha256(content), List.of());
        }
        requireSize(stored.length);
        return new Checked(fileName, stored, sha256(stored),
                List.of(ReportTemplateUploadWarning.EXTERNAL_LINKS_REMOVED));
    }

    /** 400 {@code TEMPLATE_INVALID} with the reason as the code of {@code errors[{field: file}]}. */
    static ApiProblemException invalid(String reason) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.TEMPLATE_INVALID, DETAIL,
                List.of(new FieldProblem("file", reason)));
    }

    static String reasonCode(UnsafeWorkbookReason reason) {
        return switch (reason) {
            case WRONG_EXTENSION, NOT_A_ZIP, NOT_A_WORKBOOK -> ProblemCodes.NOT_XLSX;
            case TOO_MANY_ENTRIES, ENTRY_TOO_LARGE, TOTAL_TOO_LARGE -> ProblemCodes.UNPACKED_TOO_LARGE;
            case UNREADABLE -> ProblemCodes.BROKEN_ZIP;
        };
    }

    /**
     * The last path segment without control characters, at most 255 characters (shortened before the extension, so
     * an {@code .xlsx} name keeps it); empty when the client sent no name.
     */
    static String baseName(@Nullable String original) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = INVISIBLE.matcher(name).replaceAll("").strip();
        if (name.codePointCount(0, name.length()) > MAX_FILE_NAME_CODE_POINTS) {
            boolean xlsx = name.toLowerCase(Locale.ROOT).endsWith(EXTENSION);
            String suffix = xlsx ? name.substring(name.length() - EXTENSION.length()) : "";
            String stem = name.substring(0, name.length() - suffix.length());
            int keep = MAX_FILE_NAME_CODE_POINTS - suffix.length();
            name = stem.substring(0, stem.offsetByCodePoints(0, keep)) + suffix;
        }
        return name;
    }

    // ---- macros and active content

    private static Map<String, ZipArchiveEntry> partsByLowerName(ZipFile zip) {
        Map<String, ZipArchiveEntry> parts = new LinkedHashMap<>();
        for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
            // part names are case-insensitive in OPC: two entries that differ only in case are the same part
            if (parts.put(entry.getName().toLowerCase(Locale.ROOT), entry) != null) {
                throw invalid(ProblemCodes.BROKEN_ZIP);
            }
        }
        return parts;
    }

    private static void refuseCode(ZipFile zip, Map<String, ZipArchiveEntry> parts) throws IOException {
        for (String name : parts.keySet()) {
            if (name.contains("vbaproject") || name.startsWith("xl/macrosheets/")) {
                throw invalid(ProblemCodes.MACROS);
            }
        }
        List<String> contentTypes = contentTypes(zip, parts.get(CONTENT_TYPES));
        for (String type : contentTypes) {
            if (type.contains("macroenabled") || type.contains("vbaproject") || type.contains("macrosheet")) {
                throw invalid(ProblemCodes.MACROS);
            }
        }
        for (String name : parts.keySet()) {
            if (name.startsWith("xl/activex/") || name.startsWith("xl/embeddings/")) {
                throw invalid(ProblemCodes.ACTIVE_CONTENT);
            }
        }
        for (String type : contentTypes) {
            if (type.contains("activex") || type.contains("oleobject")) {
                throw invalid(ProblemCodes.ACTIVE_CONTENT);
            }
        }
    }

    /** Every {@code ContentType} of {@code [Content_Types].xml}, lower case. */
    private static List<String> contentTypes(ZipFile zip, @Nullable ZipArchiveEntry part) throws IOException {
        List<String> types = new ArrayList<>();
        if (part == null) {
            return types; // the guard already refuses a zip without it
        }
        try (InputStream in = bounded(zip, part)) {
            TemplateXml.read(in, reader -> {
                if (reader.getEventType() == XMLStreamConstants.START_ELEMENT) {
                    String type = reader.getAttributeValue(null, "ContentType");
                    if (type != null) {
                        types.add(type.toLowerCase(Locale.ROOT));
                    }
                }
            });
        } catch (TemplateXml.UnsafeXmlException e) {
            throw invalid(ProblemCodes.BROKEN_ZIP);
        }
        return types;
    }

    // ---- XML safety and the dry parse of the repeating rows

    private static void refuseUnsafeXmlAndTwoRowSets(ZipFile zip, Map<String, ZipArchiveEntry> parts)
            throws IOException {
        List<String> sharedStrings = sharedStrings(zip, parts.get(SHARED_STRINGS));
        for (Map.Entry<String, ZipArchiveEntry> part : parts.entrySet()) {
            String name = part.getKey();
            if (!(name.endsWith(".xml") || name.endsWith(".rels")) || name.equals(SHARED_STRINGS)
                    || name.equals(CONTENT_TYPES)) {
                continue;
            }
            SheetScan scan = SHEET_PART.matcher(name).matches() ? new SheetScan(sharedStrings) : null;
            try (InputStream in = bounded(zip, part.getValue())) {
                TemplateXml.read(in, scan == null ? reader -> { } : scan);
            } catch (TemplateXml.UnsafeXmlException e) {
                throw invalid(ProblemCodes.BROKEN_ZIP);
            }
            if (scan != null && scan.twoRowSets) {
                throw invalid(ProblemCodes.TWO_ROW_SETS);
            }
        }
    }

    /** The texts of the shared string table in order, rich-text runs joined, phonetic runs left out. */
    private static List<String> sharedStrings(ZipFile zip, @Nullable ZipArchiveEntry part) throws IOException {
        List<String> strings = new ArrayList<>();
        if (part == null) {
            return strings;
        }
        StringBuilder[] current = new StringBuilder[1];
        int[] phonetic = new int[1];
        boolean[] inText = new boolean[1];
        try (InputStream in = bounded(zip, part)) {
            TemplateXml.read(in, reader -> {
                switch (reader.getEventType()) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        switch (reader.getLocalName()) {
                            case "si" -> current[0] = new StringBuilder();
                            case "rPh" -> phonetic[0]++;
                            case "t" -> inText[0] = true;
                            default -> { }
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                        if (inText[0] && phonetic[0] == 0 && current[0] != null) {
                            current[0].append(reader.getText());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        switch (reader.getLocalName()) {
                            case "si" -> {
                                strings.add(current[0] == null ? "" : current[0].toString());
                                current[0] = null;
                            }
                            case "rPh" -> phonetic[0]--;
                            case "t" -> inText[0] = false;
                            default -> { }
                        }
                    }
                    default -> { }
                }
            });
        } catch (TemplateXml.UnsafeXmlException e) {
            throw invalid(ProblemCodes.BROKEN_ZIP);
        }
        return strings;
    }

    /**
     * Reads one sheet and notes whether a row mixes placeholders of two row sets or the sheet has two repeating rows.
     * Cells are shared strings ({@code t="s"}) or inline strings ({@code t="inlineStr"}); formula results and numbers
     * hold no placeholder.
     */
    private static final class SheetScan implements TemplateXml.Handler {

        private final List<String> sharedStrings;
        private final Set<TemplateRowSet> rowSets = EnumSet.noneOf(TemplateRowSet.class);
        private final StringBuilder text = new StringBuilder();
        private @Nullable String cellType;
        private boolean inValue;
        private boolean inInline;
        private boolean inText;
        private int phonetic;
        private int repeatingRows;
        boolean twoRowSets;

        SheetScan(List<String> sharedStrings) {
            this.sharedStrings = sharedStrings;
        }

        @Override
        public void on(XMLStreamReader reader) {
            switch (reader.getEventType()) {
                case XMLStreamConstants.START_ELEMENT -> start(reader);
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                    if ((inValue && "s".equals(cellType)) || (inInline && inText && phonetic == 0)) {
                        text.append(reader.getText());
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> end(reader.getLocalName());
                default -> { }
            }
        }

        private void start(XMLStreamReader reader) {
            switch (reader.getLocalName()) {
                case "row" -> rowSets.clear();
                case "c" -> {
                    cellType = reader.getAttributeValue(null, "t");
                    text.setLength(0);
                }
                case "v" -> inValue = true;
                case "is" -> inInline = true;
                case "t" -> inText = true;
                case "rPh" -> phonetic++;
                default -> { }
            }
        }

        private void end(String element) {
            switch (element) {
                case "v" -> inValue = false;
                case "is" -> inInline = false;
                case "t" -> inText = false;
                case "rPh" -> phonetic--;
                case "c" -> {
                    String value = cellText();
                    if (value != null) {
                        Matcher placeholder = PLACEHOLDER.matcher(value);
                        while (placeholder.find()) {
                            TemplateRowSet.of(placeholder.group(1)).ifPresent(rowSets::add);
                        }
                    }
                    cellType = null;
                }
                case "row" -> {
                    if (rowSets.size() > 1 || (rowSets.size() == 1 && ++repeatingRows > 1)) {
                        twoRowSets = true;
                    }
                }
                default -> { }
            }
        }

        private @Nullable String cellText() {
            if ("inlineStr".equals(cellType)) {
                return text.toString();
            }
            if ("s".equals(cellType)) {
                try {
                    int index = Integer.parseInt(text.toString().strip());
                    return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }
    }

    // ---- reading

    /** The whole part; more than {@link #MAX_ENTRY_BYTES} unpacked is {@code UNPACKED_TOO_LARGE}. */
    static byte[] read(ZipFile zip, ZipArchiveEntry entry) throws IOException {
        try (InputStream in = bounded(zip, entry)) {
            return in.readAllBytes();
        }
    }

    /**
     * The unpacked part, refused past {@link #MAX_ENTRY_BYTES}. The guard has already counted what every part really
     * unpacks to; this keeps the bound where the bytes are read, never trusting the sizes the archive declares.
     */
    private static InputStream bounded(ZipFile zip, ZipArchiveEntry entry) throws IOException {
        return new Bounded(zip.getInputStream(entry), MAX_ENTRY_BYTES);
    }

    private static final class Bounded extends FilterInputStream {

        private long remaining;

        Bounded(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(long bytes) {
            remaining -= bytes;
            if (remaining < 0) {
                throw invalid(ProblemCodes.UNPACKED_TOO_LARGE);
            }
        }
    }

    // ---- helpers

    private static boolean startsWithZipSignature(byte[] content) {
        if (content.length < ZIP_SIGNATURE.length) {
            return false;
        }
        for (int index = 0; index < ZIP_SIGNATURE.length; index++) {
            if (content[index] != ZIP_SIGNATURE[index]) {
                return false;
            }
        }
        return true;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
