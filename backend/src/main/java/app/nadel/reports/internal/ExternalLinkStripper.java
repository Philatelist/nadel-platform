package app.nadel.reports.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;

/**
 * Removes links to other workbooks and data connections from a template at upload (feature 013, section 4.7). The app
 * never follows them, but a stored template is downloaded and opened again in Excel, which would ask to update them.
 * <p>
 * Removed: the parts under {@code xl/externalLinks/} (with their own relationships), {@code xl/connections.xml} and the
 * parts under {@code xl/queryTables/}; in every remaining {@code .rels} part the relationships that point at a removed
 * part or have the type {@code externalLink}, {@code connections} or {@code queryTable}; the
 * {@code <externalReferences>} element of {@code xl/workbook.xml}; the {@code Override}s of removed parts in
 * {@code [Content_Types].xml}. Formulas that referred to a link keep their text (Excel shows {@code #REF!} once it
 * recalculates); a table that was the target of a query stays a plain table. Pivot caches whose source is another
 * workbook are not touched.
 * <p>
 * The parts were all read with the safe parser before ({@link TemplateUploadChecks}), so they are well-formed and
 * without a document type; the few elements removed are found by one forward pass over that text ({@link #elements}),
 * which leaves every other byte of a part as it was (namespaces, {@code mc:Ignorable} and the order of attributes
 * matter to Excel). The pass is a plain loop, linear in the length of the part and without recursion, so a start tag
 * of any length cannot exhaust the stack (a regular expression with a repeated group over a start tag recurses once
 * per character). The archive is written again only when something was removed; otherwise the uploaded file is
 * stored byte for byte.
 */
final class ExternalLinkStripper {

    /** Reads one part of the archive, bounded as {@link TemplateUploadChecks} reads it. */
    @FunctionalInterface
    interface PartReader {

        byte[] read(ZipArchiveEntry entry) throws IOException;
    }

    /** One element of a part: its text from {@code <} to the end of its end tag, and its attributes as written. */
    record Element(int start, int end, String attributes) {
    }

    private static final String RELATIONSHIP = "Relationship";
    private static final String OVERRIDE = "Override";
    private static final String EXTERNAL_REFERENCES = "externalReferences";
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([A-Za-z_][\\w.\\-:]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern CHARACTER_REFERENCE = Pattern.compile("&#(x[0-9A-Fa-f]+|[0-9]+);");
    private static final List<String> LINK_RELATIONSHIP_TYPES = List.of("/externallink", "/connections",
            "/querytable");
    private static final String WORKBOOK = "xl/workbook.xml";
    private static final String CONTENT_TYPES = "[content_types].xml";

    private ExternalLinkStripper() {
    }

    /**
     * The archive without its links, or empty when it has none.
     *
     * @param entries every entry of the archive, in the order to keep
     */
    static Optional<byte[]> strip(ZipFile zip, List<ZipArchiveEntry> entries, PartReader reader) throws IOException {
        boolean changed = false;
        Map<String, byte[]> replaced = new HashMap<>();
        for (ZipArchiveEntry entry : entries) {
            String name = entry.getName().toLowerCase(Locale.ROOT);
            if (isLinkPart(name)) {
                changed = true;
                continue;
            }
            String rewritten = null;
            if (name.endsWith(".rels")) {
                rewritten = withoutLinkRelationships(text(reader.read(entry)), baseOf(name));
            } else if (name.equals(WORKBOOK)) {
                String workbook = text(reader.read(entry));
                rewritten = remove(workbook, elements(workbook, EXTERNAL_REFERENCES), element -> true);
            } else if (name.equals(CONTENT_TYPES)) {
                rewritten = withoutLinkOverrides(text(reader.read(entry)));
            }
            if (rewritten != null) {
                replaced.put(entry.getName(), rewritten.getBytes(StandardCharsets.UTF_8));
                changed = true;
            }
        }
        if (!changed) {
            return Optional.empty();
        }
        return Optional.of(rewrite(zip, entries, replaced, reader));
    }

    /** Whether the part (lower-case name without a leading slash) is a link or connection part. */
    static boolean isLinkPart(String name) {
        return name.startsWith("xl/externallinks/") || name.equals("xl/connections.xml")
                || name.startsWith("xl/querytables/");
    }

    private static byte[] rewrite(ZipFile zip, List<ZipArchiveEntry> entries, Map<String, byte[]> replaced,
            PartReader reader) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream archive = new ZipArchiveOutputStream(out)) {
            for (ZipArchiveEntry entry : entries) {
                if (isLinkPart(entry.getName().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                ZipArchiveEntry copy = new ZipArchiveEntry(entry.getName());
                copy.setMethod(ZipEntry.DEFLATED);
                copy.setTime(entry.getTime());
                archive.putArchiveEntry(copy);
                if (!entry.isDirectory()) {
                    byte[] data = replaced.get(entry.getName());
                    archive.write(data != null ? data : reader.read(entry));
                }
                archive.closeArchiveEntry();
            }
        }
        return out.toByteArray();
    }

    /** The relationships part without the link relationships; null when it has none. */
    private static String withoutLinkRelationships(String rels, String base) {
        return remove(rels, elements(rels, RELATIONSHIP), relationship -> {
            Map<String, String> attributes = attributes(relationship.attributes());
            String type = attributes.getOrDefault("Type", "").toLowerCase(Locale.ROOT);
            boolean external = "external".equalsIgnoreCase(attributes.get("TargetMode"));
            boolean linkType = LINK_RELATIONSHIP_TYPES.stream().anyMatch(type::endsWith);
            String target = attributes.get("Target");
            boolean linkTarget = !external && target != null && isLinkPart(resolve(base, target));
            return linkType || linkTarget;
        });
    }

    /** {@code [Content_Types].xml} without the overrides of link parts; null when it has none. */
    private static String withoutLinkOverrides(String contentTypes) {
        return remove(contentTypes, elements(contentTypes, OVERRIDE), override -> isLinkPart(resolve("",
                attributes(override.attributes()).getOrDefault("PartName", ""))));
    }

    /** The text without the elements the test picks, every other character kept; null when none is picked. */
    private static String remove(String xml, List<Element> elements, Predicate<Element> removed) {
        StringBuilder result = new StringBuilder(xml.length());
        int copied = 0;
        for (Element element : elements) {
            if (removed.test(element)) {
                result.append(xml, copied, element.start());
                copied = element.end();
            }
        }
        if (copied == 0) {
            return null;
        }
        return result.append(xml, copied, xml.length()).toString();
    }

    /**
     * Every element of the part with this local name (any namespace prefix, or none) that is not inside another one,
     * in document order. One forward pass: comments, CDATA sections, processing instructions and declarations are
     * skipped whole; a start tag ends at its first {@code >} outside a quoted attribute value; an element that is not
     * empty ends with its matching end tag (open elements are counted). The text is well-formed XML (checked before),
     * and a text that is not still ends the loop: every step moves forward.
     */
    static List<Element> elements(String xml, String localName) {
        List<Element> found = new ArrayList<>();
        int length = xml.length();
        int depth = 0;
        int openStart = -1;
        int openDepth = 0;
        String openAttributes = "";
        int position = 0;
        while (position < length) {
            int lt = xml.indexOf('<', position);
            if (lt < 0) {
                break;
            }
            if (xml.startsWith("<!--", lt)) {
                position = after(xml, "-->", lt + 4);
            } else if (xml.startsWith("<![CDATA[", lt)) {
                position = after(xml, "]]>", lt + 9);
            } else if (xml.startsWith("<?", lt)) {
                position = after(xml, "?>", lt + 2);
            } else if (xml.startsWith("<!", lt)) {
                position = after(xml, ">", lt + 2);
            } else if (xml.startsWith("</", lt)) {
                position = after(xml, ">", lt + 2);
                depth--;
                if (openStart >= 0 && depth == openDepth) {
                    found.add(new Element(openStart, position, openAttributes));
                    openStart = -1;
                }
            } else {
                int nameEnd = lt + 1;
                while (nameEnd < length && !endsName(xml.charAt(nameEnd))) {
                    nameEnd++;
                }
                int gt = endOfStartTag(xml, nameEnd);
                if (gt < 0) {
                    break;
                }
                boolean empty = xml.charAt(gt - 1) == '/';
                if (openStart < 0 && localName(xml.substring(lt + 1, nameEnd)).equals(localName)) {
                    String attributes = xml.substring(nameEnd, empty ? gt - 1 : gt);
                    if (empty) {
                        found.add(new Element(lt, gt + 1, attributes));
                    } else {
                        openStart = lt;
                        openDepth = depth;
                        openAttributes = attributes;
                    }
                }
                if (!empty) {
                    depth++;
                }
                position = gt + 1;
            }
        }
        return found;
    }

    /** The index of the {@code >} that ends the start tag, outside quoted values; -1 when the text ends first. */
    private static int endOfStartTag(String xml, int from) {
        char quote = 0;
        for (int index = from; index < xml.length(); index++) {
            char character = xml.charAt(index);
            if (quote != 0) {
                if (character == quote) {
                    quote = 0;
                }
            } else if (character == '"' || character == '\'') {
                quote = character;
            } else if (character == '>') {
                return index;
            }
        }
        return -1;
    }

    private static int after(String xml, String token, int from) {
        int index = xml.indexOf(token, from);
        return index < 0 ? xml.length() : index + token.length();
    }

    private static boolean endsName(char character) {
        return character == '/' || character == '>' || character == ' ' || character == '\t' || character == '\r'
                || character == '\n';
    }

    /** The name without a single namespace prefix: {@code Relationship} for {@code pr:Relationship}. */
    private static String localName(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    /** The attributes of a start tag by local name, values with their entities resolved. */
    static Map<String, String> attributes(String tag) {
        Map<String, String> attributes = new HashMap<>();
        Matcher attribute = ATTRIBUTE.matcher(tag);
        while (attribute.find()) {
            String name = attribute.group(1);
            String local = name.substring(name.indexOf(':') + 1);
            String value = attribute.group(2) != null ? attribute.group(2) : attribute.group(3);
            attributes.put(local, unescape(value));
        }
        return attributes;
    }

    /**
     * The part a relationship target points at: lower case, without a leading slash, {@code .} and {@code ..}
     * resolved, percent-escapes decoded. {@code base} is the folder of the source part ({@code xl/} for
     * {@code xl/_rels/workbook.xml.rels}).
     */
    static String resolve(String base, String target) {
        String path = target.startsWith("/") ? target.substring(1) : base + target;
        try {
            path = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // a malformed escape: compare the target as written
        }
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : path.split("/", -1)) {
            if (segment.equals("..")) {
                segments.pollLast();
            } else if (!segment.equals(".") && !segment.isEmpty()) {
                segments.addLast(segment);
            }
        }
        return String.join("/", segments).toLowerCase(Locale.ROOT);
    }

    /** The folder of the part a relationships part belongs to: {@code xl/} for {@code xl/_rels/workbook.xml.rels}. */
    static String baseOf(String relsName) {
        int rels = relsName.lastIndexOf("_rels/");
        return rels < 0 ? "" : relsName.substring(0, rels);
    }

    private static String unescape(String value) {
        Matcher reference = CHARACTER_REFERENCE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (reference.find()) {
            String code = reference.group(1);
            int codePoint;
            try {
                codePoint = code.startsWith("x") ? Integer.parseInt(code.substring(1), 16) : Integer.parseInt(code);
            } catch (NumberFormatException e) {
                codePoint = '?';
            }
            String replacement = Character.isValidCodePoint(codePoint) ? Character.toString(codePoint) : "?";
            reference.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        reference.appendTail(result);
        return result.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String text(byte[] part) {
        return new String(part, StandardCharsets.UTF_8);
    }
}
