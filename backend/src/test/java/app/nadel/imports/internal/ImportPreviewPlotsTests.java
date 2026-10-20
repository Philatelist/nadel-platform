package app.nadel.imports.internal;

import static app.nadel.imports.internal.ImportWorkbooks.PLOT_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.plot;
import static app.nadel.imports.internal.ImportWorkbooks.plots;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** POST /imports with the Plots sheet: the preview, its problems, the limits, and that nothing is saved. */
class ImportPreviewPlotsTests extends ImportsIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String unique() {
        return "ip" + UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Object> runRow(String id) {
        return ownerJdbc().queryForMap("select * from import_run where id = ?::uuid", id);
    }

    // ---- the preview

    @Test
    void theChairmanUploadsAWorkbookAndGetsCountsAndNoProblems() throws Exception {
        String a = unique();
        String b = unique();
        byte[] file = plots(
                plot(a, "Garden", "PLOT", 600, null, null, "yes", "note"),
                plot(b, null, "COMMON", null, null, null, null, null));

        MvcResult result = upload(file)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json"))
                .andExpect(jsonPath("$.fileName").value("plots.xlsx"))
                .andExpect(jsonPath("$.counts.plots.created").value(2))
                .andExpect(jsonPath("$.counts.plots.updated").value(0))
                .andExpect(jsonPath("$.counts.plots.unchanged").value(0))
                .andExpect(jsonPath("$.counts.members.created").value(0))
                .andExpect(jsonPath("$.counts.ownerships.created").value(0))
                .andExpect(jsonPath("$.problems", hasSize(0)))
                .andExpect(jsonPath("$.problemCount").value(0))
                .andExpect(jsonPath("$.committable").value(true))
                .andReturn();

        JsonNode preview = body(result);
        String id = preview.get("id").asString();
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith("/api/v1/imports/" + id);
        Instant expiresAt = Instant.parse(preview.get("expiresAt").asString());
        assertThat(expiresAt).isBetween(Instant.now().plusSeconds(29 * 60), Instant.now().plusSeconds(31 * 60));

        Map<String, Object> run = runRow(id);
        assertThat(run).containsEntry("status", "PREVIEW").containsEntry("file_name", "plots.xlsx")
                .containsEntry("problem_count", 0);
        assertThat(run.get("tenant_id")).isEqualTo(DEMO_TENANT);
        assertThat(run.get("created_by_subject")).isEqualTo("sub-chairman@demo.local");
        assertThat(run.get("created_by_member_id")).isNotNull();
        assertThat(run.get("created_by_name")).isNotNull();
        assertThat((String) run.get("plan_digest")).matches("[0-9a-f]{64}");
        JsonNode counts = JSON.readTree(run.get("counts").toString());
        assertThat(counts.get("plots").get("created").asInt()).isEqualTo(2);
        JsonNode plan = JSON.readTree(run.get("plan").toString());
        assertThat(plan.get("version").asInt()).isEqualTo(1);
        assertThat(plan.get("plots")).hasSize(2);
        JsonNode first = plan.get("plots").get(0);
        assertThat(first.get("row").asInt()).isEqualTo(2);
        assertThat(first.get("number").asString()).isEqualTo(a);
        assertThat(first.get("areaM2").asString()).isEqualTo("600");
        assertThat(first.get("hasBuilding").asBoolean()).isTrue();
        assertThat(first.has("cadastralNumber")).isFalse();
    }

    @Test
    void thePreviewSavesNoPlotAndNoAuditEntry() throws Exception {
        int plotsBefore = count("plot");
        int auditBefore = count("audit_entry");
        int outboxBefore = ownerJdbc().queryForObject("select count(*) from event_publication", Integer.class);

        upload(plots(plot(unique(), 600), plot(unique(), 300))).andExpect(status().isCreated());

        assertThat(count("plot")).isEqualTo(plotsBefore);
        assertThat(count("audit_entry")).isEqualTo(auditBefore);
        assertThat(ownerJdbc().queryForObject("select count(*) from event_publication", Integer.class))
                .isEqualTo(outboxBefore);
        assertThat(count("import_run")).isEqualTo(1);
    }

    @Test
    void theUnchangedTemplateHasNothingToImport() throws Exception {
        upload(new TemplateWriter().write())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.plots.created").value(0))
                .andExpect(jsonPath("$.counts.plots.updated").value(0))
                .andExpect(jsonPath("$.counts.plots.unchanged").value(0))
                .andExpect(jsonPath("$.counts.members.created").value(0))
                .andExpect(jsonPath("$.counts.ownerships.created").value(0))
                .andExpect(jsonPath("$.problems", hasSize(0)))
                .andExpect(jsonPath("$.problemCount").value(0))
                .andExpect(jsonPath("$.committable").value(false));
    }

    @Test
    void aWorkbookWithOnlyAHeaderRowHasNothingToImportEither() throws Exception {
        upload(plots()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.problemCount").value(0))
                .andExpect(jsonPath("$.committable").value(false));
    }

    // ---- problems

    @Test
    void eachProblemNamesSheetRowColumnAndCode() throws Exception {
        String duplicate = unique();
        byte[] file = plots(
                plot(duplicate, 600),
                plot(duplicate.toUpperCase(), 600),
                plot(unique(), null, "PLOT", 600, null, "123", null, null),
                plot(unique(), null, "PLOT", 600, 6, null, null, null),
                plot(unique(), null, null, 600, null, null, null, null));

        MvcResult result = upload(file)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.plots.created").value(1))
                .andExpect(jsonPath("$.problemCount").value(4))
                .andExpect(jsonPath("$.committable").value(false))
                .andExpect(jsonPath("$.problems", hasSize(4)))
                .andExpect(jsonPath("$.problems[0].sheet").value("PLOTS"))
                .andExpect(jsonPath("$.problems[0].row").value(3))
                .andExpect(jsonPath("$.problems[0].column").value("number"))
                .andExpect(jsonPath("$.problems[0].code").value("DUPLICATE_IN_FILE"))
                .andExpect(jsonPath("$.problems[1].row").value(4))
                .andExpect(jsonPath("$.problems[1].column").value("cadastral_number"))
                .andExpect(jsonPath("$.problems[1].code").value("CADASTRAL_INVALID"))
                .andExpect(jsonPath("$.problems[2].row").value(5))
                .andExpect(jsonPath("$.problems[2].column").value("area_m2"))
                .andExpect(jsonPath("$.problems[2].code").value("AREA_BOTH_OR_NONE"))
                .andExpect(jsonPath("$.problems[3].row").value(6))
                .andExpect(jsonPath("$.problems[3].column").value("kind"))
                .andExpect(jsonPath("$.problems[3].code").value("REQUIRED"))
                .andReturn();

        Map<String, Object> run = runRow(body(result).get("id").asString());
        assertThat(run).containsEntry("status", "HAS_ERRORS").containsEntry("problem_count", 4);
        assertThat(run.get("plan")).isNull();
    }

    @Test
    void anAreaMissingOnANewPlotIsAProblemOnTheAreaColumn() throws Exception {
        upload(plots(plot(unique(), null, "PLOT", null, null, null, null, null)))
                .andExpect(jsonPath("$.problems[0].column").value("area_m2"))
                .andExpect(jsonPath("$.problems[0].code").value("AREA_BOTH_OR_NONE"));
    }

    @Test
    void aProblemWithAreaInSotkyIsShownOnTheSotkyColumn() throws Exception {
        upload(plots(plot(unique(), null, "PLOT", null, "many", null, null, null),
                plot(unique(), null, "PLOT", null, "0,0123456", null, null, null)))
                .andExpect(jsonPath("$.problemCount").value(2))
                .andExpect(jsonPath("$.problems[0].row").value(2))
                .andExpect(jsonPath("$.problems[0].column").value("area_sotky"))
                .andExpect(jsonPath("$.problems[0].code").value("NUMBER_INVALID"))
                .andExpect(jsonPath("$.problems[1].row").value(3))
                .andExpect(jsonPath("$.problems[1].column").value("area_sotky"))
                .andExpect(jsonPath("$.problems[1].code").value("AREA_INVALID"));
    }

    @Test
    void unreadableCellsAreReportedOncePerCell() throws Exception {
        upload(plots(plot(unique(), null, "PLOT", "big", null, null, "maybe", null)))
                .andExpect(jsonPath("$.problemCount").value(2))
                .andExpect(jsonPath("$.problems[0].column").value("has_building"))
                .andExpect(jsonPath("$.problems[0].code").value("YES_NO_INVALID"))
                .andExpect(jsonPath("$.problems[1].column").value("area_m2"))
                .andExpect(jsonPath("$.problems[1].code").value("NUMBER_INVALID"));
    }

    @Test
    void aCadastralNumberOfAnExistingPlotIsTaken() throws Exception {
        String cadastral = ownerJdbc().queryForObject(
                "select cadastral_number from plot where tenant_id = ? and cadastral_number is not null limit 1",
                String.class, DEMO_TENANT);

        upload(plots(plot(unique(), null, "PLOT", 600, null, cadastral, null, null)))
                .andExpect(jsonPath("$.problems[0].column").value("cadastral_number"))
                .andExpect(jsonPath("$.problems[0].code").value("CADASTRAL_NUMBER_TAKEN"));
    }

    @Test
    void anExistingPlotIsUpdatedOrUnchanged() throws Exception {
        String number = unique();
        ownerJdbc().update("insert into plot (id, tenant_id, number, street, kind, area_m2) "
                + "values (gen_random_uuid(), ?, ?, 'Garden St', 'PLOT', 600)", DEMO_TENANT, number);
        try {
            upload(plots(plot(number, "Orchard St", "PLOT", 600, null, null, null, null)))
                    .andExpect(jsonPath("$.counts.plots.updated").value(1))
                    .andExpect(jsonPath("$.counts.plots.created").value(0))
                    .andExpect(jsonPath("$.committable").value(true));
            upload(plots(plot(number, "Garden St", "PLOT", null, 6, null, null, null)))
                    .andExpect(jsonPath("$.counts.plots.unchanged").value(1))
                    .andExpect(jsonPath("$.committable").value(false));
        } finally {
            ownerJdbc().update("delete from plot where number = ? and tenant_id = ?", number, DEMO_TENANT);
        }
    }

    @Test
    void ukrainianHeadersInAnyOrderAndCaseAreRead() throws Exception {
        String number = unique();
        byte[] file = ImportWorkbooks.builder()
                .sheet("plots", List.of("Площа, сот.", "ВУЛИЦЯ", "тип", "Номер"),
                        List.of(6, "Garden", "PLOT", number))
                .build();

        MvcResult result = upload(file)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.plots.created").value(1))
                .andExpect(jsonPath("$.problemCount").value(0))
                .andReturn();

        JsonNode plan = JSON.readTree(runRow(body(result).get("id").asString()).get("plan").toString());
        JsonNode row = plan.get("plots").get(0);
        assertThat(row.get("number").asString()).isEqualTo(number);
        assertThat(row.get("street").asString()).isEqualTo("Garden");
        assertThat(row.get("areaM2").asString()).isEqualTo("600");
    }

    @Test
    void floatingPointNoiseInTheAreaIsRemoved() throws Exception {
        MvcResult result = upload(plots(plot(unique(), null, "PLOT", new BigDecimal("600.00000000000011"), null, null,
                null, null)))
                .andExpect(jsonPath("$.problemCount").value(0))
                .andReturn();

        JsonNode plan = JSON.readTree(runRow(body(result).get("id").asString()).get("plan").toString());
        assertThat(plan.get("plots").get(0).get("areaM2").asString()).isEqualTo("600");
    }

    @Test
    void commentRowsAndEmptyRowsAreSkippedAndRowNumbersAreKept() throws Exception {
        List<List<?>> rows = new ArrayList<>();
        rows.add(List.of("# a comment before the header"));
        rows.add(PLOT_HEADER);
        rows.add(plot("# instruction", "Street", "PLOT or COMMON", "m2", "sotky", "cad", "yes/no", "notes"));
        rows.add(List.of());
        rows.add(plot(unique(), 600));
        rows.add(plot(unique(), null, "PLOT", 600, 6, null, null, null));

        upload(ImportWorkbooks.builder().sheetWithRows("Plots", rows).build())
                .andExpect(jsonPath("$.counts.plots.created").value(1))
                .andExpect(jsonPath("$.problemCount").value(1))
                .andExpect(jsonPath("$.problems[0].row").value(6))
                .andExpect(jsonPath("$.problems[0].code").value("AREA_BOTH_OR_NONE"));
    }

    @Test
    void aMissingPlotsSheetAndAMissingNumberColumnAreFileLevelProblems() throws Exception {
        upload(ImportWorkbooks.builder().sheet("Members", List.of("external_id"), List.of("m1")).build())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.problems[0].sheet").value("PLOTS"))
                .andExpect(jsonPath("$.problems[0].row").value(0))
                .andExpect(jsonPath("$.problems[0].code").value("SHEET_MISSING"))
                .andExpect(jsonPath("$.problems[0].column").doesNotExist())
                .andExpect(jsonPath("$.committable").value(false));

        upload(ImportWorkbooks.builder().sheet("Plots", List.of("street", "kind"), List.of("Garden", "PLOT"),
                        List.of("Orchard", "PLOT")).build())
                .andExpect(jsonPath("$.problemCount").value(1))
                .andExpect(jsonPath("$.problems[0].row").value(1))
                .andExpect(jsonPath("$.problems[0].column").value("number"))
                .andExpect(jsonPath("$.problems[0].code").value("HEADER_MISSING"));
    }

    @Test
    void anEmptyPlotsSheetMissesTheNumberHeader() throws Exception {
        upload(ImportWorkbooks.builder().sheetWithRows("Plots", List.of()).build())
                .andExpect(jsonPath("$.problems[0].row").value(1))
                .andExpect(jsonPath("$.problems[0].column").value("number"))
                .andExpect(jsonPath("$.problems[0].code").value("HEADER_MISSING"));
    }

    @Test
    void theListOfProblemsIsCappedButTheCountIsNot() throws Exception {
        List<List<?>> rows = new ArrayList<>();
        for (int i = 0; i < 1100; i++) {
            rows.add(plot(unique(), null, "PLOT", 600, 6, null, null, null)); // both areas on every row
        }

        upload(plots(rows.toArray(List<?>[]::new)))
                .andExpect(jsonPath("$.problemCount").value(1100))
                .andExpect(jsonPath("$.problems", hasSize(1000)))
                .andExpect(jsonPath("$.problems[999].row").value(1001));
    }

    // ---- refused files

    @Test
    void moreThanTwoThousandPlotRowsAreRefused() throws Exception {
        List<List<?>> rows = new ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            rows.add(plot("L" + i, 100));
        }

        upload(plots(rows.toArray(List<?>[]::new)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_LIMIT_EXCEEDED"));
        assertThat(count("import_run")).isZero();
    }

    @Test
    void exactlyTwoThousandPlotRowsAreAccepted() throws Exception {
        List<List<?>> rows = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            rows.add(plot(unique(), 100));
        }

        upload(plots(rows.toArray(List<?>[]::new)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.plots.created").value(2000));
    }

    @Test
    void aFileOverTenMegabytesIsTooLarge() throws Exception {
        upload(chairman(), "plots.xlsx", new byte[10 * 1024 * 1024 + 1])
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        assertThat(count("import_run")).isZero();
    }

    @Test
    void aCsvOrAPdfIsNotAWorkbook() throws Exception {
        upload(chairman(), "plots.csv", "number,kind\n1,PLOT\n".getBytes())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_NOT_XLSX"));
        upload(chairman(), "plots.xlsx", "%PDF-1.7\n".getBytes())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_NOT_XLSX"));
        upload(chairman(), "plots.pdf", plots(plot("1", 100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_NOT_XLSX"));
        assertThat(count("import_run")).isZero();
    }

    @Test
    void aWorkbookWithADoctypeAndAnExternalEntityIsRefusedCleanly() throws Exception {
        byte[] file = ImportWorkbooks.handMade(Map.of("xl/worksheets/sheet1.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE worksheet [<!ENTITY xxe SYSTEM "file:///etc/hosts">]>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                <row r="1"><c r="A1" t="inlineStr"><is><t>number</t></is></c><c r="B1" t="inlineStr"><is><t>kind</t></is></c><c r="C1" t="inlineStr"><is><t>area_m2</t></is></c></row>
                <row r="2"><c r="A2" t="inlineStr"><is><t>&xxe;</t></is></c><c r="B2" t="inlineStr"><is><t>PLOT</t></is></c><c r="C2"><v>600</v></c></row>
                </sheetData></worksheet>"""));

        // control: the same hand-made workbook without the DOCTYPE is a good workbook, so the refusals below are
        // caused by the DOCTYPE and nothing else
        upload(ImportWorkbooks.handMade(Map.of("xl/worksheets/sheet1.xml", """
                <?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                <row r="1"><c r="A1" t="inlineStr"><is><t>number</t></is></c><c r="B1" t="inlineStr"><is><t>kind</t></is></c><c r="C1" t="inlineStr"><is><t>area_m2</t></is></c></row>
                <row r="2"><c r="A2" t="inlineStr"><is><t>%s</t></is></c><c r="B2" t="inlineStr"><is><t>PLOT</t></is></c><c r="C2"><v>600</v></c></row>
                </sheetData></worksheet>""".formatted(unique()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.plots.created").value(1));

        // the library switches DTDs off: the document is refused as a damaged workbook, never expanded
        upload(file)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_NOT_XLSX"));

        // an entity that expands into itself, in the workbook part this time
        byte[] laughs = ImportWorkbooks.handMade(Map.of("xl/workbook.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE workbook [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;"><!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">]>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets><sheet name="&c;" sheetId="1" r:id="rId1"/></sheets>
                </workbook>"""));
        upload(laughs)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_NOT_XLSX"));

        assertThat(count("import_run")).isEqualTo(1); // only the control upload was recorded
    }

    @Test
    void theFileNameIsReducedToASafeBaseName() throws Exception {
        MvcResult result = upload(chairman(), "..\\..\\etc/ev‮il\u0007.xlsx", plots(plot(unique(), 600)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("evil.xlsx"))
                .andReturn();

        assertThat(runRow(body(result).get("id").asString())).containsEntry("file_name", "evil.xlsx");
    }

    @Test
    void requestsWithoutAFilePartOrNotMultipartAreBadRequests() throws Exception {
        mvc.perform(multipart(IMPORTS).header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(post(IMPORTS).header(HttpHeaders.AUTHORIZATION, chairman())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    // ---- who may upload

    @Test
    void theTreasurerMembersAndTheOperatorAreRefused() throws Exception {
        byte[] file = plots(plot(unique(), 600));
        for (String email : List.of("treasurer@demo.local", "member@demo.local", "electrician@demo.local")) {
            upload(demo(email), "plots.xlsx", file)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();
        upload(operator, "plots.xlsx", file)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(count("import_run")).isZero();
    }

    @Test
    void anonymousCallersGet401() throws Exception {
        mvc.perform(multipart(IMPORTS).file(new org.springframework.mock.web.MockMultipartFile("file", "plots.xlsx",
                XLSX, plots(plot("1", 100))))).andExpect(status().isUnauthorized());
    }

    // ---- expired plans

    @Test
    void anUploadClearsTheExpiredPlansOfTheCooperativeOnly() throws Exception {
        UUID demoExpired = UUID.randomUUID();
        UUID demoLive = UUID.randomUUID();
        UUID otherExpired = UUID.randomUUID();
        String insert = "insert into import_run (id, tenant_id, file_name, created_by_subject, created_by_name, "
                + "created_at, expires_at, status, plan) values (?, ?, 'f.xlsx', 's', 'n', now() - ?::interval, "
                + "now() + ?::interval, 'PREVIEW', '{\"plots\": []}'::jsonb)";
        ownerJdbc().update(insert, demoExpired, DEMO_TENANT, "2 hours", "-1 hour");
        ownerJdbc().update(insert, demoLive, DEMO_TENANT, "1 minute", "29 minutes");
        ownerJdbc().update(insert, otherExpired, OTHER_TENANT, "2 hours", "-1 hour");

        upload(plots(plot(unique(), 600))).andExpect(status().isCreated());

        assertThat(runRow(demoExpired.toString()).get("plan")).isNull();
        assertThat(runRow(demoLive.toString()).get("plan")).isNotNull();
        assertThat(runRow(otherExpired.toString()).get("plan")).isNotNull();
        assertThat(runRow(demoExpired.toString())).containsEntry("status", "PREVIEW");
    }
}
