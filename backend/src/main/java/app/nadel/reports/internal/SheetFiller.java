package app.nadel.reports.internal;

import app.nadel.money.Money;
import app.nadel.reports.internal.TemplateField.Placeholder;
import app.nadel.reports.internal.TemplateUploadChecks.SharedString;
import app.nadel.reports.internal.TemplateUploadChecks.SheetScan.Master;
import app.nadel.reports.internal.XmlTree.Node;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import nadel.contract.model.ReportTemplateWarningCode;
import org.jspecify.annotations.Nullable;

/**
 * Rewrites one worksheet of a template while it streams through (ADR 0015). Rows are taken one at a time as small trees
 * and written again: above the repeating row as they are, the repeating row once per row of its set (renumbered, with
 * fill-down formulas, {@link FormulaShifter#copy}), the rows below it moved. Every cell's placeholders are filled from
 * the {@link TemplateFiller.Fill}; every formula is shifted (shared formulas expanded first), its cached value and
 * value type dropped. The elements that hold references are taken whole and shifted: the dimension, the auto filter,
 * merged ranges, conditional formatting, data validation, hyperlinks and row breaks; one lying only on the repeating row
 * is copied per copy (a single conditional formatting or validation cell grows to every copy instead). Everything else
 * streams through unchanged.
 */
final class SheetFiller extends XmlTree.Copy {

    private static final Set<String> CAPTURED = Set.of("dimension", "autoFilter", "mergeCells",
            "conditionalFormatting", "dataValidations", "hyperlinks", "rowBreaks");

    private final TemplateFiller.Fill fill;
    private final String sheet;
    private final int repeatRow;
    private final List<Map<String, TemplateValue>> copies;
    private final @Nullable FormulaShifter own;
    private final List<FormulaShifter> others;
    private final Map<String, Master> shared;
    private int templateRow;

    SheetFiller(XMLStreamWriter out, TemplateFiller.Fill fill, TemplateFiller.SheetPlan plan) {
        super(out);
        this.fill = fill;
        this.sheet = plan.name();
        this.repeatRow = plan.scan().repeatRow();
        this.copies = fill.rows(plan);
        this.own = fill.shifter(plan);
        this.others = fill.shiftersBut(plan);
        this.shared = plan.scan().shared();
    }

    @Override
    boolean capture(Node element, int depth) {
        return depth == 3 && element.local.equals("row") || depth == 2 && CAPTURED.contains(element.local);
    }

    @Override
    void captured(Node element) throws XMLStreamException {
        switch (element.local) {
            case "row" -> row(element);
            case "dimension", "autoFilter" -> {
                String ref = element.attr("ref");
                String shifted = ref == null ? null : shift(ref, -1);
                if (shifted == null || !shifted.contains(FormulaShifter.REF_ERROR)) {
                    XmlTree.write(out, element.shallow().attr("ref", shifted));
                }
            }
            case "mergeCells" -> container(element, "mergeCell", "ref");
            case "hyperlinks" -> container(element, "hyperlink", "ref");
            case "conditionalFormatting" -> {
                String sqref = sqref(element.attr("sqref"));
                if (sqref != null) {
                    Node written = element.shallow().attr("sqref", sqref);
                    written.children.replaceAll(rule -> rule instanceof Node node ? formulas(node, "formula") : rule);
                    XmlTree.write(out, written);
                }
            }
            case "dataValidations" -> container(element, "dataValidation", "sqref");
            default -> rowBreaks(element);
        }
    }

    // ---- rows and cells

    private void row(Node row) throws XMLStreamException {
        int number = TemplateFiller.number(row.attr("r"), templateRow + 1);
        templateRow = number;
        if (own == null || number < repeatRow) {
            write(row, number, number, -1, null);
        } else if (number > repeatRow) {
            write(row, number, number + copies.size() - 1, -1, null);
        } else {
            for (int copy = 0; copy < copies.size(); copy++) {
                write(row, number, number + copy, copy, copies.get(copy));
            }
        }
    }

    private void write(Node row, int number, int newRow, int copy, @Nullable Map<String, TemplateValue> values)
            throws XMLStreamException {
        Node written = row.shallow();
        if (row.attr("r") != null) {
            written.attr("r", Integer.toString(newRow));
        }
        int column = 0;
        for (ListIterator<Object> children = written.children.listIterator(); children.hasNext();) {
            if (children.next() instanceof Node cell && cell.local.equals("c")) {
                int[] at = FormulaShifter.cell(String.valueOf(cell.attr("r")));
                column = at == null ? column + 1 : at[0];
                children.set(cell(cell, column, number, newRow, copy, values));
            }
        }
        XmlTree.write(out, written);
    }

    private Node cell(Node cell, int column, int row, int newRow, int copy,
            @Nullable Map<String, TemplateValue> values) {
        Node written = cell.shallow();
        String address = FormulaShifter.letters(column) + newRow;
        if (cell.attr("r") != null) {
            written.attr("r", address);
        }
        Node formula = cell.child("f");
        if (formula != null) {
            return formula(written, formula, column, row, copy);
        }
        String type = cell.attr("t");
        Node value = cell.child("v");
        Node inline = cell.child("is");
        SharedString text = "s".equals(type) && value != null ? fill.sharedString(value.text())
                : "inlineStr".equals(type) && inline != null ? inline(inline) : null;
        if (text == null || !text.text().contains("${")) {
            return written;
        }
        return placeholders(written, text, address, copy, values);
    }

    private Node formula(Node cell, Node formula, int column, int row, int copy) {
        Node written = formula.shallow();
        String type = formula.attr("t");
        String text = formula.text();
        Master master = "shared".equals(type) ? shared.get(String.valueOf(formula.attr("si"))) : null;
        boolean expand = master != null && (own != null || fill.namesRepeatedSheet(master.text()));
        if (expand) {
            text = FormulaShifter.offset(master.text(), row - master.row(), column - master.column());
            written.attr("t", null).attr("ref", null).attr("si", null);
        }
        String ref = formula.attr("ref");
        if ("array".equals(type) && ref != null) {
            written.attr("ref", copy >= 0 ? FormulaShifter.offset(ref, copy, 0) : shift(ref, -1));
        }
        if (!"shared".equals(type) || expand) {
            written.text(shift(text, copy));
        }
        cell.children.removeIf(child -> child instanceof Node node && (node.local.equals("v")
                || node.local.equals("f")));
        cell.children.addFirst(written);
        return cell.attr("t", null);
    }

    private Node placeholders(Node cell, SharedString text, String address, int copy,
            @Nullable Map<String, TemplateValue> values) {
        boolean first = copy <= 0;
        Matcher whole = TemplateUploadChecks.PLACEHOLDER.matcher(text.text().strip());
        if (whole.matches()) {
            Placeholder placeholder = known(whole.group(1), address, first);
            if (placeholder == null) {
                return cell;
            }
            flattened(text, address, first);
            return typed(cell, value(placeholder, address, values));
        }
        StringBuilder filled = new StringBuilder();
        boolean changed = false;
        Matcher found = TemplateUploadChecks.PLACEHOLDER.matcher(text.text());
        while (found.find()) {
            Placeholder placeholder = known(found.group(1), address, first);
            String replacement = placeholder == null ? found.group()
                    : value(placeholder, address, values).text(fill.texts().language());
            changed |= placeholder != null;
            found.appendReplacement(filled, Matcher.quoteReplacement(replacement));
        }
        found.appendTail(filled);
        if (!changed) {
            return cell;
        }
        flattened(text, address, first);
        return inlineText(cell, filled.toString());
    }

    /** The placeholder of the key; null, listed once per template cell, when the app does not know it. */
    private @Nullable Placeholder known(String key, String address, boolean first) {
        Placeholder placeholder = fill.placeholder(key);
        if (placeholder == null && first) {
            fill.warn(ReportTemplateWarningCode.UNKNOWN_PLACEHOLDER, sheet, address, key, null);
        }
        return placeholder;
    }

    private void flattened(SharedString text, String address, boolean first) {
        if (text.rich() && first) {
            fill.warn(ReportTemplateWarningCode.RICH_TEXT_FLATTENED, sheet, address, null, null);
        }
    }

    /** The value; one that is not known is listed for every cell it leaves empty. */
    private TemplateValue value(Placeholder placeholder, String address,
            @Nullable Map<String, TemplateValue> values) {
        TemplateValue value = fill.value(placeholder, values);
        if (value.isUnknown()) {
            fill.warn(ReportTemplateWarningCode.VALUE_UNKNOWN, sheet, address, placeholder.key(), value.reason());
        }
        return value;
    }

    /** The cell as a typed value, keeping its style: a number, a date serial, text as an inline string. */
    private Node typed(Node cell, TemplateValue value) {
        cell.children.clear();
        cell.attr("t", null);
        if (!value.isPresent()) {
            return cell;
        }
        Object raw = value.value();
        String number = switch (value.type()) {
            case TEXT -> null;
            case MONEY -> ((Money) raw).value().toPlainString();
            case NUMBER, QUANTITY -> ((BigDecimal) raw).toPlainString();
            case INT -> raw.toString();
            case null, default -> value.serial(fill.date1904());
        };
        if (number == null) {
            return inlineText(cell, (String) raw);
        }
        cell.children.add(cell.sibling("v").text(number));
        return cell;
    }

    private static Node inlineText(Node cell, String text) {
        Node t = cell.sibling("t").text(text);
        t.attributes.add(new String[] {"xml", XmlTree.XML, "space", "preserve"});
        Node inline = cell.sibling("is");
        inline.children.add(t);
        cell.children.clear();
        cell.children.add(inline);
        return cell.attr("t", "inlineStr");
    }

    /** The text of an inline string, runs joined, phonetic runs left out; rich when it has runs. */
    private static SharedString inline(Node inline) {
        StringBuilder text = new StringBuilder();
        boolean rich = false;
        for (Object child : inline.children) {
            if (child instanceof Node node && node.local.equals("t")) {
                text.append(node.text());
            } else if (child instanceof Node node && node.local.equals("r")) {
                rich = true;
                Node t = node.child("t");
                text.append(t == null ? "" : t.text());
            }
        }
        return new SharedString(text.toString(), rich);
    }

    // ---- references outside the cells

    /** A formula or reference text: the other repeated sheets' rows inserted, then this sheet's copy or shift. */
    private String shift(String text, int copy) {
        String shifted = text;
        for (FormulaShifter other : others) {
            shifted = other.shift(shifted, sheet);
        }
        if (own == null) {
            return shifted;
        }
        return copy >= 0 ? own.copy(shifted, copy) : own.shift(shifted, sheet);
    }

    /** Whether the reference lies only on the repeating row. */
    private boolean onRepeatingRow(String reference) {
        int[] rows = FormulaShifter.rows(reference);
        return own != null && rows != null && rows[0] == repeatRow && rows[1] == repeatRow;
    }

    /** A merged range or a hyperlink's cells: once per copy on the repeating row, otherwise shifted. */
    private List<String> references(String reference) {
        List<String> references = new ArrayList<>();
        if (onRepeatingRow(reference)) {
            for (int copy = 0; copy < copies.size(); copy++) {
                references.add(FormulaShifter.offset(reference, copy, 0));
            }
        } else {
            String shifted = shift(reference, -1);
            if (!shifted.contains(FormulaShifter.REF_ERROR)) {
                references.add(shifted);
            }
        }
        return references;
    }

    /** An {@code sqref} list shifted, a cell on the repeating row growing to every copy; null when nothing is left. */
    private @Nullable String sqref(@Nullable String sqref) {
        List<String> kept = new ArrayList<>();
        for (String reference : String.valueOf(sqref).split(" ")) {
            String item = onRepeatingRow(reference) && reference.indexOf(':') < 0 ? reference + ":" + reference
                    : reference;
            String shifted = shift(item, -1);
            if (!reference.isEmpty() && !shifted.contains(FormulaShifter.REF_ERROR)) {
                kept.add(shifted);
            }
        }
        return kept.isEmpty() ? null : String.join(" ", kept);
    }

    /** The element with the texts of its children of that name shifted. */
    private Node formulas(Node element, String... names) {
        Node written = element.shallow();
        written.children.replaceAll(child -> child instanceof Node node && List.of(names).contains(node.local)
                ? node.shallow().text(shift(node.text(), -1)) : child);
        return written;
    }

    /** Merged cells, hyperlinks, data validations: every entry rewritten, the count kept true, empty ones dropped. */
    private void container(Node element, String entry, String attribute) throws XMLStreamException {
        Node written = element.shallow();
        written.children.clear();
        for (Object child : element.children) {
            if (child instanceof Node node && node.local.equals(entry)) {
                entries(written, node, attribute);
            }
        }
        if (!written.children.isEmpty()) {
            XmlTree.write(out, element.attr("count") == null ? written
                    : written.attr("count", Integer.toString(written.children.size())));
        }
    }

    /** One merged range, hyperlink or validation: once per reference it becomes, formulas and location shifted. */
    private void entries(Node written, Node node, String attribute) {
        String reference = String.valueOf(node.attr(attribute));
        List<String> references = attribute.equals("sqref") ? listOf(sqref(reference)) : references(reference);
        for (String rewritten : references) {
            Node copy = formulas(node, "formula1", "formula2").attr(attribute, rewritten);
            String location = node.attr("location");
            written.children.add(location == null ? copy : copy.attr("location", shift(location, -1)));
        }
    }

    private static List<String> listOf(@Nullable String value) {
        return value == null ? List.of() : List.of(value);
    }

    /** Row breaks after the repeating row or below it move with their rows. */
    private void rowBreaks(Node element) throws XMLStreamException {
        Node written = element.shallow();
        written.children.replaceAll(child -> {
            if (child instanceof Node brk && own != null) {
                int id = TemplateFiller.number(brk.attr("id"), 0);
                return id >= repeatRow ? brk.shallow().attr("id", Integer.toString(id + copies.size() - 1)) : brk;
            }
            return child;
        });
        XmlTree.write(out, written);
    }
}
