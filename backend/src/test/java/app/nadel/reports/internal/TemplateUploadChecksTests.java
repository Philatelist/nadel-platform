package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.xlsx.UnsafeWorkbookReason;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import nadel.contract.model.ReportTemplateWarningCode;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Every check of {@link TemplateUploadChecks} on files that differ from a valid template only in what the test is
 * about (see {@link TemplateFiles}), in the order the checks run, and the stripping of links to other workbooks.
 */
class TemplateUploadChecksTests {

    private final TemplateUploadChecks checks = new TemplateUploadChecks();

    // ---- accepted

    @Test
    void aValidTemplateIsStoredByteForByteWithItsDigest() {
        byte[] valid = TemplateFiles.valid();

        TemplateUploadChecks.Checked checked = checks.check("C:\\forms\\Відомість.xlsx", valid);

        assertThat(checked.fileName()).isEqualTo("Відомість.xlsx");
        assertThat(checked.content()).isSameAs(valid);
        assertThat(checked.sha256()).isEqualTo(TemplateUploadChecks.sha256(valid)).matches("[0-9a-f]{64}");
        assertThat(checked.warnings()).isEmpty();
    }

    @Test
    void theExtensionIsMatchedIgnoringCase() {
        assertThat(checks.check("STATEMENT.XLSX", TemplateFiles.valid()).fileName()).isEqualTo("STATEMENT.XLSX");
    }

    @Test
    void escapedCharactersInSharedStringsAreFine() {
        byte[] workbook = TemplateFiles.workbook(sheet -> sheet.value(0, 0, "A & B < C ${cooperative.name}"));

        assertThat(checks.check("a.xlsx", workbook).warnings()).isEmpty();
    }

    @Test
    void oneRepeatingRowPerSheetAndSingleValuesBesideItAreFine() {
        byte[] workbook = TemplateFiles.withInlineRows(
                new String[] {"${cooperative.name}", "${period.year}"},
                new String[] {"${rows.months.month}", "${rows.months.charged.total}"},
                new String[] {"${rows.unknown.x}", "${rows.foo.bar}"},
                new String[] {"Разом", "${totals.debt}"});

        assertThat(checks.check("a.xlsx", workbook).warnings()).isEmpty();
    }

    // ---- size, name and signature

    @Test
    void aFileOverTwoMegabytesIsTooLargeBeforeAnythingElse() {
        assertThatThrownBy(() -> checks.check("notes.txt", TemplateFiles.overTwoMegabytes()))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(e.code()).isEqualTo(ProblemCodes.TEMPLATE_TOO_LARGE);
                });
        assertThatThrownBy(() -> TemplateUploadChecks.requireSize(TemplateUploadChecks.MAX_FILE_BYTES + 1))
                .isInstanceOf(ApiProblemException.class);
        TemplateUploadChecks.requireSize(TemplateUploadChecks.MAX_FILE_BYTES);
    }

    @Test
    void aNameThatIsNotXlsxIsRefusedEvenWithAValidWorkbook() {
        for (String name : List.of("statement.xlsm", "statement.xls", "statement.xltx", "statement", "", ".xlsx.txt")) {
            assertInvalid(name, TemplateFiles.valid(), ProblemCodes.NOT_XLSX);
        }
        assertInvalid(null, TemplateFiles.valid(), ProblemCodes.NOT_XLSX);
    }

    @Test
    void aTextFileNamedXlsxIsRefusedByItsSignature() {
        assertInvalid("statement.xlsx", TemplateFiles.text(), ProblemCodes.NOT_XLSX);
        assertInvalid("statement.xlsx", new byte[] {'P', 'K'}, ProblemCodes.NOT_XLSX);
    }

    @Test
    void aZipThatIsNotAWorkbookIsNotXlsx() {
        byte[] zip = TemplateFiles.zip(Map.of("readme.txt", "hello".getBytes()));

        assertInvalid("statement.xlsx", zip, ProblemCodes.NOT_XLSX);
    }

    // ---- the guard's limits

    @Test
    void aCompressedBombIsUnpackedTooLarge() {
        byte[] bomb = TemplateFiles.withBomb(30 * TemplateFiles.MB);
        assertThat(bomb.length).isLessThan((int) TemplateUploadChecks.MAX_FILE_BYTES);

        assertInvalid("statement.xlsx", bomb, ProblemCodes.UNPACKED_TOO_LARGE);
    }

    @Test
    void tooManyPartsAreUnpackedTooLarge() {
        Map<String, byte[]> parts = TemplateFiles.parts(TemplateFiles.valid());
        for (int i = 0; i < TemplateUploadChecks.MAX_ENTRIES; i++) {
            parts.put("xl/media/image" + i + ".png", new byte[] {1});
        }

        assertInvalid("statement.xlsx", TemplateFiles.zip(parts), ProblemCodes.UNPACKED_TOO_LARGE);
    }

    @Test
    void aTruncatedArchiveIsABrokenZip() {
        byte[] valid = TemplateFiles.valid();
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length / 2);

        assertInvalid("statement.xlsx", truncated, ProblemCodes.BROKEN_ZIP);
    }

    @Test
    void everyReasonOfTheGuardHasItsCode() {
        assertThat(TemplateUploadChecks.reasonCode(UnsafeWorkbookReason.WRONG_EXTENSION)).isEqualTo("NOT_XLSX");
        assertThat(TemplateUploadChecks.reasonCode(UnsafeWorkbookReason.NOT_A_ZIP)).isEqualTo("NOT_XLSX");
        assertThat(TemplateUploadChecks.reasonCode(UnsafeWorkbookReason.NOT_A_WORKBOOK)).isEqualTo("NOT_XLSX");
        assertThat(TemplateUploadChecks.reasonCode(UnsafeWorkbookReason.TOO_MANY_ENTRIES))
                .isEqualTo("UNPACKED_TOO_LARGE");
        assertThat(TemplateUploadChecks.reasonCode(UnsafeWorkbookReason.ENTRY_TOO_LARGE))
                .isEqualTo("UNPACKED_TOO_LARGE");
        assertThat(TemplateUploadChecks.reasonCode(UnsafeWorkbookReason.TOTAL_TOO_LARGE))
                .isEqualTo("UNPACKED_TOO_LARGE");
        assertThat(TemplateUploadChecks.reasonCode(UnsafeWorkbookReason.UNREADABLE)).isEqualTo("BROKEN_ZIP");
    }

    @Test
    void twoPartsWhoseNamesDifferOnlyInCaseAreABrokenZip() {
        byte[] doubled = TemplateFiles.withPart(TemplateFiles.valid(), "XL/Workbook.xml", "<workbook/>".getBytes());

        assertInvalid("statement.xlsx", doubled, ProblemCodes.BROKEN_ZIP);
    }

    // ---- code

    @Test
    void aVbaProjectIsMacros() {
        assertInvalid("statement.xlsx", TemplateFiles.withVbaProject(), ProblemCodes.MACROS);
    }

    @Test
    void aMacroEnabledContentTypeIsMacros() {
        assertInvalid("statement.xlsx", TemplateFiles.withMacroEnabledContentType(), ProblemCodes.MACROS);
    }

    @Test
    void anExcel4MacroSheetIsMacros() {
        byte[] workbook = TemplateFiles.withPart(TemplateFiles.valid(), "xl/macrosheets/sheet1.xml",
                "<xm:macrosheet xmlns:xm=\"http://schemas.microsoft.com/office/excel/2006/main\"/>".getBytes());

        assertInvalid("statement.xlsx", workbook, ProblemCodes.MACROS);
    }

    @Test
    void activeXControlsAndOleObjectsAreActiveContent() {
        assertInvalid("statement.xlsx", TemplateFiles.withActiveX(), ProblemCodes.ACTIVE_CONTENT);
        assertInvalid("statement.xlsx", TemplateFiles.withOleObject(), ProblemCodes.ACTIVE_CONTENT);
        byte[] declared = TemplateFiles.withText(TemplateFiles.valid(), TemplateFiles.CONTENT_TYPES,
                types -> types.replace("</Types>", "<Default Extension=\"bin\" ContentType=\"application/"
                        + "vnd.openxmlformats-officedocument.oleObject\"/></Types>"));
        assertInvalid("statement.xlsx", declared, ProblemCodes.ACTIVE_CONTENT);
    }

    // ---- XML

    @Test
    void aDocumentTypeInAnyPartIsABrokenZip() {
        assertInvalid("statement.xlsx", TemplateFiles.withDoctype(), ProblemCodes.BROKEN_ZIP);
        byte[] inContentTypes = TemplateFiles.withText(TemplateFiles.valid(), TemplateFiles.CONTENT_TYPES,
                types -> types.replaceFirst("<Types", "<!DOCTYPE Types><Types"));
        assertInvalid("statement.xlsx", inContentTypes, ProblemCodes.BROKEN_ZIP);
        byte[] external = TemplateFiles.withText(TemplateFiles.valid(), TemplateFiles.WORKBOOK,
                workbook -> workbook.replaceFirst("<workbook",
                        "<!DOCTYPE workbook SYSTEM \"http://attacker.invalid/x.dtd\"><workbook"));
        assertInvalid("statement.xlsx", external, ProblemCodes.BROKEN_ZIP);
    }

    @Test
    void aPartThatIsNotWellFormedIsABrokenZip() {
        assertInvalid("statement.xlsx", TemplateFiles.withBrokenSheet(), ProblemCodes.BROKEN_ZIP);
    }

    // ---- repeating rows

    @Test
    void aRowMixingTwoRowSetsIsRefused() {
        byte[] workbook = TemplateFiles.withInlineRows(
                new String[] {"${rows.plots.number}", "${rows.meters.serial}"});

        assertInvalid("statement.xlsx", workbook, ProblemCodes.TWO_ROW_SETS);
    }

    @Test
    void twoRepeatingRowsInOneSheetAreRefused() {
        byte[] workbook = TemplateFiles.withInlineRows(
                new String[] {"${rows.plots.number}"},
                new String[] {"Разом"},
                new String[] {"${rows.plots.area}"});

        assertInvalid("statement.xlsx", workbook, ProblemCodes.TWO_ROW_SETS);
    }

    @Test
    void anArrayFormulaAcrossTheRepeatingRowIsRefusedAndOneOnItOrBesideItIsFine() {
        // valid(): the repeating row is 3, the totals row 4 (B4 = SUM(B3:B3))
        byte[] across = TemplateFiles.withText(TemplateFiles.valid(), TemplateFiles.SHEET, sheet -> sheet.replace(
                "<f>SUM(B3:B3)</f>", "<f t=\"array\" ref=\"B3:B4\">B3:B3*2</f>"));
        assertInvalid("statement.xlsx", across, ProblemCodes.ARRAY_ACROSS_ROW);
        byte[] fromAbove = TemplateFiles.withText(TemplateFiles.valid(), TemplateFiles.SHEET, sheet -> sheet.replace(
                "<f>SUM(B3:B3)</f>", "<f t=\"array\" ref=\"C1:C3\">A1:A3</f>"));
        assertInvalid("statement.xlsx", fromAbove, ProblemCodes.ARRAY_ACROSS_ROW);

        for (String ref : List.of("C3", "C3:D3", "C4:C9", "C1:C2")) {
            byte[] fine = TemplateFiles.withText(TemplateFiles.valid(), TemplateFiles.SHEET, sheet -> sheet.replace(
                    "<f>SUM(B3:B3)</f>", "<f t=\"array\" ref=\"" + ref + "\">B3*2</f>"));
            assertThat(checks.check("statement.xlsx", fine).warnings()).as(ref).isEmpty();
        }
        // without a repeating row an array may span any rows
        byte[] noRepeat = TemplateFiles.withInlineRows(new String[] {"${period.year}"});
        byte[] spanning = TemplateFiles.withText(noRepeat, TemplateFiles.SHEET, sheet -> sheet.replace("</row>",
                "<c r=\"B1\"><f t=\"array\" ref=\"B1:B9\">A1:A9</f></c></row>"));
        assertThat(checks.check("statement.xlsx", spanning).warnings()).isEmpty();
    }

    @Test
    void sharedStringsAreReadForTheRowSetsIncludingRichText() {
        byte[] twoInSharedStrings = TemplateFiles.workbook(sheet -> {
            sheet.value(0, 0, "${rows.plots.number}");
            sheet.value(0, 1, "№ ${rows.water.source}");
        });
        assertInvalid("statement.xlsx", twoInSharedStrings, ProblemCodes.TWO_ROW_SETS);

        // a placeholder split over two rich-text runs is still one placeholder (the phonetic run is not text): the
        // title row becomes a repeating row of meters above the repeating row of plots
        byte[] rich = TemplateFiles.withText(TemplateFiles.valid(), "xl/sharedStrings.xml", strings -> strings
                .replace("<t xml:space=\"preserve\">${period.year}</t>", "<r><t>${rows.</t></r><r><rPr><b/></rPr>"
                        + "<t>meters.serial}</t></r><rPh sb=\"0\" eb=\"1\"><t>${rows.plots.x}</t></rPh>"));
        assertThat(rich).isNotEqualTo(TemplateFiles.valid());
        assertInvalid("statement.xlsx", rich, ProblemCodes.TWO_ROW_SETS);
        byte[] phoneticOnly = TemplateFiles.withText(TemplateFiles.valid(), "xl/sharedStrings.xml", strings -> strings
                .replace("<t xml:space=\"preserve\">${period.year}</t>", "<t>Рік</t>"
                        + "<rPh sb=\"0\" eb=\"1\"><t>${rows.meters.serial}</t></rPh>"));
        assertThat(checks.check("statement.xlsx", phoneticOnly).warnings()).isEmpty();
    }

    // ---- external links

    @Test
    void linksToOtherWorkbooksAndConnectionsAreRemovedAndTheFileStillOpens() throws IOException {
        byte[] linked = TemplateFiles.withExternalLink();
        Map<String, byte[]> uploaded = TemplateFiles.parts(linked);

        TemplateUploadChecks.Checked checked = checks.check("linked.xlsx", linked);

        assertThat(checked.warnings()).containsExactly(ReportTemplateWarningCode.EXTERNAL_LINKS_REMOVED);
        assertThat(checked.content()).isNotEqualTo(linked);
        assertThat(checked.sha256()).isEqualTo(TemplateUploadChecks.sha256(checked.content()));
        Map<String, byte[]> parts = TemplateFiles.parts(checked.content());
        assertThat(parts.keySet()).noneMatch(name -> name.startsWith("xl/externalLinks/"))
                .doesNotContain("xl/connections.xml")
                .containsAll(TemplateFiles.parts(TemplateFiles.valid()).keySet());
        assertThat(uploaded).containsKeys("xl/externalLinks/externalLink1.xml", "xl/connections.xml");
        assertThat(TemplateFiles.text(parts.get(TemplateFiles.WORKBOOK))).doesNotContain("externalReference");
        assertThat(TemplateFiles.text(parts.get(TemplateFiles.WORKBOOK_RELS)))
                .doesNotContain("externalLink").doesNotContain("connections").contains("worksheets/sheet1.xml");
        assertThat(TemplateFiles.text(parts.get(TemplateFiles.CONTENT_TYPES)))
                .doesNotContain("externalLink").doesNotContain("connections")
                .contains(TemplateFiles.MAIN_TYPE);
        // every part that was not about links is unchanged
        parts.forEach((name, bytes) -> {
            if (!List.of(TemplateFiles.WORKBOOK, TemplateFiles.WORKBOOK_RELS, TemplateFiles.CONTENT_TYPES)
                    .contains(name)) {
                assertThat(bytes).as(name).isEqualTo(uploaded.get(name));
            }
        });
        assertThat(TemplateFiles.text(parts.get(TemplateFiles.WORKBOOK)))
                .isEqualTo(TemplateFiles.text(uploaded.get(TemplateFiles.WORKBOOK))
                        .replace("<externalReferences><externalReference r:id=\"rIdLink\"/></externalReferences>", ""));
        // and the cleaned file passes the checks again, without a warning
        assertThat(checks.check("linked.xlsx", checked.content()).warnings()).isEmpty();
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(checked.content()))) {
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(rows.get(2).getCellText(0)).isEqualTo("${rows.plots.number}");
        }
    }

    @Test
    void aRelationshipToAQueryTableOfASheetIsRemovedWithThePart() {
        byte[] withQuery = TemplateFiles.withPart(TemplateFiles.withPart(TemplateFiles.valid(),
                "xl/queryTables/queryTable1.xml", ("<queryTable xmlns=\"http://schemas.openxmlformats.org/"
                        + "spreadsheetml/2006/main\" name=\"q\" connectionId=\"1\"/>").getBytes()),
                "xl/worksheets/_rels/sheet1.xml.rels", ("<?xml version=\"1.0\"?><Relationships xmlns=\"http://"
                        + "schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id='rId1' "
                        + "Type='http://example.invalid/other' Target='../queryTables/queryTable1.xml'></Relationship>"
                        + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                        + "relationships/hyperlink\" Target=\"https://example.org/?a=1&amp;b=2\" TargetMode=\"External\"/>"
                        + "</Relationships>").getBytes());

        TemplateUploadChecks.Checked checked = checks.check("query.xlsx", withQuery);

        Map<String, byte[]> parts = TemplateFiles.parts(checked.content());
        assertThat(parts).doesNotContainKey("xl/queryTables/queryTable1.xml");
        assertThat(TemplateFiles.text(parts.get("xl/worksheets/_rels/sheet1.xml.rels")))
                .doesNotContain("queryTable").contains("hyperlink");
    }

    @Test
    void linkElementsWithVeryLongAttributeListsAreRemovedAndOthersKeptWithoutError() {
        String padding = longAttributeList();
        byte[] linked = TemplateFiles.withExternalLink();
        linked = TemplateFiles.withText(linked, TemplateFiles.WORKBOOK_RELS, rels -> rels
                .replaceFirst("<Relationship ", "<Relationship " + padding + " ")
                .replace("<Relationship Id=\"rIdLink\"", "<Relationship " + padding + " Id=\"rIdLink\""));
        linked = TemplateFiles.withText(linked, TemplateFiles.CONTENT_TYPES, types -> types
                .replaceFirst("<Override ", "<Override " + padding + " ")
                .replace("<Override PartName=\"/xl/externalLinks/", "<Override " + padding
                        + " PartName=\"/xl/externalLinks/"));
        linked = TemplateFiles.withText(linked, TemplateFiles.WORKBOOK, workbook -> workbook
                .replace("<externalReferences>", "<externalReferences " + padding + ">"));
        Map<String, byte[]> uploaded = TemplateFiles.parts(linked);

        TemplateUploadChecks.Checked checked = checks.check("linked.xlsx", linked);

        assertThat(checked.warnings()).containsExactly(ReportTemplateWarningCode.EXTERNAL_LINKS_REMOVED);
        Map<String, byte[]> parts = TemplateFiles.parts(checked.content());
        String rels = TemplateFiles.text(parts.get(TemplateFiles.WORKBOOK_RELS));
        assertThat(rels).doesNotContain("externalLink").doesNotContain("connections")
                .contains("<Relationship " + padding + " ");
        String types = TemplateFiles.text(parts.get(TemplateFiles.CONTENT_TYPES));
        assertThat(types).doesNotContain("externalLink").doesNotContain("connections")
                .contains("<Override " + padding + " ");
        assertThat(TemplateFiles.text(parts.get(TemplateFiles.WORKBOOK)))
                .isEqualTo(TemplateFiles.text(uploaded.get(TemplateFiles.WORKBOOK)).replace("<externalReferences "
                        + padding + "><externalReference r:id=\"rIdLink\"/></externalReferences>", ""));
        assertThat(checks.check("linked.xlsx", checked.content()).warnings()).isEmpty();
    }

    @Test
    void aFileWithoutLinksButWithVeryLongStartTagsIsStoredByteForByte() {
        String padding = longAttributeList();
        byte[] padded = TemplateFiles.withText(TemplateFiles.withText(TemplateFiles.valid(),
                TemplateFiles.WORKBOOK_RELS, rels -> rels.replace("<Relationship ", "<Relationship " + padding + " ")),
                TemplateFiles.CONTENT_TYPES, types -> types.replace("<Override ", "<Override " + padding + " "));

        TemplateUploadChecks.Checked checked = checks.check("statement.xlsx", padded);

        assertThat(checked.content()).isSameAs(padded);
        assertThat(checked.warnings()).isEmpty();
    }

    @Test
    void anyFailureEscapingTheArchiveChecksIsABrokenZipNeverAnError() {
        ApiProblemException tooLarge = TemplateUploadChecks.invalid(ProblemCodes.UNPACKED_TOO_LARGE);

        assertThatThrownBy(() -> TemplateUploadChecks.refusingFailures(() -> {
            throw new StackOverflowError();
        })).isInstanceOfSatisfying(ApiProblemException.class, e -> assertThat(e.errors())
                .containsExactly(new FieldProblem("file", ProblemCodes.BROKEN_ZIP)));
        assertThatThrownBy(() -> TemplateUploadChecks.refusingFailures(() -> {
            throw new IllegalStateException("a parser failure");
        })).isInstanceOfSatisfying(ApiProblemException.class, e -> assertThat(e.errors())
                .containsExactly(new FieldProblem("file", ProblemCodes.BROKEN_ZIP)));
        assertThatThrownBy(() -> TemplateUploadChecks.refusingFailures(() -> {
            throw new IOException("truncated");
        })).isInstanceOfSatisfying(ApiProblemException.class, e -> assertThat(e.errors())
                .containsExactly(new FieldProblem("file", ProblemCodes.BROKEN_ZIP)));
        assertThatThrownBy(() -> TemplateUploadChecks.refusingFailures(() -> {
            throw tooLarge;
        })).isSameAs(tooLarge);
        assertThat(TemplateUploadChecks.refusingFailures(() -> "stored")).isEqualTo("stored");
    }

    @Test
    void elementsAreFoundByOneForwardPassOverTheMarkup() {
        String xml = "<?xml version=\"1.0\"?><!-- <Relationship Id=\"c\"/> --><Relationships><![CDATA[<Relationship/>]]>"
                + "<Relationship Id=\"a\" Target='x>y/>z'/><pr:Relationship Id=\"b\">\n</pr:Relationship>"
                + "<Relationship Id=\"d\"><inner><Relationship Id=\"e\"/></inner></Relationship>"
                + "<RelationshipX Id=\"f\"/><Relationship\tId='g'/></Relationships>";

        List<ExternalLinkStripper.Element> found = ExternalLinkStripper.elements(xml, "Relationship");

        assertThat(found).extracting(element -> xml.substring(element.start(), element.end())).containsExactly(
                "<Relationship Id=\"a\" Target='x>y/>z'/>", "<pr:Relationship Id=\"b\">\n</pr:Relationship>",
                "<Relationship Id=\"d\"><inner><Relationship Id=\"e\"/></inner></Relationship>",
                "<Relationship\tId='g'/>");
        assertThat(found).extracting(ExternalLinkStripper.Element::attributes).containsExactly(
                " Id=\"a\" Target='x>y/>z'", " Id=\"b\"", " Id=\"d\"", "\tId='g'");
        assertThat(ExternalLinkStripper.elements("<a><Relationship Id=\"unterminated", "Relationship")).isEmpty();
    }

    /**
     * About 100 KB of attributes for one start tag: 100 short attributes (the JDK parser allows 200 per element)
     * separated by runs of 1,000 spaces. Each of those characters is one step of a pattern that walks a start tag.
     */
    private static String longAttributeList() {
        return IntStream.range(0, 100).mapToObj(index -> "pad" + index + "=\"v\"")
                .collect(Collectors.joining(" ".repeat(1_000)));
    }

    @Test
    void relationshipTargetsAreResolvedAgainstTheirPart() {
        assertThat(ExternalLinkStripper.baseOf("xl/_rels/workbook.xml.rels")).isEqualTo("xl/");
        assertThat(ExternalLinkStripper.baseOf("_rels/.rels")).isEmpty();
        assertThat(ExternalLinkStripper.resolve("xl/worksheets/", "../externalLinks/externalLink1.xml"))
                .isEqualTo("xl/externallinks/externallink1.xml");
        assertThat(ExternalLinkStripper.resolve("xl/", "/xl/./Connections.xml")).isEqualTo("xl/connections.xml");
        assertThat(ExternalLinkStripper.resolve("xl/", "external%4Cinks/a.xml")).isEqualTo("xl/externallinks/a.xml");
        assertThat(ExternalLinkStripper.resolve("xl/", "bad%zzescape.xml")).isEqualTo("xl/bad%zzescape.xml");
        assertThat(ExternalLinkStripper.attributes("Target=\"a&amp;b&#x41;&#66;\" x:Type='t'"))
                .containsEntry("Target", "a&bAB").containsEntry("Type", "t");
    }

    // ---- file names

    @Test
    void theFileNameIsTheSanitisedBaseNameKeepingTheExtension() {
        assertThat(TemplateUploadChecks.baseName("../../etc/statement.xlsx")).isEqualTo("statement.xlsx");
        assertThat(TemplateUploadChecks.baseName(" sta\u202Etement\u0000.xlsx ")).isEqualTo("statement.xlsx");
        assertThat(TemplateUploadChecks.baseName(null)).isEmpty();
        String longName = "я".repeat(300) + ".XLSX";
        String shortened = TemplateUploadChecks.baseName(longName);
        assertThat(shortened.codePointCount(0, shortened.length())).isEqualTo(255);
        assertThat(shortened).endsWith(".XLSX").startsWith("я");
        String longOther = "b".repeat(300);
        assertThat(TemplateUploadChecks.baseName(longOther)).hasSize(255);
    }

    private void assertInvalid(String fileName, byte[] content, String reason) {
        assertThatThrownBy(() -> checks.check(fileName, content))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo(ProblemCodes.TEMPLATE_INVALID);
                    assertThat(e.errors()).containsExactly(new FieldProblem("file", reason));
                    if (fileName != null && !fileName.isEmpty()) {
                        assertThat(e.getMessage()).doesNotContain(fileName);
                    }
                });
    }
}
