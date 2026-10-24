package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /report-templates/{id}/generate} over HTTP (feature 013 §4.3, ADR 0015) in a throw-away cooperative with
 * the worked ledger of {@link ReportsTestSupport#bookTheLedger} and the land tax of {@code TemplateValuesTests}: a yearly
 * tariff of 35.00 per sotka from 01.01.2025 for P1 and P2 (600 m² each: 210.00 a year each, 420.00 expected). The
 * sample land tax statement ({@link SampleTemplates}) is filled, read back and compared with the land tax report; the
 * warnings travel in the headers and the warnings sheet; every refusal leaves no audit entry.
 */
class TemplateGenerateTests extends ReportsTestSupport {

    private static final String TEMPLATES = "/api/v1/report-templates";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String PERIOD = "{\"from\": \"2025-01-01\", \"to\": \"2025-03-31\"";

    @Autowired
    ReportLimits limits;

    private final List<UUID> otherCooperatives = new ArrayList<>();

    @AfterEach
    void removeOtherCooperatives() {
        otherCooperatives.forEach(ReportsTestSupport::removeCooperative);
    }

    @Test
    void theLandTaxStatementIsFilledWithTheLandTaxReportsNumbersAndAuditedOnce() throws Exception {
        landTax();
        UUID template = upload(chairman, "Відомість земельного податку", SampleTemplates.landTaxStatement());

        MockHttpServletResponse response = generate(treasurer, template, PERIOD + ", \"lang\": \"uk\"}")
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\""
                        + "_________ __________ ________2025-01-01_2025-03-31.xlsx\"; filename*=UTF-8''"
                        + "%D0%92%D1%96%D0%B4%D0%BE%D0%BC%D1%96%D1%81%D1%82%D1%8C%20%D0%B7%D0%B5%D0%BC%D0%B5%D0%BB"
                        + "%D1%8C%D0%BD%D0%BE%D0%B3%D0%BE%20%D0%BF%D0%BE%D0%B4%D0%B0%D1%82%D0%BA%D1%83"
                        + "_2025-01-01_2025-03-31.xlsx"))
                .andExpect(header().string(ReportTemplatesController.WARNINGS, "0"))
                .andExpect(header().doesNotExist(ReportTemplatesController.WARNINGS_DETAIL))
                .andReturn().getResponse();

        byte[] file = response.getContentAsByteArray();
        JsonNode report = body(send(chairman, get("/api/v1/reports/land-tax?year=2025")).andExpect(status().isOk()))
                .get("sheets").get(0);
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file))) {
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(rows.get(0).getCellAsString(0))
                    .contains("Відомість земельного податку Садівниче товариство «Звіти»");
            assertThat(rows.get(1).getCellAsString(1)).contains("12345678");
            assertThat(rows.get(2).getCellAsNumber(1).orElseThrow()).isEqualByComparingTo("2025");
            for (int index = 0; index < 2; index++) {
                Row plot = rows.get(4 + index);
                assertThat(plot.getCellAsString(0)).contains(Integer.toString(index + 1));
                assertThat(plot.getCellAsNumber(1).orElseThrow()).isEqualByComparingTo("600");
                assertThat(plot.getCellAsNumber(2).orElseThrow()).isEqualByComparingTo("35");
                assertThat(plot.getCellAsNumber(3).orElseThrow()).isEqualByComparingTo("210.00");
            }
            assertThat(rows.get(6).getCellAsString(0)).contains("Разом");
            // the template's total equals the land tax report's
            assertThat(rows.get(6).getCellAsNumber(4)).contains(
                    new BigDecimal(report.get("totals").get(0).get("cells").get(columnIndex(report, "expected"))
                            .get("value").asString()));
        }
        String sheet = TemplateFiles.text(TemplateFiles.parts(file).get("xl/worksheets/sheet1.xml"));
        assertThat(sheet).contains("<f>SUM(D5:D6)</f>");
        assertThat(TemplateFiles.text(TemplateFiles.parts(file).get("docProps/core.xml")))
                .contains(">Відомість земельного податку</dc:title>").contains(">1</cp:version>");

        // the land tax report's preview above is an entry of its own
        List<Map<String, Object>> entries = awaitReportEntries(2).stream()
                .filter(entry -> "REPORT_TEMPLATE".equals(entry.get("entity_type"))).toList();
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().get("entity_id")).isEqualTo(template);
        assertThat(entries.getFirst().get("entity_label")).isEqualTo("Відомість земельного податку");
        assertThat(entries.getFirst().get("actor_member_id")).isEqualTo(memberId("TREASURER"));
        assertThat(changes(entries.getFirst())).containsExactly("template=" + template, "version=1",
                "filters=[{\"param\":\"from\",\"value\":\"2025-01-01\"},{\"param\":\"to\",\"value\":\"2025-03-31\"}]",
                "rows=2", "personalData=false", "output=XLSX");
    }

    @Test
    void warningsComeInTheHeadersAndTheWarningsSheetAndAnUnknownTreeValueStaysEmpty() throws Exception {
        tree();
        byte[] template = TemplateFiles.workbook(sheet -> {
            sheet.value(0, 0, "${rows.meters.name}");
            sheet.value(0, 1, "${rows.meters.endValue}");
            for (int column = 0; column < 60; column++) {
                sheet.value(2, column, "${unknown.placeholder.with.a.long.name." + column + "}");
            }
        });
        UUID id = upload(chairman, "Лічильники", template);

        MockHttpServletResponse response = send(chairman, post(TEMPLATES + "/" + id + "/generate")
                .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.ACCEPT_LANGUAGE, "en-GB,en;q=0.9")
                .content(PERIOD + "}"))
                .andExpect(status().isOk())
                .andExpect(header().string(ReportTemplatesController.WARNINGS, "61"))
                .andReturn().getResponse();

        String detail = response.getHeader(ReportTemplatesController.WARNINGS_DETAIL);
        assertThat(detail).matches("[A-Za-z0-9_-]+").hasSizeLessThan(TemplateGenerator.DETAIL_BYTES);
        JsonNode warnings = json.readTree(new String(Base64.getUrlDecoder().decode(detail), StandardCharsets.UTF_8));
        assertThat(warnings.size()).isBetween(1, TemplateGenerator.DETAIL_WARNINGS - 1);
        // the quarter has no reading at the end: its end value is empty, never 0
        assertThat(warnings.get(0).get("code").asString()).isEqualTo("VALUE_UNKNOWN");
        assertThat(warnings.get(0).get("sheet").asString()).isEqualTo("Statement");
        assertThat(warnings.get(0).get("cell").asString()).isEqualTo("B2");
        assertThat(warnings.get(0).get("placeholder").asString()).isEqualTo("rows.meters.endValue");
        assertThat(warnings.get(0).get("reason").asString()).isEqualTo("NO_READING");
        assertThat(warnings.get(1).get("code").asString()).isEqualTo("UNKNOWN_PLACEHOLDER");
        assertThat(warnings.get(1).get("cell").asString()).isEqualTo("A4");
        assertThat(warnings.get(1).has("reason")).isFalse();
        assertThat(warnings.get(1).get("placeholder").asString()).isEqualTo("unknown.placeholder.with.a.long.name.0");

        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(
                response.getContentAsByteArray()))) {
            List<Row> meters = workbook.getFirstSheet().read();
            assertThat(meters.get(0).getCellAsString(0)).contains("ТП-1");
            assertThat(meters.get(0).getCellAsNumber(1).orElseThrow()).isEqualByComparingTo("1300");
            assertThat(meters.get(1).getCellAsString(0)).contains("Квартал А");
            assertThat(meters.get(1).getCellText(1)).isEmpty();
            List<Row> listed = workbook.getSheets().toList().get(1).read();
            assertThat(workbook.getSheets().toList().get(1).getName()).isEqualTo("Warnings");
            assertThat(listed).hasSize(62);
            assertThat(listed.get(1).getCellAsString(4)).contains("No reading, so the value is not known");
        }
    }

    @Test
    void thePeriodIsCheckedAndArchivedUnknownAndOtherCooperativesTemplatesAreRefusedWithoutAnAuditEntry()
            throws Exception {
        UUID id = upload(chairman, "Відомість", TemplateFiles.valid());

        generate(chairman, id, "{\"from\": \"2025-03-31\", \"to\": \"2025-01-01\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_FILTER_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("to"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        generate(chairman, id, "{\"from\": \"2025-01-01\", \"to\": \"2026-01-01\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_FILTER_INVALID"));
        generate(chairman, id, "{\"from\": \"2025-01-01\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        generate(chairman, id, PERIOD + ", \"lang\": \"de\"}").andExpect(status().isBadRequest());
        generate(chairman, UUID.randomUUID(), PERIOD + "}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        generate(otherCooperativeChairman(), id, PERIOD + "}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        send(chairman, post(TEMPLATES + "/" + id + "/archive")).andExpect(status().isOk());
        generate(chairman, id, PERIOD + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_ARCHIVED"));

        // a year less a day is a valid period
        UUID again = upload(chairman, "Відомість", TemplateFiles.valid());
        generate(chairman, again, "{\"from\": \"2025-01-01\", \"to\": \"2025-12-31\"}").andExpect(status().isOk());
        assertThat(awaitReportEntries(1)).hasSize(1);
    }

    @Test
    void moreRowsThanTheCapAreRefusedAndWriteNoAuditEntry() throws Exception {
        bookTheLedger();
        UUID id = upload(chairman, "Відомість", TemplateFiles.valid());
        limits.rowCap(1);
        try {
            generate(chairman, id, PERIOD + "}")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("REPORT_TOO_LARGE"));
        } finally {
            limits.rowCap(ReportLimits.DEFAULT_ROW_CAP);
        }
        awaitOutbox();
        assertThat(reportEntries()).isEmpty();
    }

    @Test
    void aStoredFileThatNoLongerPassesTheChecksIsRefusedCleanly() throws Exception {
        UUID id = upload(chairman, "Відомість", TemplateFiles.valid());
        // a newer version stored without the checks (as before a check existed): refused again at fill time
        byte[] hostile = TemplateFiles.withDoctype();
        ownerJdbc().update("insert into report_template_version (tenant_id, template_id, version_no, file_name, "
                + "content, size_bytes, sha256) values (?, ?, 2, 'v2.xlsx', ?, ?, ?)", tenant, id, hostile,
                hostile.length, TemplateUploadChecks.sha256(hostile));

        generate(chairman, id, PERIOD + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEMPLATE_INVALID"))
                .andExpect(jsonPath("$.errors[0].code").value("BROKEN_ZIP"))
                .andExpect(jsonPath("$.detail").value("The template file is not valid."));
        awaitOutbox();
        assertThat(reportEntries()).isEmpty();
    }

    @Test
    void theBodyIsValidatedBeforeThePermissionAndAWellFormedOneIsRefusedForAMember() throws Exception {
        UUID id = upload(chairman, "Відомість", TemplateFiles.valid());

        // the generated interface validates the body (@Valid) before @PreAuthorize: documented in the README
        generate(member, id, "{\"from\": \"2025-01-01\"}").andExpect(status().isBadRequest());
        generate(member, id, PERIOD + "}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        generate(null, id, PERIOD + "}").andExpect(status().isUnauthorized());
        awaitOutbox();
        assertThat(reportEntries()).isEmpty();
    }

    // ---- fixture

    /** The worked ledger with the land tax, the requisites and the ЄДРПОУ. */
    private void landTax() {
        bookTheLedger();
        perSotkaYearly(tariff(typeId("LAND_TAX"), "Земельний податок"), "35.00", "2025-01-01");
        ownerJdbc().update("insert into cooperative_settings (tenant_id, official_name) values (?, ?)", tenant,
                "Садівниче товариство «Звіти»");
        ownerJdbc().update("update tenant set edrpou = '12345678' where id = ?", tenant);
    }

    /** ТП-1 1 000 -> 1 300 over the quarter "Квартал А", read on 01.01.2025 only (its end is not known). */
    private void tree() {
        UUID substation = meter("SUBSTATION", "ELECTRICITY", null, "ТП-1", "S-TP1", "2025-01-01", "0");
        reading(substation, "2025-01-01", "1000", "APPROVED");
        reading(substation, "2025-03-31", "1300", "APPROVED");
        UUID quarter = meter("QUARTER", "ELECTRICITY", null, "Квартал А", "S-QA", "2025-01-01", "0");
        ownerJdbc().update("insert into meter_parent (tenant_id, meter_id, parent_meter_id, valid_from) "
                + "values (?, ?, ?, date '2025-01-01')", tenant, quarter, substation);
        reading(quarter, "2025-01-01", "500", "APPROVED");
    }

    private UUID upload(String bearer, String name, byte[] content) throws Exception {
        JsonNode created = body(send(bearer, multipart(TEMPLATES).file(new MockMultipartFile("file", "t.xlsx", XLSX,
                content)).param("name", name)).andExpect(status().isCreated()));
        return UUID.fromString(created.at("/template/id").asString());
    }

    private ResultActions generate(String bearer, UUID template, String body) throws Exception {
        return send(bearer, post(TEMPLATES + "/" + template + "/generate").contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static int columnIndex(JsonNode sheet, String key) {
        for (int index = 0; index < sheet.get("columns").size(); index++) {
            if (sheet.get("columns").get(index).get("key").asString().equals(key)) {
                return index;
            }
        }
        throw new AssertionError("No column " + key);
    }

    private String otherCooperativeChairman() {
        UUID other = UUID.randomUUID();
        ownerJdbc().update("insert into tenant (id, name) values (?, ?)", other, "СТ «Інший»");
        ownerJdbc().queryForList("select seed_base_roles(?)", other);
        otherCooperatives.add(other);
        return person(other, "CHAIRMAN");
    }
}
