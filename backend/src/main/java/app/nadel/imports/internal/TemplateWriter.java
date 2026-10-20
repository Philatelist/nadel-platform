package app.nadel.imports.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/**
 * Builds the import template in one of two languages: three sheets (Plots, Members, Ownership), one instruction row and
 * one example row. Both of those rows start with {@code #}, which the import skips. Columns that carry identifiers or
 * phone numbers are formatted as text so that Excel keeps leading zeros and long digit strings.
 * <p>
 * The English template shows the canonical sheet and column names. In the Ukrainian template everything a person reads
 * is Ukrainian: the sheet names, headers and value words come from the reader's own vocabulary
 * ({@link ImportVocabulary}, the first word of each line), so the template can never show a word the upload does not
 * accept; the instruction sentences and example values come from {@code imports/template_uk.properties}
 * ({@code template_en.properties} for English). The structure (sheets, columns, text format) is the same in both.
 */
final class TemplateWriter {

    /** Marks a row the import ignores: instructions and the example. */
    static final String COMMENT_PREFIX = "#";

    private static final String TEXT_FORMAT = "@";
    private static final int HEADER_ROW = 0;
    private static final int INSTRUCTION_ROW = 1;
    private static final int EXAMPLE_ROW = 2;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    /** A column of the template: its canonical key and whether its cells have the text format. */
    private record Column(String key, boolean text) {
    }

    /** A sheet of the template: the key of its columns in the vocabulary, its English name and its columns. */
    private record Sheet(String key, String englishName, List<Column> columns) {
    }

    private static final List<Sheet> SHEETS = List.of(
            new Sheet(ImportVocabulary.COLUMNS_PLOTS, PlotsSheet.NAME, columns(PlotColumn.values(),
                    PlotColumn.NUMBER, PlotColumn.CADASTRAL_NUMBER)),
            new Sheet(ImportVocabulary.COLUMNS_MEMBERS, MembersSheet.NAME, columns(MemberColumn.values(),
                    MemberColumn.EXTERNAL_ID, MemberColumn.PHONES, MemberColumn.VIBER_PHONE)),
            new Sheet(ImportVocabulary.COLUMNS_OWNERSHIP, OwnershipSheet.NAME, columns(OwnershipColumn.values(),
                    OwnershipColumn.PLOT_NUMBER, OwnershipColumn.MEMBER_EXTERNAL_ID, OwnershipColumn.SHARE)));

    private static List<Column> columns(SheetColumn[] all, SheetColumn... textColumns) {
        List<SheetColumn> text = List.of(textColumns);
        return Arrays.stream(all).map(column -> new Column(column.key(), text.contains(column))).toList();
    }

    private final ImportVocabulary vocabulary = ImportVocabulary.get();

    /** @return the template in the given language as an {@code .xlsx} file */
    byte[] write(TemplateLanguage language) {
        Texts texts = new Texts(language, vocabulary);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(bytes, "Nadel", "1.0")) {
            for (Sheet sheet : SHEETS) {
                writeSheet(workbook.newWorksheet(texts.sheetName(sheet)), sheet, texts);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the import template", e);
        }
        return bytes.toByteArray();
    }

    private static void writeSheet(Worksheet worksheet, Sheet sheet, Texts texts) {
        List<Column> columns = sheet.columns();
        for (int index = 0; index < columns.size(); index++) {
            Column column = columns.get(index);
            String header = texts.header(sheet, column);
            String example = texts.text(sheet, column, "example");
            if (column.text()) {
                worksheet.style(index).format(TEXT_FORMAT).set();
            }
            worksheet.width(index, Math.max(16, header.length() + 4));
            worksheet.value(HEADER_ROW, index, header);
            worksheet.style(HEADER_ROW, index).bold().set();
            worksheet.value(INSTRUCTION_ROW, index, prefixed(index, "", texts.text(sheet, column, "hint")));
            if (index == 0 || !example.isEmpty()) {
                worksheet.value(EXAMPLE_ROW, index, prefixed(index, texts.exampleLabel() + " ", example));
            }
            worksheet.style(INSTRUCTION_ROW, index).italic().wrapText(true).fontColor("808080").set();
            worksheet.style(EXAMPLE_ROW, index).italic().fontColor("808080").set();
        }
        worksheet.freezePane(0, 1);
    }

    /** The first cell of a comment row carries the {@code #} marker; the other cells hold the plain text. */
    private static String prefixed(int columnIndex, String label, String text) {
        return columnIndex == 0 ? COMMENT_PREFIX + " " + label + text : text;
    }

    /** The words of one language: names from the vocabulary (Ukrainian) and sentences from the language's bundle. */
    private static final class Texts {

        private final TemplateLanguage language;
        private final ImportVocabulary vocabulary;
        private final Properties bundle;

        Texts(TemplateLanguage language, ImportVocabulary vocabulary) {
            this.language = language;
            this.vocabulary = vocabulary;
            this.bundle = ImportVocabulary.load("imports/template_" + language.code() + ".properties");
        }

        String sheetName(Sheet sheet) {
            return language == TemplateLanguage.UK ? vocabulary.sheetLabel(sheet.key()) : sheet.englishName();
        }

        String header(Sheet sheet, Column column) {
            return language == TemplateLanguage.UK ? vocabulary.columnLabel(sheet.key(), column.key()) : column.key();
        }

        String exampleLabel() {
            return required("example.label");
        }

        /** A hint or an example of a column, with the placeholders filled in. */
        String text(Sheet sheet, Column column, String part) {
            return resolve(required(sheet.key() + "." + column.key() + "." + part));
        }

        private String required(String key) {
            String value = bundle.getProperty(key);
            if (value == null) {
                throw new IllegalStateException("No text " + key + " in the " + language.code() + " template bundle");
            }
            return value;
        }

        /** Replaces {@code {sheet.x}}, {@code {col.sheet.x}} and {@code {value.kind.X}} with the vocabulary's words. */
        private String resolve(String text) {
            return PLACEHOLDER.matcher(text).replaceAll(match -> Matcher.quoteReplacement(word(match.group(1))));
        }

        private String word(String placeholder) {
            String[] parts = placeholder.split("[.]", 3);
            return switch (parts[0]) {
                case "sheet" -> vocabulary.sheetLabel(parts[1]);
                case "col" -> vocabulary.columnLabel(parts[1], parts[2]);
                case "value" -> vocabulary.valueLabel(parts[1], parts[2]);
                default -> throw new IllegalStateException("Unknown placeholder {" + placeholder + "}");
            };
        }
    }
}
