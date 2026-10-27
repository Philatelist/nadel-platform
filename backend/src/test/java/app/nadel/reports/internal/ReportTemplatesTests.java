package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManagerFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * The template store over HTTP (feature 013, functional spec 2.11 and 2.12) in a throw-away cooperative: versions,
 * downloads byte for byte, the archive, unique names and the tax report slot, every refusal storing nothing, links to
 * other workbooks removed, the audit entries, another cooperative seeing nothing, and lists that never load a file.
 */
class ReportTemplatesTests extends ReportsTestSupport {

    private static final String TEMPLATES = "/api/v1/report-templates";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final List<UUID> otherCooperatives = new ArrayList<>();

    @AfterEach
    void removeOtherCooperatives() {
        otherCooperatives.forEach(ReportsTestSupport::removeCooperative);
    }

    // ---- versions

    @Test
    void aValidUploadIsVersionOneAndANewFileIsVersionTwoWhileVersionOneStaysDownloadable() throws Exception {
        byte[] first = TemplateFiles.valid();
        JsonNode created = body(upload(chairman, "Відомість земельного податку", "Для податкової", null, first,
                "Відомість.xlsx")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warnings").isEmpty())
                .andExpect(jsonPath("$.template.status").value("ACTIVE"))
                .andExpect(jsonPath("$.template.name").value("Відомість земельного податку"))
                .andExpect(jsonPath("$.template.description").value("Для податкової"))
                .andExpect(jsonPath("$.template.slot").doesNotExist())
                .andExpect(jsonPath("$.template.versionCount").value(1))
                .andExpect(jsonPath("$.template.latestVersion.versionNo").value(1))
                .andExpect(jsonPath("$.template.latestVersion.fileName").value("Відомість.xlsx"))
                .andExpect(jsonPath("$.template.latestVersion.sizeBytes").value(first.length))
                .andExpect(jsonPath("$.template.latestVersion.sha256").value(TemplateUploadChecks.sha256(first)))
                .andExpect(jsonPath("$.template.latestVersion.uploadedByName").value("Звітний CHAIRMAN")));
        UUID template = UUID.fromString(created.at("/template/id").asString());
        UUID v1 = UUID.fromString(created.at("/template/latestVersion/id").asString());

        byte[] second = TemplateFiles.workbook(sheet -> sheet.value(0, 0, "${cooperative.edrpou}"));
        JsonNode added = body(send(chairman, multipart(TEMPLATES + "/" + template + "/versions")
                .file(file("v2.xlsx", second)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, TEMPLATES + "/" + template + "/versions"))
                .andExpect(jsonPath("$.template.id").value(template.toString()))
                .andExpect(jsonPath("$.template.versionCount").value(2))
                .andExpect(jsonPath("$.template.latestVersion.versionNo").value(2)));
        UUID v2 = UUID.fromString(added.at("/template/latestVersion/id").asString());

        send(chairman, get(TEMPLATES + "/" + template + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(v2.toString()))
                .andExpect(jsonPath("$[0].versionNo").value(2))
                .andExpect(jsonPath("$[1].id").value(v1.toString()))
                .andExpect(jsonPath("$[1].versionNo").value(1))
                .andExpect(jsonPath("$[1].uploadedByName").value("Звітний CHAIRMAN"));

        byte[] downloaded = send(chairman, get(TEMPLATES + "/" + template + "/versions/" + v1 + "/file"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"_________.xlsx\"; filename*=UTF-8''"
                                + "%D0%92%D1%96%D0%B4%D0%BE%D0%BC%D1%96%D1%81%D1%82%D1%8C.xlsx"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(TemplateUploadChecks.sha256(downloaded)).isEqualTo(TemplateUploadChecks.sha256(first));
        assertThat(downloaded).isEqualTo(first);
        byte[] downloadedV2 = send(chairman, get(TEMPLATES + "/" + template + "/versions/" + v2 + "/file"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(downloadedV2).isEqualTo(second);
    }

    @Test
    void theListPutsTheTaxSlotFirstThenActiveBeforeArchivedByNameIgnoringCase() throws Exception {
        UUID zeta = uploadId("zeta", null);
        uploadId("Альфа", null);
        uploadId("beta", null);
        send(chairman, post(TEMPLATES + "/" + zeta + "/archive")).andExpect(status().isOk());

        // the treasurer has REPORTS_VIEW only: the list, nothing else
        send(treasurer, get(TEMPLATES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].slot").value("TAX"))
                .andExpect(jsonPath("$[0].status").value("NOT_ADDED"))
                .andExpect(jsonPath("$[0].versionCount").value(0))
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[0].name").doesNotExist())
                .andExpect(jsonPath("$[0].latestVersion").doesNotExist())
                .andExpect(jsonPath("$[1].name").value("beta"))
                .andExpect(jsonPath("$[2].name").value("Альфа"))
                .andExpect(jsonPath("$[3].name").value("zeta"))
                .andExpect(jsonPath("$[3].status").value("ARCHIVED"))
                .andExpect(jsonPath("$[3].latestVersion.versionNo").value(1));
        send(treasurer, post(TEMPLATES + "/" + zeta + "/archive")).andExpect(status().isForbidden());
    }

    // ---- the tax report slot

    @Test
    void theTaxSlotIsNotAddedUntilATemplateIsUploadedIntoItAndOneActiveTemplateHoldsIt() throws Exception {
        send(chairman, get(TEMPLATES)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("NOT_ADDED"));

        uploadId("Інший шаблон", null);
        UUID tax = uploadId("Податкова форма", "TAX");
        send(chairman, get(TEMPLATES))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(tax.toString()))
                .andExpect(jsonPath("$[0].slot").value("TAX"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].latestVersion.versionNo").value(1))
                .andExpect(jsonPath("$[1].name").value("Інший шаблон"));

        upload(chairman, "Друга податкова", null, "TAX", TemplateFiles.valid(), "tax.xlsx")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_SLOT_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("slot"));

        // archiving frees the slot: it shows as not added again, the archived template is listed with the others
        send(chairman, post(TEMPLATES + "/" + tax + "/archive")).andExpect(status().isOk());
        send(chairman, get(TEMPLATES))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].status").value("NOT_ADDED"))
                .andExpect(jsonPath("$[2].id").value(tax.toString()))
                .andExpect(jsonPath("$[2].slot").value("TAX"))
                .andExpect(jsonPath("$[2].status").value("ARCHIVED"));
        UUID again = uploadId("Друга податкова", "TAX");
        send(chairman, get(TEMPLATES)).andExpect(jsonPath("$[0].id").value(again.toString()))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    // ---- names

    @Test
    void anActiveNameIsTakenIgnoringCaseAndFreeAgainOnceArchived() throws Exception {
        UUID first = uploadId("Відомість", null);

        upload(chairman, "  ВІДОМІСТЬ ", null, null, TemplateFiles.valid(), "b.xlsx")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_NAME_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("TEMPLATE_NAME_TAKEN"));
        assertThat(templateCount()).isEqualTo(1);

        send(chairman, post(TEMPLATES + "/" + first + "/archive")).andExpect(status().isOk());
        upload(chairman, "  ВІДОМІСТЬ ", null, null, TemplateFiles.valid(), "b.xlsx")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.template.name").value("ВІДОМІСТЬ"));
    }

    @Test
    void formFieldsAreValidated() throws Exception {
        upload(chairman, "   ", null, null, TemplateFiles.valid(), "a.xlsx")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        upload(chairman, "x".repeat(201), null, null, TemplateFiles.valid(), "a.xlsx")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        upload(chairman, "Назва", "x".repeat(501), null, TemplateFiles.valid(), "a.xlsx")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        send(chairman, multipart(TEMPLATES).param("name", "Без файлу"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("file"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(chairman, multipart(TEMPLATES).file(file("a.xlsx", TemplateFiles.valid())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        upload(chairman, "Назва", null, "PENSION", TemplateFiles.valid(), "a.xlsx")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        UUID template = uploadId("Назва", null);
        send(chairman, multipart(TEMPLATES + "/" + template + "/versions"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("file"));
        assertThat(templateCount()).isEqualTo(1);
        assertThat(versionCount()).isEqualTo(1);
    }

    // ---- refusals

    @Test
    void everyRefusedFileGetsItsCodeAndNothingIsStored() throws Exception {
        Map<String, Object[]> refusals = Map.ofEntries(
                Map.entry("vba", new Object[] {"a.xlsx", TemplateFiles.withVbaProject(), "MACROS"}),
                Map.entry("macro type", new Object[] {"a.xlsx", TemplateFiles.withMacroEnabledContentType(),
                        "MACROS"}),
                Map.entry("xlsm", new Object[] {"a.xlsm", TemplateFiles.valid(), "NOT_XLSX"}),
                Map.entry("activeX", new Object[] {"a.xlsx", TemplateFiles.withActiveX(), "ACTIVE_CONTENT"}),
                Map.entry("ole", new Object[] {"a.xlsx", TemplateFiles.withOleObject(), "ACTIVE_CONTENT"}),
                Map.entry("doctype", new Object[] {"a.xlsx", TemplateFiles.withDoctype(), "BROKEN_ZIP"}),
                Map.entry("bomb", new Object[] {"a.xlsx", TemplateFiles.withBomb(30 * TemplateFiles.MB),
                        "UNPACKED_TOO_LARGE"}),
                Map.entry("text", new Object[] {"a.xlsx", TemplateFiles.text(), "NOT_XLSX"}),
                Map.entry("two sets", new Object[] {"a.xlsx", TemplateFiles.withInlineRows(
                        new String[] {"${rows.plots.number}", "${rows.water.source}"}), "TWO_ROW_SETS"}));
        for (Map.Entry<String, Object[]> refusal : refusals.entrySet()) {
            Object[] input = refusal.getValue();
            String body = upload(chairman, "Шаблон " + refusal.getKey(), null, null, (byte[]) input[1],
                    (String) input[0])
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("TEMPLATE_INVALID"))
                    .andExpect(jsonPath("$.errors.length()").value(1))
                    .andExpect(jsonPath("$.errors[0].field").value("file"))
                    .andExpect(jsonPath("$.errors[0].code").value((String) input[2]))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).as(refusal.getKey()).doesNotContain((String) input[0]).doesNotContain("Exception");
        }
        upload(chairman, "Завеликий", null, null, TemplateFiles.overTwoMegabytes(), "big.xlsx")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TEMPLATE_TOO_LARGE"));

        assertThat(templateCount()).isZero();
        assertThat(versionCount()).isZero();
        awaitOutbox();
        assertThat(templateEntries()).isEmpty();
    }

    @Test
    void aRefusedNewVersionStoresNothing() throws Exception {
        UUID template = uploadId("Відомість", null);

        send(chairman, multipart(TEMPLATES + "/" + template + "/versions").file(file("a.xlsx",
                TemplateFiles.withVbaProject())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("MACROS"));
        send(chairman, multipart(TEMPLATES + "/" + template + "/versions").file(file("a.xlsx",
                TemplateFiles.overTwoMegabytes())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TEMPLATE_TOO_LARGE"));

        assertThat(versionCount()).isEqualTo(1);
        awaitOutbox();
        assertThat(templateEntries()).hasSize(1);
    }

    // ---- links to other workbooks

    @Test
    void linksToOtherWorkbooksAreRemovedFromTheStoredFileWithAWarning() throws Exception {
        byte[] linked = TemplateFiles.withExternalLink();

        JsonNode created = body(upload(chairman, "З посиланнями", null, null, linked, "linked.xlsx")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warnings[0]").value("EXTERNAL_LINKS_REMOVED")));
        UUID template = UUID.fromString(created.at("/template/id").asString());
        UUID version = UUID.fromString(created.at("/template/latestVersion/id").asString());

        byte[] stored = send(chairman, get(TEMPLATES + "/" + template + "/versions/" + version + "/file"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(stored).isNotEqualTo(linked);
        assertThat(created.at("/template/latestVersion/sha256").asString())
                .isEqualTo(TemplateUploadChecks.sha256(stored));
        assertThat(created.at("/template/latestVersion/sizeBytes").asLong()).isEqualTo(stored.length);
        assertThat(TemplateFiles.parts(stored).keySet()).noneMatch(name -> name.startsWith("xl/externalLinks/"))
                .doesNotContain("xl/connections.xml");
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(stored))) {
            assertThat(workbook.getFirstSheet().read().get(2).getCellText(0)).isEqualTo("${rows.plots.number}");
        }
    }

    // ---- archive

    @Test
    void anArchivedTemplateKeepsItsVersionsAndTakesNoNewOne() throws Exception {
        UUID template = uploadId("Відомість", null);
        UUID v1 = latestVersion(template);

        send(chairman, post(TEMPLATES + "/" + template + "/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.versionCount").value(1))
                .andExpect(jsonPath("$.latestVersion.id").value(v1.toString()));
        // archiving again changes nothing
        send(chairman, post(TEMPLATES + "/" + template + "/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        send(chairman, get(TEMPLATES + "/" + template + "/versions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        send(chairman, get(TEMPLATES + "/" + template + "/versions/" + v1 + "/file")).andExpect(status().isOk());
        send(chairman, multipart(TEMPLATES + "/" + template + "/versions").file(file("v2.xlsx",
                TemplateFiles.valid())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_ARCHIVED"));
        assertThat(versionCount()).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select archived_by from report_template where id = ?", UUID.class,
                template)).isEqualTo(memberId("CHAIRMAN"));
    }

    // ---- audit

    @Test
    void uploadsAndTheArchiveAreAuditedOnceEachWithNameAndVersionOnly() throws Exception {
        UUID template = uploadId("Відомість ${x}", null);
        send(chairman, multipart(TEMPLATES + "/" + template + "/versions").file(file("друга.xlsx",
                TemplateFiles.valid()))).andExpect(status().isCreated());
        send(chairman, post(TEMPLATES + "/" + template + "/archive")).andExpect(status().isOk());
        send(chairman, post(TEMPLATES + "/" + template + "/archive")).andExpect(status().isOk());
        awaitOutbox();

        List<Map<String, Object>> entries = templateEntries();
        assertThat(entries).extracting(entry -> entry.get("action")).containsExactly("REPORT_TEMPLATE_UPLOADED",
                "REPORT_TEMPLATE_UPLOADED", "REPORT_TEMPLATE_ARCHIVED");
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.get("entity_type")).isEqualTo("REPORT_TEMPLATE");
            assertThat(entry.get("entity_id")).isEqualTo(template);
            assertThat(entry.get("entity_label")).isEqualTo("Відомість ${x}");
            assertThat(entry.get("actor_member_id")).isEqualTo(memberId("CHAIRMAN"));
        });
        assertThat(changes(entries.get(0))).containsExactly("version=1", "fileName=a.xlsx");
        assertThat(changes(entries.get(1))).containsExactly("version=2", "fileName=друга.xlsx");
        assertThat(changes(entries.get(2))).containsExactly("version=2");
    }

    // ---- other cooperatives

    @Test
    void anotherCooperativeCannotListDownloadArchiveOrExtendATemplate() throws Exception {
        UUID template = uploadId("Відомість", null);
        UUID version = latestVersion(template);
        String stranger = otherCooperativeChairman();

        send(stranger, get(TEMPLATES)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("NOT_ADDED"));
        send(stranger, get(TEMPLATES + "/" + template + "/versions")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(stranger, get(TEMPLATES + "/" + template + "/versions/" + version + "/file"))
                .andExpect(status().isNotFound());
        send(stranger, post(TEMPLATES + "/" + template + "/archive")).andExpect(status().isNotFound());
        send(stranger, multipart(TEMPLATES + "/" + template + "/versions").file(file("x.xlsx",
                TemplateFiles.valid()))).andExpect(status().isNotFound());
        // the same name is free in another cooperative
        upload(stranger, "Відомість", null, null, TemplateFiles.valid(), "a.xlsx").andExpect(status().isCreated());

        send(chairman, get(TEMPLATES)).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].versionCount").value(1));
        assertThat(versionCount()).isEqualTo(1);
    }

    @Test
    void unknownIdsAndAVersionOfAnotherTemplateAreNotFound() throws Exception {
        UUID first = uploadId("Перший", null);
        UUID second = uploadId("Другий", null);
        UUID versionOfSecond = latestVersion(second);
        UUID unknown = UUID.randomUUID();

        send(chairman, get(TEMPLATES + "/" + first + "/versions/" + versionOfSecond + "/file"))
                .andExpect(status().isNotFound());
        send(chairman, get(TEMPLATES + "/" + first + "/versions/" + unknown + "/file"))
                .andExpect(status().isNotFound());
        send(chairman, get(TEMPLATES + "/" + unknown + "/versions")).andExpect(status().isNotFound());
        send(chairman, post(TEMPLATES + "/" + unknown + "/archive")).andExpect(status().isNotFound());
        send(chairman, multipart(TEMPLATES + "/" + unknown + "/versions").file(file("x.xlsx",
                TemplateFiles.valid()))).andExpect(status().isNotFound());
        send(chairman, get(TEMPLATES + "/not-a-uuid/versions")).andExpect(status().isBadRequest());
    }

    // ---- files are never loaded by lists

    @Test
    void listsNeverLoadAVersionWithItsFile() throws Exception {
        UUID template = uploadId("Відомість", null);
        send(chairman, multipart(TEMPLATES + "/" + template + "/versions").file(file("v2.xlsx",
                TemplateFiles.valid()))).andExpect(status().isCreated());
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            send(chairman, get(TEMPLATES)).andExpect(status().isOk());
            send(treasurer, get(TEMPLATES)).andExpect(status().isOk());
            send(chairman, get(TEMPLATES + "/" + template + "/versions")).andExpect(status().isOk());
            send(chairman, post(TEMPLATES + "/" + template + "/archive")).andExpect(status().isOk());

            assertThat(statistics.getEntityStatistics(ReportTemplateVersionRecord.class.getName()).getLoadCount())
                    .isZero();
            assertThat(statistics.getEntityStatistics(ReportTemplateRecord.class.getName()).getLoadCount())
                    .isPositive();
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }

    // ---- helpers

    private ResultActions upload(String bearer, String name, String description, String slot, byte[] content,
            String fileName) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(TEMPLATES).file(file(fileName, content));
        request.param("name", name);
        if (description != null) {
            request.param("description", description);
        }
        if (slot != null) {
            request.param("slot", slot);
        }
        return send(bearer, request);
    }

    private UUID uploadId(String name, String slot) throws Exception {
        JsonNode created = body(upload(chairman, name, null, slot, TemplateFiles.valid(), "a.xlsx")
                .andExpect(status().isCreated()));
        return UUID.fromString(created.at("/template/id").asString());
    }

    private UUID latestVersion(UUID template) throws Exception {
        JsonNode versions = body(send(chairman, get(TEMPLATES + "/" + template + "/versions"))
                .andExpect(status().isOk()));
        return UUID.fromString(versions.get(0).get("id").asString());
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, XLSX, content);
    }

    private int templateCount() {
        return ownerJdbc().queryForObject("select count(*) from report_template where tenant_id = ?", Integer.class,
                tenant);
    }

    private int versionCount() {
        return ownerJdbc().queryForObject("select count(*) from report_template_version where tenant_id = ?",
                Integer.class, tenant);
    }

    private List<Map<String, Object>> templateEntries() {
        return ownerJdbc().queryForList("select action, entity_type, entity_id, entity_label, actor_member_id, "
                + "cast(changes as text) as changes from audit_entry where tenant_id = ? and entity_type = "
                + "'REPORT_TEMPLATE' order by occurred_at, id", tenant);
    }

    /** The chairman of another throw-away cooperative, removed after the test. */
    private String otherCooperativeChairman() {
        UUID other = UUID.randomUUID();
        ownerJdbc().update("insert into tenant (id, name) values (?, ?)", other, "СТ «Інший»");
        ownerJdbc().queryForList("select seed_base_roles(?)", other);
        otherCooperatives.add(other);
        return person(other, "CHAIRMAN");
    }
}
