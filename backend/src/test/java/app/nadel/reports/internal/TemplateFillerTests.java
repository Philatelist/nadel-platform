package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.money.Money;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.reports.internal.TemplateField.Placeholder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import nadel.contract.model.ReportTemplateFieldType;
import nadel.contract.model.ReportTemplateValueReason;
import nadel.contract.model.ReportTemplateWarning;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/**
 * The template filler (ADR 0015) on templates written by the spreadsheet library and on hand-made sheet XML
 * ({@link TemplateFiles}, {@link SampleTemplates}), with values given by the test: the rewritten parts are checked as
 * XML, and every filled file is opened again with fastexcel-reader.
 */
class TemplateFillerTests {

    private static final String SHEET = TemplateFiles.SHEET;
    private static final ReportTexts UK = ReportTexts.of(ReportLanguage.UK);
    private static final ReportTexts EN = ReportTexts.of(ReportLanguage.EN);

    private final TemplateFiller filler = new TemplateFiller();

    // ---- single values

    @Test
    void aPlaceholderAloneInACellBecomesATypedValueKeepingTheCellsStyle() {
        byte[] template = TemplateFiles.workbook(sheet -> {
            sheet.value(0, 0, "${totals.debt}");
            sheet.style(0, 0).format("#,##0.00").set();
            sheet.value(0, 1, "${period.year}");
            sheet.value(0, 2, "${period.from}");
            sheet.style(0, 2).format("dd.mm.yyyy").set();
            sheet.value(0, 3, "${cooperative.name}");
            sheet.value(0, 4, " ${totals.plots.areaSotka} ");
            sheet.value(0, 5, "${generatedAt}");
            sheet.formula(1, 0, "A1*2");
        });
        Values values = new Values()
                .single("totals.debt", money("1250.50"))
                .single("period.year", TemplateValue.of(ReportTemplateFieldType.INT, 2025L))
                .single("period.from", TemplateValue.of(ReportTemplateFieldType.DATE, LocalDate.of(2025, 1, 31)))
                .single("cooperative.name", text("СТ «Сад» & <Ко>"))
                .single("totals.plots.areaSotka", TemplateValue.of(ReportTemplateFieldType.NUMBER,
                        new BigDecimal("12.5000")))
                .single("generatedAt", TemplateValue.of(ReportTemplateFieldType.DATE_TIME,
                        LocalDateTime.of(2025, 1, 31, 18, 0)));

        TemplateFiller.Filled filled = fill(template, values);

        String sheet = part(filled, SHEET);
        assertThat(sheet).contains("<c r=\"A1\" s=\"1\"><v>1250.50</v></c>")
                .contains("<c r=\"B1\"><v>2025</v></c>")
                // 31.01.2025 is day 45 688 of the 1900 date system
                .contains("<c r=\"C1\" s=\"2\"><v>45688</v></c>")
                .contains("<c r=\"D1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">СТ «Сад» &amp; &lt;Ко&gt;</t></is></c>")
                .contains("<c r=\"E1\"><v>12.5000</v></c>")
                .contains("<c r=\"F1\"><v>45688.75</v></c>")
                // the formula still refers to the cell, now a number
                .contains("<c r=\"A2\"><f>A1*2</f></c>");
        assertThat(filled.warnings()).isEmpty();
        assertThat(filled.rows()).isZero();
        try (ReadableWorkbook workbook = open(filled)) {
            Row first = workbook.getFirstSheet().read().getFirst();
            assertThat(first.getCellAsNumber(0)).contains(new BigDecimal("1250.50"));
            assertThat(first.getCellAsDate(2)).contains(LocalDateTime.of(2025, 1, 31, 0, 0));
            assertThat(first.getCellAsString(3)).contains("СТ «Сад» & <Ко>");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aWorkbookOfThe1904DateSystemGetsSerialsOfThatSystem() {
        byte[] template = TemplateFiles.withText(TemplateFiles.workbook(sheet -> sheet.value(0, 0, "${period.to}")),
                TemplateFiles.WORKBOOK, workbook -> workbook.replace("date1904=\"false\"", "date1904=\"1\""));

        TemplateFiller.Filled filled = fill(template, new Values().single("period.to",
                TemplateValue.of(ReportTemplateFieldType.DATE, LocalDate.of(1904, 1, 31))));

        assertThat(part(filled, SHEET)).contains("<c r=\"A1\"><v>30</v></c>");
    }

    @Test
    void placeholdersInsideTextAreWrittenAsTextInTheFilesLanguage() {
        byte[] template = TemplateFiles.workbook(sheet -> sheet.value(0, 0,
                "Борг ${totals.debt} грн на ${period.to} (${totals.plots.count} ділянок, ${totals.plots.areaSotka}"
                        + " сот., ${no.such}, ${generatedAt}, ${cooperative.name})"));
        Values values = new Values()
                .single("totals.debt", money("-1234567.50"))
                .single("period.to", TemplateValue.of(ReportTemplateFieldType.DATE, LocalDate.of(2025, 3, 31)))
                .single("totals.plots.count", TemplateValue.of(ReportTemplateFieldType.INT, 1200L))
                .single("totals.plots.areaSotka", TemplateValue.of(ReportTemplateFieldType.NUMBER,
                        new BigDecimal("360.5000")))
                .single("generatedAt", TemplateValue.of(ReportTemplateFieldType.DATE_TIME,
                        LocalDateTime.of(2025, 4, 1, 9, 5, 30)))
                .single("cooperative.name", TemplateValue.EMPTY);

        assertThat(cellText(fill(template, values, UK), "A1")).isEqualTo(
                "Борг -1\u00a0234\u00a0567,50 грн на 31.03.2025 (1200 ділянок, 360,5000 сот., ${no.such}, "
                        + "01.04.2025 09:05, )");
        assertThat(cellText(fill(template, values, EN), "A1")).isEqualTo(
                "Борг -1,234,567.50 грн на 31.03.2025 (1200 ділянок, 360.5000 сот., ${no.such}, "
                        + "01.04.2025 09:05, )");
        assertThat(TemplateValue.of(ReportTemplateFieldType.MONTH, YearMonth.of(2025, 2)).text(ReportLanguage.UK))
                .isEqualTo("02.2025");
        assertThat(TemplateValue.of(ReportTemplateFieldType.QUANTITY, new BigDecimal("999.000"))
                .text(ReportLanguage.UK)).isEqualTo("999,000");
    }

    // ---- unknown placeholders and values

    @Test
    void anUnknownPlaceholderIsLeftAsWrittenAndListedOnceAndAnUnknownValueIsEmptyNeverZero() {
        byte[] template = TemplateFiles.workbook(sheet -> {
            sheet.value(0, 0, "${no.such.field}");
            sheet.value(0, 1, "Код ${cooperative.edrpou} та ${nonsense}");
            sheet.value(0, 2, "${cooperative.iban}");
            sheet.value(0, 3, "${totals.charged.NO_SUCH_TYPE}");
            sheet.value(0, 4, "Лише ${nonsense.too} тут");
        });
        Values values = new Values()
                .single("cooperative.edrpou", TemplateValue.unknown(ReportTemplateValueReason.NOT_SET))
                .single("cooperative.iban", TemplateValue.unknown(ReportTemplateValueReason.NOT_SET));

        TemplateFiller.Filled filled = fill(template, values);

        String sheet = part(filled, SHEET);
        // left as written: still the shared string
        assertThat(sheet).contains("<c r=\"A1\" t=\"s\"><v>0</v></c>").contains("<c r=\"D1\" t=\"s\"><v>3</v></c>")
                .contains("<c r=\"E1\" t=\"s\"><v>4</v></c>")
                .contains("<c r=\"C1\"></c>");
        assertThat(cellText(filled, "B1")).isEqualTo("Код  та ${nonsense}");
        assertThat(codes(filled.warnings())).containsExactly(
                "UNKNOWN_PLACEHOLDER Statement A1 no.such.field null",
                "VALUE_UNKNOWN Statement B1 cooperative.edrpou NOT_SET",
                "UNKNOWN_PLACEHOLDER Statement B1 nonsense null",
                "VALUE_UNKNOWN Statement C1 cooperative.iban NOT_SET",
                "UNKNOWN_PLACEHOLDER Statement D1 totals.charged.NO_SUCH_TYPE null",
                "UNKNOWN_PLACEHOLDER Statement E1 nonsense.too null");
        // the shared strings are never changed
        assertThat(part(filled, "xl/sharedStrings.xml"))
                .isEqualTo(TemplateFiles.text(TemplateFiles.parts(template).get("xl/sharedStrings.xml")));
    }

    @Test
    void richTextWithAPlaceholderIsFlattenedAndListedAndRichTextWithoutOneIsLeftAlone() {
        byte[] template = TemplateFiles.withText(TemplateFiles.workbook(sheet -> {
            sheet.value(0, 0, "x");
            sheet.value(0, 1, "y");
            sheet.value(0, 2, "z");
        }), "xl/sharedStrings.xml", strings -> strings.replaceFirst("<si>.*</si>", Matcher.quoteReplacement(
                "<si><r><rPr><b/></rPr><t>Рік </t></r><r><t>${period.year}</t></r><rPh sb=\"0\" eb=\"1\"><t>ネ</t>"
                        + "</rPh></si><si><r><rPr><b/></rPr><t>Жирний</t></r></si>"
                        + "<si><r><t>${nonsense}</t></r></si>")));

        TemplateFiller.Filled filled = fill(template, new Values().single("period.year",
                TemplateValue.of(ReportTemplateFieldType.INT, 2025L)));

        assertThat(cellText(filled, "A1")).isEqualTo("Рік 2025");
        assertThat(part(filled, SHEET)).contains("<c r=\"B1\" t=\"s\"><v>1</v></c>")
                .contains("<c r=\"C1\" t=\"s\"><v>2</v></c>");
        assertThat(codes(filled.warnings())).containsExactly("RICH_TEXT_FLATTENED Statement A1 null null",
                "UNKNOWN_PLACEHOLDER Statement C1 nonsense null");
    }

    @Test
    void inlineStringsAreFilledTooTheirRunsJoined() {
        byte[] template = TemplateFiles.withText(TemplateFiles.workbook(sheet -> sheet.value(0, 0, "x")), SHEET,
                sheet -> sheet.replace("<c r=\"A1\" t=\"s\"><v>0</v></c>",
                        "<c r=\"A1\" t=\"inlineStr\"><is><t>${period.year}</t></is></c>"
                                + "<c r=\"B1\" t=\"inlineStr\"><is><r><t>Рік: </t></r><r><t>${period.year}</t></r>"
                                + "<rPh><t>x</t></rPh></is></c><c r=\"C1\" t=\"inlineStr\"><is><r><t>без</t></r>"
                                + "<r></r></is></c><c r=\"D1\" t=\"inlineStr\"/><c r=\"E1\" t=\"s\"/>"));

        TemplateFiller.Filled filled = fill(template, new Values().single("period.year",
                TemplateValue.of(ReportTemplateFieldType.INT, 2025L)));

        assertThat(part(filled, SHEET)).contains("<c r=\"A1\"><v>2025</v></c>");
        assertThat(cellText(filled, "B1")).isEqualTo("Рік: 2025");
        assertThat(codes(filled.warnings())).containsExactly("RICH_TEXT_FLATTENED Statement B1 null null");
    }

    // ---- the repeating row

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 300})
    void theRepeatingRowIsCopiedOncePerRowAndTheTotalsBelowSumEveryCopy(int count) {
        List<Map<String, TemplateValue>> plots = plots(count);

        TemplateFiller.Filled filled = fill(SampleTemplates.landTaxStatement(), landTaxValues(plots));

        assertThat(filled.rows()).isEqualTo(count);
        String sheet = part(filled, SHEET);
        int last = 4 + count;
        String sum = count == 0 ? "SUM(#REF!)" : "SUM(D5:D" + last + ")";
        int totalsRow = count == 0 ? 5 : last + 1;
        assertThat(sheet).contains("<row r=\"" + totalsRow + "\"><c r=\"A" + totalsRow + "\" s=\"")
                .contains("<f>" + sum + "</f>")
                .doesNotContain("${rows.plots");
        assertThat(rowNumbers(sheet)).isEqualTo(IntStream.rangeClosed(1, totalsRow).boxed().toList());
        // every copy keeps the template row's styles
        List<String> styles = styles(part(fill(SampleTemplates.landTaxStatement(), landTaxValues(plots(1))), SHEET),
                5);
        for (int row = 5; row <= last; row++) {
            assertThat(styles(sheet, row)).isEqualTo(styles);
        }
        try (ReadableWorkbook workbook = open(filled)) {
            List<Row> rows = workbook.getFirstSheet().read();
            for (int index = 0; index < count; index++) {
                Row row = rows.get(4 + index);
                assertThat(row.getCellAsString(0)).contains(Integer.toString(index + 1));
                assertThat(row.getCellAsNumber(1)).contains(new BigDecimal("600.00"));
                assertThat(row.getCellAsNumber(3)).contains(new BigDecimal(index + ".50"));
            }
            assertThat(rows.get(totalsRow - 1).getCellAsString(0)).contains("Разом");
            assertThat(rows.get(totalsRow - 1).getCellAsNumber(4)).contains(new BigDecimal("420.00"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void referencesAroundTheRepeatingRowFollowItEverywhere() {
        byte[] template = TemplateFiles.withText(SampleTemplates.landTaxStatement(), SHEET, sheet -> sheet
                .replace("</row><row r=\"6\">", "<c r=\"F5\"><f>D5*$B$3</f><v>1</v></c><c r=\"G5\">"
                        + "<f>SUM(D$5:D5)</f></c></row><row r=\"6\">")
                .replace("<mergeCells>", "<mergeCells count=\"3\">\n")
                .replace("<sheetData>", "<!-- kept --><?nadel kept?><sheetData>")
                .replace("<mergeCell ref=\"A1:E1\"/>", "<mergeCell ref=\"A1:E1\"/><mergeCell ref=\"B5:C5\"/>"
                        + "<mergeCell ref=\"A6:C6\"/>")
                .replace("</mergeCells>", "</mergeCells><conditionalFormatting sqref=\"D5 A1\"><cfRule type=\"expression\""
                        + " priority=\"1\"><formula>D5&gt;$D$6</formula></cfRule></conditionalFormatting>"
                        + "<conditionalFormatting sqref=\"E6\"><cfRule type=\"cellIs\" priority=\"2\">"
                        + "<formula>D6</formula></cfRule></conditionalFormatting>"
                        + "<dataValidations count=\"1\"><dataValidation type=\"decimal\" sqref=\"B5:C5\">"
                        + "<formula1>0</formula1><formula2>$B$6</formula2></dataValidation></dataValidations>"
                        + "<hyperlinks><hyperlink ref=\"A5\" location=\"'Відомість'!A6\"/>"
                        + "<hyperlink ref=\"A6\" location=\"Відомість!E6\"/></hyperlinks>")
                .replace("<dimension ref=\"A1\"/>", "<dimension ref=\"A1:G6\"/>")
                .replace("<pageMargins", "<autoFilter ref=\"A4:E6\"/><pageMargins")
                .replace("</headerFooter>", "</headerFooter><rowBreaks count=\"2\" manualBreakCount=\"2\">"
                        + "<brk id=\"3\" max=\"16383\" man=\"1\"/>\n<brk id=\"5\" max=\"16383\" man=\"1\"/></rowBreaks>"));
        template = TemplateFiles.withText(template, TemplateFiles.WORKBOOK, workbook -> workbook.replace(
                "<definedNames></definedNames>", "<definedNames><definedName name=\"_xlnm.Print_Area\" "
                        + "localSheetId=\"0\">'Відомість'!$A$1:$E$6</definedName><definedName name=\"Rate\">"
                        + "Відомість!$C$5</definedName></definedNames>"));

        TemplateFiller.Filled filled = fill(template, landTaxValues(plots(3)));

        String sheet = part(filled, SHEET);
        assertThat(sheet)
                // fill-down inside the copies, the cached value dropped
                .contains("<c r=\"F5\"><f>D5*$B$3</f></c><c r=\"G5\"><f>SUM(D$5:D5)</f></c>")
                .contains("<c r=\"F7\"><f>D7*$B$3</f></c><c r=\"G7\"><f>SUM(D$5:D7)</f></c>")
                .contains("<f>SUM(D5:D7)</f>")
                .contains("<dimension ref=\"A1:G8\"></dimension>")
                .contains("<mergeCells count=\"5\"><mergeCell ref=\"A1:E1\"></mergeCell>"
                        + "<mergeCell ref=\"B5:C5\"></mergeCell>"
                        + "<mergeCell ref=\"B6:C6\"></mergeCell><mergeCell ref=\"B7:C7\"></mergeCell>"
                        + "<mergeCell ref=\"A8:C8\"></mergeCell>")
                .contains("<conditionalFormatting sqref=\"D5:D7 A1\"><cfRule type=\"expression\" priority=\"1\">"
                        + "<formula>D5&gt;$D$8</formula></cfRule></conditionalFormatting>")
                .contains("<conditionalFormatting sqref=\"E8\"><cfRule type=\"cellIs\" priority=\"2\">"
                        + "<formula>D8</formula></cfRule></conditionalFormatting>")
                .contains("<dataValidations count=\"1\"><dataValidation type=\"decimal\" sqref=\"B5:C7\">"
                        + "<formula1>0</formula1><formula2>$B$8</formula2></dataValidation></dataValidations>")
                .contains("<hyperlink ref=\"A5\" location=\"'Відомість'!A8\"></hyperlink>"
                        + "<hyperlink ref=\"A6\" location=\"'Відомість'!A8\"></hyperlink>"
                        + "<hyperlink ref=\"A7\" location=\"'Відомість'!A8\"></hyperlink>"
                        + "<hyperlink ref=\"A8\" location=\"Відомість!E8\"></hyperlink>")
                .contains("<autoFilter ref=\"A4:E8\"></autoFilter>")
                .contains("<brk id=\"3\" max=\"16383\" man=\"1\"></brk>\n<brk id=\"7\" max=\"16383\" man=\"1\"></brk>")
                .contains("<!-- kept --><?nadel kept?><sheetData>");
        assertThat(part(filled, TemplateFiles.WORKBOOK))
                .contains("'Відомість'!$A$1:$E$8</definedName>")
                .contains("Відомість!$C$5</definedName>");
        open(filled);
    }

    @Test
    void withAnEmptySetTheRepeatingRowAndWhatLiesOnlyOnItAreRemoved() {
        byte[] template = TemplateFiles.withText(SampleTemplates.landTaxStatement(), SHEET, sheet -> sheet
                .replace("<mergeCell ref=\"A1:E1\"/>", "<mergeCell ref=\"A1:E1\"/><mergeCell ref=\"B5:C5\"/>")
                .replace("</mergeCells>", "</mergeCells><conditionalFormatting sqref=\"D5\"><cfRule type=\"expression\""
                        + " priority=\"1\"><formula>D5&gt;0</formula></cfRule></conditionalFormatting>"
                        + "<dataValidations count=\"1\"><dataValidation sqref=\"B5\"/></dataValidations>"
                        + "<hyperlinks><hyperlink ref=\"A5\" location=\"A1\"/></hyperlinks>")
                .replace("<dimension ref=\"A1\"/>", "<dimension ref=\"A5\"/>")
                .replace("</headerFooter>", "</headerFooter><rowBreaks count=\"2\"><brk id=\"6\"/><brk id=\"x\"/>"
                        + "</rowBreaks>"));

        TemplateFiller.Filled filled = fill(template, landTaxValues(List.of()));

        String sheet = part(filled, SHEET);
        assertThat(sheet).doesNotContain("<row r=\"6\"").doesNotContain("conditionalFormatting")
                .doesNotContain("dataValidation").doesNotContain("hyperlink").doesNotContain("<dimension")
                .contains("<mergeCells><mergeCell ref=\"A1:E1\"></mergeCell></mergeCells>")
                .contains("<brk id=\"5\"></brk><brk id=\"x\"></brk>")
                .contains("<row r=\"5\"><c r=\"A5\"");
        open(filled);
    }

    @Test
    void sharedFormulasAreExpandedAndArrayFormulasOnTheRowAreCopied() {
        byte[] template = TemplateFiles.withInlineRows(
                new String[] {"${rows.plots.number}", "x", "y"},
                new String[] {"Разом", "", ""});
        template = TemplateFiles.withText(template, SHEET, sheet -> sheet
                .replace("<c r=\"B1\" t=\"inlineStr\"><is><t>x</t></is></c>",
                        "<c r=\"B1\"><f t=\"shared\" ref=\"B1:B2\" si=\"0\">A1*2</f><v>2</v></c>")
                .replace("<c r=\"B2\" t=\"inlineStr\"><is><t></t></is></c>", "<c r=\"B2\"><f t=\"shared\" si=\"0\"/>"
                        + "<v>4</v></c>")
                .replace("<c r=\"C1\" t=\"inlineStr\"><is><t>y</t></is></c>",
                        "<c r=\"C1\"><f t=\"array\" ref=\"C1\">A1:A1*1</f></c>")
                .replace("<c r=\"C2\" t=\"inlineStr\"><is><t></t></is></c>", "<c r=\"C2\"><f t=\"shared\" si=\"9\"/>"
                        + "</c>"));

        TemplateFiller.Filled filled = fill(template, new Values().rows(TemplateRowSet.PLOTS, plots(2)));

        String sheet = part(filled, SHEET);
        assertThat(sheet).contains("<c r=\"B1\"><f>A1*2</f></c>").contains("<c r=\"B2\"><f>A2*2</f></c>")
                .contains("<c r=\"B3\"><f>A3*2</f></c>")
                .contains("<c r=\"C1\"><f t=\"array\" ref=\"C1\">A1:A1*1</f></c>")
                .contains("<c r=\"C2\"><f t=\"array\" ref=\"C2\">A2:A2*1</f></c>")
                // a shared formula whose master is missing is left alone
                .contains("<c r=\"C3\"><f t=\"shared\" si=\"9\"></f></c>");
    }

    @Test
    void formulasOnOtherSheetsAndSharedFormulasNamingTheRepeatedSheetFollowItsRows() {
        byte[] template = TemplateFiles.withPart(SampleTemplates.landTaxStatement(), "xl/worksheets/sheet2.xml",
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"" + XmlTree.MAIN + "\"><sheetData>"
                        + "<row r=\"1\"><c r=\"A1\"><f>'Відомість'!D6+A2</f><v>1</v></c>"
                        + "<c r=\"B1\"><f t=\"shared\" ref=\"B1:C1\" si=\"0\">Відомість!B6</f></c>"
                        + "<c r=\"C1\"><f t=\"shared\" si=\"0\"/></c>"
                        + "<c r=\"D1\"><f t=\"shared\" ref=\"D1:E1\" si=\"1\">A1</f></c>"
                        + "<c r=\"E1\"><f t=\"shared\" si=\"1\"/></c></row></sheetData></worksheet>")
                        .getBytes(StandardCharsets.UTF_8));
        template = withSecondSheet(template, "Підсумок");

        TemplateFiller.Filled filled = fill(template, landTaxValues(plots(4)));

        String other = part(filled, "xl/worksheets/sheet2.xml");
        assertThat(other).contains("<c r=\"A1\"><f>'Відомість'!D9+A2</f></c>")
                .contains("<c r=\"B1\"><f>Відомість!B9</f></c>").contains("<c r=\"C1\"><f>Відомість!C9</f></c>")
                // a group that does not name the repeated sheet stays shared
                .contains("<c r=\"D1\"><f t=\"shared\" ref=\"D1:E1\" si=\"1\">A1</f></c>")
                .contains("<c r=\"E1\"><f t=\"shared\" si=\"1\"></f></c>");
    }

    @Test
    void drawingAnchorsAndTablesBelowTheRowMoveAndChartsAreListedNotShifted() {
        String drawing = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats."
                + "org/drawingml/2006/spreadsheetDrawing\"><xdr:twoCellAnchor><xdr:from><xdr:col>0</xdr:col>"
                + "<xdr:row>1</xdr:row></xdr:from><xdr:to><xdr:col>2</xdr:col><xdr:row>7</xdr:row></xdr:to>"
                + "<xdr:clientData/></xdr:twoCellAnchor><xdr:oneCellAnchor><xdr:from><xdr:col>0</xdr:col>"
                + "<xdr:row>4</xdr:row></xdr:from><xdr:clientData/></xdr:oneCellAnchor></xdr:wsDr>";
        String table = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><table xmlns=\"" + XmlTree.MAIN + "\" id=\"1\" "
                + "name=\"T\" displayName=\"T\" ref=\"A4:E6\"><autoFilter ref=\"A4:E6\"/><tableColumns count=\"1\">"
                + "<tableColumn id=\"1\" name=\"A\"/></tableColumns></table>";
        String chart = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><c:chartSpace xmlns:c=\"http://schemas.openxmlformats"
                + ".org/drawingml/2006/chart\"><c:chart><c:f>'Відомість'!$D$5:$D$5</c:f></c:chart></c:chartSpace>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/"
                + "package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/"
                + "officeDocument/2006/relationships/drawing\" Target=\"../drawings/drawing1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                + "relationships/table\" Target=\"../tables/table1.xml\"/><Relationship Id=\"rId3\" Type=\"http://"
                + "schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"https://x.test\" "
                + "TargetMode=\"External\"/></Relationships>";
        byte[] template = SampleTemplates.landTaxStatement();
        template = TemplateFiles.withPart(template, "xl/drawings/drawing1.xml", drawing.getBytes(StandardCharsets.UTF_8));
        template = TemplateFiles.withPart(template, "xl/tables/table1.xml", table.getBytes(StandardCharsets.UTF_8));
        template = TemplateFiles.withPart(template, "xl/charts/chart1.xml", chart.getBytes(StandardCharsets.UTF_8));
        template = TemplateFiles.withPart(template, "xl/worksheets/_rels/sheet1.xml.rels",
                rels.getBytes(StandardCharsets.UTF_8));

        TemplateFiller.Filled filled = fill(template, landTaxValues(plots(3)));

        assertThat(part(filled, "xl/drawings/drawing1.xml"))
                .contains("<xdr:from><xdr:col>0</xdr:col><xdr:row>1</xdr:row></xdr:from>")
                .contains("<xdr:to><xdr:col>2</xdr:col><xdr:row>9</xdr:row></xdr:to>")
                .contains("<xdr:from><xdr:col>0</xdr:col><xdr:row>4</xdr:row></xdr:from>");
        assertThat(part(filled, "xl/tables/table1.xml")).contains("ref=\"A4:E8\"><autoFilter ref=\"A4:E8\">");
        assertThat(part(filled, "xl/charts/chart1.xml")).isEqualTo(chart);
        assertThat(codes(filled.warnings())).containsExactly("CHART_REFS_NOT_SHIFTED Відомість null null null");
    }

    @Test
    void twoSheetsWithRepeatingRowsReferToEachOthersMovedRows() {
        byte[] months = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"" + XmlTree.MAIN + "\">"
                + "<sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Місяць</t></is></c></row>"
                + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>${rows.months.month}</t></is></c>"
                + "<c r=\"B2\"><f>'Відомість'!D6+B1</f></c></row>"
                + "<row r=\"3\"><c r=\"B3\"><f>SUM(B2:B2)</f></c></row></sheetData></worksheet>")
                .getBytes(StandardCharsets.UTF_8);
        byte[] template = TemplateFiles.withText(SampleTemplates.landTaxStatement(), SHEET, sheet -> sheet.replace(
                "</row></sheetData>", "<c r=\"F6\"><f>Місяці!B3</f></c></row></sheetData>"));
        template = withSecondSheet(TemplateFiles.withPart(template, "xl/worksheets/sheet2.xml", months), "Місяці");
        Map<String, TemplateValue> january = Map.of("rows.months.month",
                TemplateValue.of(ReportTemplateFieldType.MONTH, YearMonth.of(2025, 1)));
        Map<String, TemplateValue> february = Map.of("rows.months.month",
                TemplateValue.of(ReportTemplateFieldType.MONTH, YearMonth.of(2025, 2)));

        TemplateFiller.Filled filled = fill(template,
                landTaxValues(plots(3)).rows(TemplateRowSet.MONTHS, List.of(january, february)));

        assertThat(filled.rows()).isEqualTo(5);
        assertThat(part(filled, SHEET)).contains("<c r=\"F8\"><f>Місяці!B4</f></c>");
        assertThat(part(filled, "xl/worksheets/sheet2.xml"))
                // 01.01.2025 and 01.02.2025 as date serials
                .contains("<c r=\"A2\"><v>45658</v></c><c r=\"B2\"><f>'Відомість'!D8+B1</f></c>")
                .contains("<c r=\"A3\"><v>45689</v></c><c r=\"B3\"><f>'Відомість'!D9+B2</f></c>")
                .contains("<c r=\"B4\"><f>SUM(B2:B3)</f></c>");
    }

    // ---- no formula is evaluated

    @Test
    void theCalculationChainIsRemovedAndExcelRecalculatesOnOpening() {
        byte[] template = TemplateFiles.withPart(SampleTemplates.landTaxStatement(), "xl/calcChain.xml",
                "<?xml version=\"1.0\"?><calcChain xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                        .concat("<c r=\"D6\" i=\"1\"/></calcChain>").getBytes(StandardCharsets.UTF_8));
        template = TemplateFiles.withText(template, TemplateFiles.WORKBOOK_RELS, rels -> rels.replace(
                "</Relationships>", "<Relationship Id=\"rIdCalc\" Type=\"http://schemas.openxmlformats.org/"
                        + "officeDocument/2006/relationships/calcChain\" Target=\"calcChain.xml\"/></Relationships>"));
        template = TemplateFiles.withText(template, TemplateFiles.CONTENT_TYPES, types -> types.replace("</Types>",
                "<Override PartName=\"/xl/calcChain.xml\" ContentType=\"application/vnd.openxmlformats-"
                        + "officedocument.spreadsheetml.calcChain+xml\"/></Types>"));

        TemplateFiller.Filled filled = fill(template, landTaxValues(plots(2)));

        Map<String, byte[]> parts = TemplateFiles.parts(filled.content());
        assertThat(parts).doesNotContainKey("xl/calcChain.xml");
        assertThat(part(filled, TemplateFiles.WORKBOOK_RELS)).doesNotContain("calcChain");
        assertThat(part(filled, TemplateFiles.CONTENT_TYPES)).doesNotContain("calcChain");
        assertThat(part(filled, TemplateFiles.WORKBOOK)).contains("<calcPr fullCalcOnLoad=\"1\"></calcPr></workbook>");
        assertThat(part(filled, SHEET)).doesNotContainPattern("<f>[^<]*</f><v>");

        // an existing calcPr keeps its attributes; one missing before later elements is put in its place
        byte[] withCalc = TemplateFiles.withText(template, TemplateFiles.WORKBOOK, workbook -> workbook.replace(
                "</definedNames>", "</definedNames><calcPr calcId=\"191029\" fullCalcOnLoad=\"0\"/>"));
        assertThat(part(fill(withCalc, landTaxValues(plots(1))), TemplateFiles.WORKBOOK))
                .contains("<calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"></calcPr>");
        byte[] withExt = TemplateFiles.withText(template, TemplateFiles.WORKBOOK, workbook -> workbook.replace(
                "</definedNames>", "</definedNames><extLst></extLst>"));
        assertThat(part(fill(withExt, landTaxValues(plots(1))), TemplateFiles.WORKBOOK))
                .contains("<calcPr fullCalcOnLoad=\"1\"></calcPr><extLst></extLst>");
    }

    // ---- warnings sheet, document properties

    @Test
    void theWarningsSheetListsEveryWarningInTheFilesLanguageUnderAUniqueName() {
        byte[] template = TemplateFiles.workbook(sheet -> {
            sheet.value(0, 0, "${nonsense}");
            sheet.value(0, 1, "${cooperative.iban}");
        });
        Values values = new Values().single("cooperative.iban", TemplateValue.unknown(ReportTemplateValueReason.NOT_SET));

        TemplateFiller.Filled uk = fill(template, values, UK);

        assertThat(part(uk, TemplateFiles.WORKBOOK)).contains("<sheet xmlns:r=\"http://schemas.openxmlformats.org/"
                + "officeDocument/2006/relationships\" name=\"Попередження\" sheetId=\"2\" r:id=\"rIdNadel1\">");
        assertThat(part(uk, TemplateFiles.WORKBOOK_RELS)).contains("<Relationship Id=\"rIdNadel1\" "
                + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" "
                + "Target=\"worksheets/sheet2.xml\">");
        assertThat(part(uk, TemplateFiles.CONTENT_TYPES)).contains("<Override PartName=\"/xl/worksheets/sheet2.xml\"");
        try (ReadableWorkbook workbook = open(uk)) {
            List<Row> rows = workbook.getSheets().toList().get(1).read();
            assertThat(rows.get(0).getCellAsString(0)).contains("Аркуш");
            assertThat(rows).hasSize(3);
            assertThat(rows.get(1).getCellAsString(0)).contains("Statement");
            assertThat(rows.get(1).getCellAsString(1)).contains("A1");
            assertThat(rows.get(1).getCellAsString(2)).contains("nonsense");
            assertThat(rows.get(1).getCellAsString(3)).contains(UK.text("reportTemplates.warnings.UNKNOWN_PLACEHOLDER"));
            assertThat(rows.get(1).getCellText(4)).isEmpty();
            assertThat(rows.get(2).getCellAsString(4)).contains(UK.text("reportTemplates.valueReasons.NOT_SET"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        // a template that already has a sheet of the name and a relationship of the id
        byte[] taken = TemplateFiles.withText(template, TemplateFiles.WORKBOOK, workbook -> workbook.replace(
                "name=\"Statement\"", "name=\"WARNINGS\""));
        taken = TemplateFiles.withText(taken, TemplateFiles.WORKBOOK_RELS, rels -> rels.replace("</Relationships>",
                "<Relationship Id=\"rIdNadel1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                        + "relationships/customXml\" Target=\"../customXml/item1.xml\"/></Relationships>"));
        TemplateFiller.Filled en = fill(taken, values, EN);
        assertThat(part(en, TemplateFiles.WORKBOOK)).contains("name=\"Warnings (2)\" sheetId=\"2\" r:id=\"rIdNadel2\"");
        // no warning, no sheet
        assertThat(part(fill(SampleTemplates.landTaxStatement(), landTaxValues(plots(1))), TemplateFiles.WORKBOOK))
                .doesNotContain("Попередження");
    }

    @Test
    void theDocumentPropertiesNameTheTemplateAndItsVersion() {
        byte[] template = TemplateFiles.withText(SampleTemplates.landTaxStatement(), "docProps/core.xml",
                core -> core.replace("<dc:creator>", "<dc:title>Old</dc:title><cp:version>9</cp:version><dc:creator>"));

        String core = part(filler.fill(template, "Відомість & податок", 3, landTaxValues(plots(1)), UK, 20_000),
                "docProps/core.xml");

        assertThat(core).contains("<dc:creator>Nadel</dc:creator>").doesNotContain("Old").doesNotContain(">9<")
                .contains("<dc:title xmlns:dc=\"http://purl.org/dc/elements/1.1/\">Відомість &amp; податок</dc:title>")
                .contains(">3</cp:version>");

        // a template without the part gets one, with its relationship and content type
        Map<String, byte[]> parts = TemplateFiles.parts(SampleTemplates.landTaxStatement());
        parts.remove("docProps/core.xml");
        parts.put("_rels/.rels", TemplateFiles.text(parts.get("_rels/.rels")).replaceFirst(
                "<Relationship Id=\"rId2\"[^>]*/>", "").getBytes(StandardCharsets.UTF_8));
        TemplateFiller.Filled added = filler.fill(TemplateFiles.zip(parts), "Відомість", 1,
                landTaxValues(plots(1)), UK, 20_000);
        assertThat(part(added, "docProps/core.xml")).contains(">Відомість</dc:title>").contains(">1</cp:version>");
        assertThat(part(added, "_rels/.rels")).contains("Id=\"rIdNadel1\" Type=\"http://schemas.openxmlformats.org/"
                + "package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"");
        assertThat(part(added, TemplateFiles.CONTENT_TYPES)).containsOnlyOnce("/docProps/core.xml");
        open(added);
        parts.put(TemplateFiles.CONTENT_TYPES, TemplateFiles.text(parts.get(TemplateFiles.CONTENT_TYPES)).replaceFirst(
                "<Override PartName=\"/docProps/core.xml\"[^>]*/>", "").getBytes(StandardCharsets.UTF_8));
        TemplateFiller.Filled typed = filler.fill(TemplateFiles.zip(parts), "Відомість", 1, landTaxValues(plots(1)),
                UK, 20_000);
        assertThat(part(typed, TemplateFiles.CONTENT_TYPES)).containsOnlyOnce("<Override PartName=\"/docProps/core.xml\" "
                + "ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\">");
    }

    // ---- characters XML forbids

    /** Vertical tab, NUL, U+FFFE, U+FFFF, a lone high and a lone low surrogate, and a valid pair (U+1F33B). */
    private static final String DIRTY = "a\u000Bb\u0000c\uFFFEd\uFFFFe\uD800f\uDC00g\uD83C\uDF3Bh";
    private static final String CLEANED = "a b c d e f g\uD83C\uDF3Bh";

    @Test
    void charactersXmlForbidsInValuesAndTheTemplateNameBecomeSpacesAndTheFileStillOpens() {
        byte[] template = TemplateFiles.workbook(sheet -> {
            sheet.value(0, 0, "${cooperative.name}");
            sheet.value(0, 1, "Адреса: ${cooperative.address}, банк ${cooperative.bankName}");
            sheet.value(0, 2, "${cooperative.officialName}");
            sheet.value(0, 3, "${generatedBy}");
            sheet.value(0, 4, "${nonsense}"); // a warning, so the file gets the warnings sheet too
            sheet.value(1, 0, "${rows.plots.number}");
            sheet.value(1, 1, "вул. ${rows.plots.street}");
        });
        Map<String, TemplateValue> plot = new LinkedHashMap<>();
        plot.put("rows.plots.number", text(DIRTY));
        plot.put("rows.plots.street", text(DIRTY));
        Values values = new Values().rows(TemplateRowSet.PLOTS, List.of(plot))
                .single("cooperative.name", text(DIRTY))
                .single("cooperative.address", text(DIRTY))
                .single("cooperative.bankName", text(DIRTY))
                .single("cooperative.officialName", text(DIRTY))
                .single("generatedBy", text(DIRTY));

        TemplateFiller.Filled filled = filler.fill(template, "Шаблон " + DIRTY, 1, values, UK, 20_000);

        // every XML part of the file is well-formed XML 1.0
        TemplateFiles.parts(filled.content()).forEach((name, content) -> {
            if (name.endsWith(".xml") || name.endsWith(".rels")) {
                assertWellFormed(name, content);
            }
        });
        assertThat(part(filled, "docProps/core.xml")).contains(">Шаблон " + CLEANED + "</dc:title>");
        assertThat(codes(filled.warnings())).containsExactly("UNKNOWN_PLACEHOLDER Statement E1 nonsense null");
        try (ReadableWorkbook workbook = open(filled)) {
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(rows.get(0).getCellAsString(0)).contains(CLEANED);
            assertThat(rows.get(0).getCellAsString(1)).contains("Адреса: " + CLEANED + ", банк " + CLEANED);
            assertThat(rows.get(0).getCellAsString(2)).contains(CLEANED);
            assertThat(rows.get(0).getCellAsString(3)).contains(CLEANED);
            assertThat(rows.get(1).getCellAsString(0)).contains(CLEANED);
            assertThat(rows.get(1).getCellAsString(1)).contains("вул. " + CLEANED);
            assertThat(workbook.getSheets().toList().get(1).read().get(1).getCellAsString(2)).contains("nonsense");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void assertWellFormed(String name, byte[] content) {
        try {
            XMLStreamReader reader = XMLInputFactory.newFactory().createXMLStreamReader(
                    new ByteArrayInputStream(content));
            while (reader.hasNext()) {
                reader.next();
            }
        } catch (XMLStreamException e) {
            throw new AssertionError(name + " is not well-formed XML: " + e.getMessage(), e);
        }
    }

    // ---- refusals and limits

    @Test
    void theUploadChecksRunAgainBeforeAFill() {
        assertInvalid(() -> fill(TemplateFiles.withDoctype(), new Values()), "BROKEN_ZIP");
        assertInvalid(() -> fill(TemplateFiles.withBomb(30 * TemplateFiles.MB), new Values()), "UNPACKED_TOO_LARGE");
        assertInvalid(() -> fill(TemplateFiles.withVbaProject(), new Values()), "MACROS");
        assertInvalid(() -> fill(TemplateFiles.text(), new Values()), "NOT_XLSX");
    }

    @Test
    void aSheetWithoutANameOrARepeatingRowOutsideTheSheetIsRefusedAndAChartSheetIsCopied() {
        byte[] unnamed = TemplateFiles.withText(SampleTemplates.landTaxStatement(), TemplateFiles.WORKBOOK,
                workbook -> workbook.replaceFirst("<sheet name=\"[^\"]*\"", "<sheet name=\"\""));
        assertInvalid(() -> fill(unnamed, landTaxValues(plots(1))), "BROKEN_ZIP");
        for (String row : List.of("0", "1048577")) {
            byte[] outside = TemplateFiles.withInlineRows(new String[] {"${rows.plots.number}"});
            byte[] moved = TemplateFiles.withText(outside, SHEET, sheet -> sheet.replace("<row r=\"1\">",
                    "<row r=\"" + row + "\">"));
            assertInvalid(() -> fill(moved, landTaxValues(plots(1))), "BROKEN_ZIP");
        }

        byte[] chartSheet = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><chartsheet xmlns=\"" + XmlTree.MAIN
                + "\"><sheetViews><sheetView workbookViewId=\"0\"/></sheetViews></chartsheet>")
                .getBytes(StandardCharsets.UTF_8);
        byte[] template = TemplateFiles.withPart(SampleTemplates.landTaxStatement(), "xl/chartsheets/sheet1.xml",
                chartSheet);
        template = TemplateFiles.withText(template, TemplateFiles.WORKBOOK, text -> text.replace("</sheets>",
                "<sheet name=\"Діаграма\" r:id=\"rIdChart\" sheetId=\"2\"/><sheet name=\"Зламаний\" "
                        + "r:id=\"rIdNone\" sheetId=\"3\"/></sheets>"));
        template = TemplateFiles.withText(template, TemplateFiles.WORKBOOK_RELS, rels -> rels.replace(
                "</Relationships>", "<Relationship Id=\"rIdChart\" Type=\"http://schemas.openxmlformats.org/"
                        + "officeDocument/2006/relationships/chartsheet\" Target=\"chartsheets/sheet1.xml\"/>"
                        + "</Relationships>"));
        TemplateFiller.Filled filled = fill(template, landTaxValues(plots(2)));
        assertThat(TemplateFiles.parts(filled.content()).get("xl/chartsheets/sheet1.xml")).isEqualTo(chartSheet);
        assertThat(part(filled, SHEET)).contains("<f>SUM(D5:D6)</f>");
    }

    @Test
    void moreRowsThanTheCapInAllAndAnOutputTooLargeToUnpackAreRefused() {
        assertThatThrownBy(() -> filler.fill(SampleTemplates.landTaxStatement(), "T", 1, landTaxValues(plots(6)), UK,
                5))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("REPORT_TOO_LARGE");
                });
        TemplateFiller small = new TemplateFiller(64 * 1024);
        assertThatThrownBy(() -> small.fill(SampleTemplates.landTaxStatement(), "T", 1, landTaxValues(plots(2000)),
                UK, 20_000))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("REPORT_TOO_LARGE"));
    }

    @Test
    void aHostileTemplateWithAHugeAttributeListNeverOverflowsTheStack() {
        // 100 KB of attributes on the repeating row and on a cell of it: filled, every attribute kept
        String attributes = IntStream.range(0, 100).mapToObj(i -> " a" + i + "=\"" + "x".repeat(1024) + "\"")
                .reduce("", String::concat);
        byte[] wide = TemplateFiles.withText(SampleTemplates.landTaxStatement(), SHEET, sheet -> sheet
                .replace("<row r=\"5\">", "<row r=\"5\"" + attributes + ">")
                .replace("<c r=\"A5\"", "<c r=\"A5\"" + attributes));

        TemplateFiller.Filled filled = fill(wide, landTaxValues(plots(3)));

        assertThat(part(filled, SHEET)).contains("<row r=\"7\" a0=\"x").contains("<c r=\"A7\" a0=\"x");

        // 100 KB of short attributes: past the parser's limit of attributes per element, refused, no stack overflow
        StringBuilder many = new StringBuilder();
        for (int i = 0; many.length() < 100 * 1024; i++) {
            many.append(" a").append(i).append("=\"").append(i).append('"');
        }
        byte[] hostile = TemplateFiles.withText(SampleTemplates.landTaxStatement(), SHEET,
                sheet -> sheet.replace("<c r=\"A5\"", "<c r=\"A5\"" + many));
        assertInvalid(() -> fill(hostile, landTaxValues(plots(3))), "BROKEN_ZIP");
    }

    @Test
    void twentyThousandRowsOfTwentyColumnsAreFilledInUnderFiveSeconds() {
        String[] row = new String[20];
        List<Map<String, TemplateValue>> plots = new ArrayList<>();
        for (int column = 0; column < row.length; column++) {
            row[column] = column % 2 == 0 ? "${rows.plots.number}" : "${rows.plots.debt.total}";
        }
        for (int index = 0; index < 20_000; index++) {
            Map<String, TemplateValue> plot = new HashMap<>();
            plot.put("rows.plots.number", text(Integer.toString(index)));
            plot.put("rows.plots.debt.total", money(index + ".25"));
            plots.add(plot);
        }
        byte[] template = TemplateFiles.withInlineRows(new String[] {"${cooperative.name}"}, row,
                new String[] {"Разом"});
        Values values = new Values().rows(TemplateRowSet.PLOTS, plots).single("cooperative.name", text("СТ"));
        fill(template, values); // warm up

        long started = System.nanoTime();
        TemplateFiller.Filled filled = fill(template, values);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(millis).as("filled in %d ms", millis).isLessThan(5_000);
        assertThat(filled.rows()).isEqualTo(20_000);
        try (ReadableWorkbook workbook = open(filled)) {
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(rows).hasSize(20_002);
            assertThat(rows.get(20_000).getCellAsNumber(19)).contains(new BigDecimal("19999.25"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theSampleLandTaxStatementIsWrittenForALook() throws IOException {
        Path folder = Path.of("target/sample-templates");
        SampleTemplates.main(new String[] {folder.toString()});
        try (ReadableWorkbook workbook = new ReadableWorkbook(
                Files.newInputStream(folder.resolve(SampleTemplates.LAND_TAX_STATEMENT)))) {
            assertThat(workbook.getFirstSheet().getName()).isEqualTo(SampleTemplates.LAND_TAX_SHEET);
        }
    }

    // ---- helpers

    /** Values given by the test: single values by key, rows by set; any other known key is empty. */
    static final class Values implements TemplateFiller.Values {

        private final Map<String, TemplateValue> singles = new HashMap<>();
        private final Map<TemplateRowSet, List<Map<String, TemplateValue>>> rows = new EnumMap<>(TemplateRowSet.class);

        Values single(String key, TemplateValue value) {
            singles.put(key, value);
            return this;
        }

        Values rows(TemplateRowSet set, List<Map<String, TemplateValue>> setRows) {
            rows.put(set, setRows);
            return this;
        }

        @Override
        public Optional<Placeholder> placeholder(String key) {
            return TemplateField.resolve(key, List.of());
        }

        @Override
        public Optional<TemplateValue> single(String key) {
            return Optional.of(singles.getOrDefault(key, TemplateValue.EMPTY));
        }

        @Override
        public List<Map<String, TemplateValue>> rows(TemplateRowSet set) {
            return rows.getOrDefault(set, List.of());
        }
    }

    /** {@code count} plots: number {@code i + 1}, 600.00 m², rate 35.0000, expected {@code i.50}. */
    static List<Map<String, TemplateValue>> plots(int count) {
        List<Map<String, TemplateValue>> plots = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Map<String, TemplateValue> plot = new LinkedHashMap<>();
            plot.put("rows.plots.number", text(Integer.toString(index + 1)));
            plot.put("rows.plots.area", TemplateValue.of(ReportTemplateFieldType.NUMBER, new BigDecimal("600.00")));
            plot.put("rows.plots.landTax.rate", TemplateValue.of(ReportTemplateFieldType.NUMBER,
                    new BigDecimal("35.0000")));
            plot.put("rows.plots.landTax.expected", money(index + ".50"));
            plots.add(plot);
        }
        return plots;
    }

    static Values landTaxValues(List<Map<String, TemplateValue>> plots) {
        return new Values().rows(TemplateRowSet.PLOTS, plots)
                .single("cooperative.officialName", text("Садівниче товариство «Сад»"))
                .single("cooperative.edrpou", text("12345678"))
                .single("period.year", TemplateValue.of(ReportTemplateFieldType.INT, 2025L))
                .single("totals.landTax.expected", money("420.00"));
    }

    private TemplateFiller.Filled fill(byte[] template, Values values) {
        return fill(template, values, UK);
    }

    private TemplateFiller.Filled fill(byte[] template, Values values, ReportTexts texts) {
        return filler.fill(template, "Шаблон", 1, values, texts, 20_000);
    }

    static TemplateValue text(String value) {
        return TemplateValue.of(ReportTemplateFieldType.TEXT, value);
    }

    static TemplateValue money(String value) {
        return TemplateValue.of(ReportTemplateFieldType.MONEY, Money.parse(value));
    }

    static String part(TemplateFiller.Filled filled, String name) {
        return TemplateFiles.text(TemplateFiles.parts(filled.content()).get(name));
    }

    /** The text of an inline string cell. */
    private static String cellText(TemplateFiller.Filled filled, String cell) {
        Matcher text = Pattern.compile("<c r=\"" + cell + "\"[^>]*t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                + "([^<]*)</t>").matcher(part(filled, SHEET));
        assertThat(text.find()).as("inline string in %s", cell).isTrue();
        return text.group(1).replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
    }

    private static List<Integer> rowNumbers(String sheet) {
        Matcher row = Pattern.compile("<row r=\"(\\d+)\"").matcher(sheet);
        List<Integer> numbers = new ArrayList<>();
        while (row.find()) {
            numbers.add(Integer.parseInt(row.group(1)));
        }
        return numbers.stream().distinct().toList();
    }

    /** The style ids of the cells of a row, in order. */
    private static List<String> styles(String sheet, int row) {
        int start = sheet.indexOf("<row r=\"" + row + "\"");
        String content = sheet.substring(start, sheet.indexOf("</row>", start));
        Matcher style = Pattern.compile("<c r=\"([A-Z]+)\\d+\"(?: s=\"(\\d+)\")?").matcher(content);
        List<String> styles = new ArrayList<>();
        while (style.find()) {
            styles.add(style.group(1) + "=" + style.group(2));
        }
        return styles;
    }

    private static List<String> codes(List<ReportTemplateWarning> warnings) {
        return warnings.stream().map(warning -> warning.getCode() + " " + warning.getSheet() + " "
                + warning.getCell() + " " + warning.getPlaceholder() + " " + warning.getReason()).toList();
    }

    private static ReadableWorkbook open(TemplateFiller.Filled filled) {
        try {
            return new ReadableWorkbook(new ByteArrayInputStream(filled.content()));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** The template with a second sheet "{@code name}" at {@code xl/worksheets/sheet2.xml}. */
    static byte[] withSecondSheet(byte[] template, String name) {
        byte[] workbook = TemplateFiles.withText(template, TemplateFiles.WORKBOOK, text -> text.replace("</sheets>",
                "<sheet name=\"" + name + "\" r:id=\"rIdSecond\" sheetId=\"2\"/></sheets>"));
        workbook = TemplateFiles.withText(workbook, TemplateFiles.WORKBOOK_RELS, rels -> rels.replace(
                "</Relationships>", "<Relationship Id=\"rIdSecond\" Type=\"http://schemas.openxmlformats.org/"
                        + "officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
                        + "</Relationships>"));
        return TemplateFiles.withText(workbook, TemplateFiles.CONTENT_TYPES, types -> types.replace("</Types>",
                "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-"
                        + "officedocument.spreadsheetml.worksheet+xml\"/></Types>"));
    }

    private static void assertInvalid(ThrowingCallable fill, String reason) {
        assertThatThrownBy(fill).isInstanceOfSatisfying(ApiProblemException.class, e -> {
            assertThat(e.code()).isEqualTo("TEMPLATE_INVALID");
            assertThat(e.errors()).extracting(FieldProblem::code).containsExactly(reason);
        });
    }
}
