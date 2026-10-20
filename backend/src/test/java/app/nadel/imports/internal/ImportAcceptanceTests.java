package app.nadel.imports.internal;

import static app.nadel.imports.internal.ImportWorkbooks.owner;
import static app.nadel.imports.internal.ImportWorkbooks.plot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Acceptance tests of the whole feature "Excel import" (functional-spec.md of 008) through the public HTTP API, as the
 * demo people would drive it: one test per journey over sections 2.1-2.5, reading the result back through the plots,
 * audit and history endpoints (not only the database). Per-case rules stay in the {@code Import*Tests} of this
 * package. Criteria are named {@code §2.x #n} in the order of the spec. Everything the journeys create is removed by
 * {@link ImportCommitSupport}. The class lives in {@code app.nadel.imports.internal} because it reuses the
 * package-private {@link ImportCommitSupport} and {@link ImportWorkbooks}.
 * <pre>
 * @layer: integration
 * @spec: 008-excel-import
 * @regression
 * </pre>
 */
@Tag("regression")
class ImportAcceptanceTests extends ImportCommitSupport {

    private static final LocalDate SINCE = LocalDate.of(2019, 5, 20);
    private static final String PLOTS = "/api/v1/plots";
    private static final String AUDIT = "/api/v1/audit";
    private static final String TEMPLATE = IMPORTS + "/template";

    private JsonNode getJson(String url, String authorization) throws Exception {
        return body(mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isOk()).andReturn());
    }

    /** The plots list of the cooperative filtered by a text, as the chairman reads it. */
    private JsonNode plotsMatching(String q) throws Exception {
        return getJson(PLOTS + "?size=100&q=" + q, chairman()).get("items");
    }

    private static List<List<?>> rows(List<?>... rows) {
        return List.of(rows);
    }

    // ---- 2.1 template and file format

    /** Opens the downloaded file: three sheets, each with the headers, an instruction row and an example row. */
    private static void assertTemplateLayout(byte[] file) throws Exception {
        List<String> firstHeaders = List.of("number", "external_id", "plot_number");
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file))) {
            List<Sheet> sheets = workbook.getSheets().toList();
            assertThat(sheets.stream().map(Sheet::getName)).containsExactly("Plots", "Members", "Ownership");
            for (int i = 0; i < sheets.size(); i++) {
                List<Row> rows = sheets.get(i).read();
                assertThat(rows).as(sheets.get(i).getName()).hasSize(3);
                assertThat(rows.get(0).getCellText(0)).isEqualTo(firstHeaders.get(i));
                assertThat(rows.get(0).getCellCount()).isGreaterThan(3);
                assertThat(rows.get(1).getCellText(0)).startsWith("#");
                assertThat(rows.get(2).getCellText(0)).startsWith("#").contains("example");
            }
        }
    }

    @Test
    void section21_theDownloadedTemplateUploadsAsNothingToImportWithoutErrors() throws Exception {
        // #1 the template is a file with the three sheets; #2 uploaded unchanged it has nothing to import
        MvcResult template = mvc.perform(get(TEMPLATE).header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isOk()).andReturn();
        byte[] file = template.getResponse().getContentAsByteArray();
        assertTemplateLayout(file);
        int plotsBefore = count("plot");

        upload(file)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.plots.created").value(0))
                .andExpect(jsonPath("$.counts.plots.updated").value(0))
                .andExpect(jsonPath("$.counts.plots.unchanged").value(0))
                .andExpect(jsonPath("$.counts.members.created").value(0))
                .andExpect(jsonPath("$.counts.ownerships.created").value(0))
                .andExpect(jsonPath("$.problems", hasSize(0)))
                .andExpect(jsonPath("$.committable").value(false));
        assertThat(count("plot")).isEqualTo(plotsBefore);
    }

    @Test
    void section21_ukrainianHeadersInAnotherOrderAndCaseAreReadAndSixSotkasAreSixHundredSquareMetres()
            throws Exception {
        // #3 headers "Номер", "Вулиця", "Площа, сот." shuffled and in other case; #5 6 sotkas are stored as 600 m2
        // (the UI prints that as "6 соток (600 м²)", see imports-acceptance.spec.ts)
        String number = plotNo("uk");
        byte[] file = ImportWorkbooks.builder()
                .sheet("Plots", List.of("ПЛОЩА, СОТ.", "вулиця", "ТИП", "нОмЕр"), List.of(6, "Garden St", "PLOT", number))
                .build();

        JsonNode preview = preview(file);
        assertThat(preview.get("counts").get("plots").get("created").asInt()).isEqualTo(1);
        assertThat(preview.get("problemCount").asInt()).isZero();
        commit(preview.get("id").asString()).andExpect(status().isOk());

        JsonNode listed = plotsMatching(number);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("street").asString()).isEqualTo("Garden St");
        assertThat(listed.get(0).get("areaM2").decimalValue()).isEqualByComparingTo("600");
        JsonNode card = getJson(PLOTS + "/" + listed.get(0).get("id").asString(), chairman());
        assertThat(card.get("areaM2").decimalValue()).isEqualByComparingTo("600");
    }

    @Test
    void section21_bothAreasOrNeitherIsAnErrorOnTheAreaColumn() throws Exception {
        // #4
        byte[] file = ImportWorkbooks.plots(
                plot(plotNo("both"), null, "PLOT", 600, 6, null, null, null),
                plot(plotNo("none"), null, "PLOT", null, null, null, null, null));

        JsonNode preview = preview(file);

        assertThat(preview.get("problemCount").asInt()).isEqualTo(2);
        assertThat(preview.get("committable").asBoolean()).isFalse();
        for (JsonNode problem : preview.get("problems")) {
            assertThat(problem.get("sheet").asString()).isEqualTo("PLOTS");
            assertThat(problem.get("column").asString()).isEqualTo("area_m2");
            assertThat(problem.get("code").asString()).isEqualTo("AREA_BOTH_OR_NONE");
        }
        assertThat(preview.get("problems").get(0).get("row").asInt()).isEqualTo(2);
        assertThat(preview.get("problems").get(1).get("row").asInt()).isEqualTo(3);
    }

    // ---- 2.2 upload and preview

    @Test
    void section22_theValidExampleAndTheMistakesFileAreChecked() throws Exception {
        // #1 the valid example file: 30 plots, 35 members, ownership rows, no errors
        byte[] valid = ImportFixtures.class.getResourceAsStream(ImportFixtures.VALID).readAllBytes();
        upload(valid)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.plots.created").value(30))
                .andExpect(jsonPath("$.counts.members.created").value(35))
                .andExpect(jsonPath("$.counts.ownerships.created").value(35))
                .andExpect(jsonPath("$.problemCount").value(0))
                .andExpect(jsonPath("$.committable").value(true));

        // #2 the file with typical mistakes: every problem with sheet, row, column and code; no commit
        byte[] errors = ImportFixtures.class.getResourceAsStream(ImportFixtures.ERRORS).readAllBytes();
        MvcResult result = upload(errors).andExpect(status().isCreated())
                .andExpect(jsonPath("$.committable").value(false)).andReturn();
        JsonNode preview = body(result);
        List<String> codes = new ArrayList<>();
        boolean invalidEmail = false;
        for (JsonNode problem : preview.get("problems")) {
            assertThat(problem.get("sheet").asString()).isIn("PLOTS", "MEMBERS", "OWNERSHIP");
            assertThat(problem.get("row").asInt()).isPositive();
            assertThat(problem.get("column").asString()).isNotBlank();
            codes.add(problem.get("code").asString());
            if (problem.get("code").asString().equals("INVALID") && problem.get("sheet").asString().equals("MEMBERS")) {
                assertThat(problem.get("column").asString()).isEqualTo("email");
                invalidEmail = true;
            }
        }
        assertThat(codes).contains("DUPLICATE_IN_FILE", "CADASTRAL_INVALID", "INVALID",
                "SHARES_NOT_ONE", "UNKNOWN_MEMBER_KEY");
        assertThat(invalidEmail).as("the bad e-mail is an INVALID problem on the email column").isTrue();
        assertThat(preview.get("problemCount").asInt()).isEqualTo(11);
        commit(preview.get("id").asString()).andExpect(status().isConflict());
        assertThat(plotsLike("IMP-")).isZero();
    }

    @Test
    void section22_refusedFilesGiveClearCodesAndLeaveNothingBehind() throws Exception {
        int plotsBefore = count("plot");
        // #3 over 10 MB
        upload(chairman(), "big.xlsx", new byte[10 * 1024 * 1024 + 1])
                .andExpect(status().isContentTooLarge()).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        // #4 a CSV and a PDF
        upload(chairman(), "plots.csv", "number,kind\n1,PLOT\n".getBytes())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_NOT_XLSX"));
        upload(chairman(), "plots.pdf", "%PDF-1.7\n%%EOF\n".getBytes())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_NOT_XLSX"));
        // #5 more than 2,000 plots
        List<List<?>> many = new ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            many.add(plot("LRG-" + tag + "-" + i, 100));
        }
        upload(ImportWorkbooks.plots(many.toArray(List<?>[]::new)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMPORT_LIMIT_EXCEEDED"));

        assertThat(count("plot")).isEqualTo(plotsBefore);
        assertThat(count("import_run")).isZero();
    }

    @Test
    void section22_uploadingWithoutCommittingLeavesThePlotsListAsItWas() throws Exception {
        // #6
        String before = plotsMatching("").toString();
        byte[] valid = ImportFixtures.class.getResourceAsStream(ImportFixtures.VALID).readAllBytes();

        preview(valid);

        assertThat(plotsMatching("").toString()).isEqualTo(before);
        assertThat(plotsMatching("IMP-")).isEmpty();
    }

    // ---- 2.3 commit and 2.5 history and audit

    @Test
    void section23And25_theCommittedExampleShowsInThePlotsListTheHistoryAndTheAuditLog() throws Exception {
        byte[] valid = ImportFixtures.class.getResourceAsStream(ImportFixtures.VALID).readAllBytes();
        JsonNode preview = preview(valid);
        String id = preview.get("id").asString();

        // 2.3 #1 the summary has the preview's counts and the plots list has the 30 plots with their owners
        commit(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.plots.created").value(30))
                .andExpect(jsonPath("$.counts.members.created").value(35))
                .andExpect(jsonPath("$.counts.ownerships.created").value(35));
        JsonNode plots = plotsMatching("IMP-");
        assertThat(plots).hasSize(30);
        int owned = 0;
        int ownerEntries = 0;
        for (JsonNode listed : plots) {
            if (listed.has("owners") && listed.get("owners").size() > 0) {
                owned++;
                ownerEntries += listed.get("owners").size();
            }
        }
        assertThat(owned).isEqualTo(30);
        assertThat(ownerEntries).isEqualTo(35);

        // 2.5 #1 the history lists the import with the author, time, file name and counts
        JsonNode history = getJson(IMPORTS, chairman()).get("items");
        assertThat(history).hasSize(1);
        JsonNode item = history.get(0);
        assertThat(item.get("status").asString()).isEqualTo("COMMITTED");
        assertThat(item.get("uploadedBy").asString()).isEqualTo("Kovalenko Oleksandr Petrovych");
        assertThat(item.get("uploadedAt").asString()).isNotBlank();
        assertThat(item.get("committedAt").asString()).isNotBlank();
        assertThat(item.get("fileName").asString()).isEqualTo("plots.xlsx");
        assertThat(item.get("counts").get("plots").get("created").asInt()).isEqualTo(30);

        // 2.5 #2 one import entry with file name and counts, plus an entry for each created plot and member
        awaitAudit("IMPORT_COMMITTED", "plots.xlsx", 1);
        awaitAudit("PLOT_CREATED", "IMP-", 30);
        awaitAudit("MEMBER_CREATED", "Fixt", 35);
        JsonNode entries = getJson(AUDIT + "?entityType=IMPORT&size=100", chairman()).get("items");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("action").asString()).isEqualTo("IMPORT_COMMITTED");
        assertThat(entries.get(0).get("entityLabel").asString()).isEqualTo("plots.xlsx");
        JsonNode plotsCreated = null;
        for (JsonNode change : entries.get(0).get("changes")) {
            if (change.toString().contains("plotsCreated")) {
                plotsCreated = change;
            }
        }
        assertThat(plotsCreated).as("the plotsCreated change").isNotNull();
        assertThat(plotsCreated.get("field").asString()).isEqualTo("plotsCreated");
        assertThat(plotsCreated.get("newValue").asString()).isEqualTo("30");
        assertThat(getJson(AUDIT + "?entityType=PLOT&size=100", chairman()).get("items").toString())
                .contains("PLOT_CREATED");
    }

    // ---- 2.4 repeating an import

    @Test
    void section24_aSecondUploadIsUnchangedAndEditsUpdateInsteadOfDuplicating() throws Exception {
        String a = plotNo("a");
        byte[] first = ImportWorkbooks.builder()
                .sheet("Plots", ImportWorkbooks.PLOT_HEADER,
                        plot(a, "Garden St", "PLOT", 600, null, null, null, "Keep me"))
                .sheet("Members", ImportWorkbooks.MEMBER_HEADER, newMember("M-1", "One"))
                .sheet("Ownership", ImportWorkbooks.OWNERSHIP_HEADER, owner(a, "M-1", "1", SINCE))
                .build();
        commit(preview(first).get("id").asString()).andExpect(status().isOk());

        // #1 the same file again: everything unchanged, nothing to commit
        upload(first).andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.plots.unchanged").value(1))
                .andExpect(jsonPath("$.counts.members.unchanged").value(1))
                .andExpect(jsonPath("$.counts.ownerships.unchanged").value(1))
                .andExpect(jsonPath("$.counts.plots.created").value(0))
                .andExpect(jsonPath("$.committable").value(false));

        // #2 street "Garden St" -> "Orchard St" is one updated plot; #5 empty notes stay; #3 a new phone for the
        // member found by e-mail updates it
        byte[] second = ImportWorkbooks.builder()
                .sheet("Plots", ImportWorkbooks.PLOT_HEADER, plot(a, "Orchard St", "PLOT", 600, null, null, null, null))
                .sheet("Members", ImportWorkbooks.MEMBER_HEADER,
                        ImportWorkbooks.member("M-1", "Fixt" + tag + "One", "Person", null, "+380501112233",
                                email("one"), null, null, null, null))
                .build();
        JsonNode preview = preview(second);
        assertThat(preview.get("counts").get("plots").get("updated").asInt()).isEqualTo(1);
        assertThat(preview.get("counts").get("members").get("updated").asInt()).isEqualTo(1);
        assertThat(preview.get("counts").get("members").get("created").asInt()).isZero();
        commit(preview.get("id").asString()).andExpect(status().isOk());

        JsonNode listed = plotsMatching(a);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("street").asString()).isEqualTo("Orchard St");
        JsonNode card = getJson(PLOTS + "/" + listed.get(0).get("id").asString(), chairman());
        assertThat(card.get("notes").asString()).isEqualTo("Keep me");
        assertThat(membersLike("Fixt" + tag)).isEqualTo(1);
        JsonNode members = getJson("/api/v1/members?size=100&q=Fixt" + tag, chairman()).get("items");
        assertThat(members).hasSize(1);
        assertThat(members.get(0).get("phones").toString()).contains("+380501112233");
    }

    @Test
    void section24_aDifferentOwnerOfAnOwnedPlotIsAnErrorPointingToChangeOwner() throws Exception {
        // #4 demo plot 9 is owned
        byte[] file = ImportWorkbooks.builder()
                .sheet("Plots", ImportWorkbooks.PLOT_HEADER)
                .sheet("Members", ImportWorkbooks.MEMBER_HEADER, newMember("M-1", "Buyer"))
                .sheet("Ownership", ImportWorkbooks.OWNERSHIP_HEADER, owner("9", "M-1", "1", SINCE))
                .build();

        JsonNode preview = preview(file);

        assertThat(preview.get("committable").asBoolean()).isFalse();
        JsonNode problem = preview.get("problems").get(0);
        assertThat(problem.get("sheet").asString()).isEqualTo("OWNERSHIP");
        assertThat(problem.get("code").asString()).isEqualTo("OWNERSHIP_CONFLICT");
        assertThat(problem.get("row").asInt()).isEqualTo(2);
        assertThat(problem.get("params").get("plot").asString()).isEqualTo("9");
        commit(preview.get("id").asString()).andExpect(status().isConflict());
        assertThat(membersLike("Fixt" + tag)).isZero();
    }

    // ---- 2.5 who may import, other cooperatives

    @Test
    void section25_theTreasurerAndAMemberAreRefusedEverywhereAndNothingIsSaved() throws Exception {
        byte[] file = ImportWorkbooks.plots(plot(plotNo("x"), 600));
        for (String email : List.of("treasurer@demo.local", "member@demo.local")) {
            String caller = demo(email);
            upload(caller, "plots.xlsx", file).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            mvc.perform(get(TEMPLATE).header(HttpHeaders.AUTHORIZATION, caller)).andExpect(status().isForbidden());
            mvc.perform(get(IMPORTS).header(HttpHeaders.AUTHORIZATION, caller)).andExpect(status().isForbidden());
        }
        assertThat(plotsLike("CMT-" + tag)).isZero();
        assertThat(count("import_run")).isZero();
    }

    @Test
    void section25_anImportOfOneCooperativeNeverTouchesAnotherAndItsPeopleSeeNothingOfIt() throws Exception {
        List<String> tables = List.of("plot", "member", "ownership", "plot_status", "import_run");
        List<Integer> before = new ArrayList<>();
        for (String table : tables) {
            before.add(ownerJdbc().queryForObject("select count(*) from " + table + " where tenant_id = ?",
                    Integer.class, OTHER_TENANT));
        }
        byte[] valid = ImportFixtures.class.getResourceAsStream(ImportFixtures.VALID).readAllBytes();

        commit(preview(valid).get("id").asString()).andExpect(status().isOk());

        List<Integer> after = new ArrayList<>();
        for (String table : tables) {
            after.add(ownerJdbc().queryForObject("select count(*) from " + table + " where tenant_id = ?",
                    Integer.class, OTHER_TENANT));
        }
        assertThat(after).isEqualTo(before);
        String otherMember = TestTokens.bearer(OTHER_TENANT, "sub-member@other.local", "member@other.local");
        assertThat(getJson(PLOTS + "?size=100", otherMember).toString()).doesNotContain("IMP-");
    }
}
