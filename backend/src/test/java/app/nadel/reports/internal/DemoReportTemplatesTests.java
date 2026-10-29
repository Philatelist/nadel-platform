package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.DemoLedgerData;
import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The demo report template of {@code R__demo_report_templates.sql} (feature 013, slice 10) and the fake requisites of
 * {@code R__demo_data.sql}. Integration tests start with the demo ledger switched off, so the template script is run
 * here with the placeholder {@code demoledger} set to {@code on}. Checks: the template and its single version exist
 * with a size and SHA-256 that match the bytes, the bytes pass the upload checks unchanged, the chairman can generate a
 * file from it, re-running the script overwrites nothing a chairman did, and the tax slot stays empty.
 */
class DemoReportTemplatesTests extends IntegrationTest {

    private static final String TEMPLATES = "/api/v1/report-templates";
    private final JdbcTemplate owner = ownerJdbc();

    @Autowired
    private MockMvc mvc;

    @BeforeAll
    static void ownerRole() {
        ReportsTestSupport.createOwnerRole();
    }

    @BeforeEach
    @AfterEach
    void removeDemoTemplates() {
        await().atMost(Duration.ofSeconds(10)).until(() -> owner.queryForObject(
                "select count(*) from event_publication where completion_date is null", Integer.class) == 0);
        ReportsTestSupport.asLedgerOwner(sql -> {
            sql.update("delete from audit_entry where entity_id in (select id from report_template where tenant_id = ?)",
                    DEMO_TENANT);
            sql.update("delete from report_template_version where tenant_id = ?", DEMO_TENANT);
            sql.update("delete from report_template where tenant_id = ?", DEMO_TENANT);
        });
    }

    @Test
    void theDemoCooperativeGetsTheLandTaxStatementWithAConsistentVersion() throws Exception {
        runScript();
        Map<String, Object> template = owner.queryForMap("select * from report_template where tenant_id = ?",
                DEMO_TENANT);
        assertThat(template).containsEntry("name", "Відомість земельного податку").containsEntry("status", "ACTIVE");
        assertThat(template.get("slot")).isNull();
        assertThat(template.get("created_by")).hasToString("aaaaaaaa-0000-0000-0000-000000000001");

        Map<String, Object> version = owner.queryForMap("select * from report_template_version where tenant_id = ?",
                DEMO_TENANT);
        byte[] bytes = (byte[]) version.get("content");
        assertThat(version).containsEntry("version_no", 1).containsEntry("file_name", "land-tax-statement.xlsx");
        assertThat(version.get("size_bytes")).isEqualTo(bytes.length);
        assertThat(bytes.length).isLessThan(20 * 1024);
        assertThat(version.get("sha256")).isEqualTo(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(version.get("uploaded_by")).hasToString("aaaaaaaa-0000-0000-0000-000000000001");

        TemplateUploadChecks.Checked checked = new TemplateUploadChecks().check("land-tax-statement.xlsx", bytes);
        assertThat(checked.warnings()).isEmpty();
        assertThat(checked.content()).isEqualTo(bytes);
    }

    @Test
    void theTaxSlotStaysEmptyAndTheChairmanCanGenerate() throws Exception {
        runScript();
        assertThat(owner.queryForObject("select count(*) from report_template where tenant_id = ? and slot is not null",
                Integer.class, DEMO_TENANT)).isZero();
        UUID id = owner.queryForObject("select id from report_template where tenant_id = ?", UUID.class, DEMO_TENANT);
        String chairman = TestTokens.bearer(DEMO_TENANT, "sub-chairman@demo.local", "chairman@demo.local");
        mvc.perform(post(TEMPLATES + "/" + id + "/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\": \"2026-01-01\", \"to\": \"2026-12-31\"}")
                        .header(HttpHeaders.AUTHORIZATION, chairman))
                .andExpect(status().isOk())
                .andExpect(content().contentType(ReportTemplatesController.XLSX));
    }

    @Test
    void runningTheScriptAgainKeepsWhatTheChairmanChanged() throws Exception {
        runScript();
        owner.update("update report_template set name = 'Моя відомість', status = 'ARCHIVED', archived_at = now() "
                + "where tenant_id = ?", DEMO_TENANT);
        runScript();
        assertThat(owner.queryForList("select name || '|' || status from report_template where tenant_id = ?",
                String.class, DEMO_TENANT)).containsExactly("Моя відомість|ARCHIVED");
        assertThat(owner.queryForObject("select count(*) from report_template_version where tenant_id = ?",
                Integer.class, DEMO_TENANT)).isEqualTo(1);
    }

    @Test
    void theDemoLedgerBlockFillsFakeRequisitesOnlyWhileEmpty() {
        try {
            DemoLedgerData.restore(owner);
            Map<String, Object> settings = owner.queryForMap(
                    "select official_name, legal_address, bank_name, iban from cooperative_settings where tenant_id = ?",
                    DEMO_TENANT);
            assertThat(settings.values()).doesNotContainNull();
            assertThat(settings.get("iban")).isEqualTo("UA850000000000000000000123456");
            assertThat(owner.queryForObject("select edrpou from tenant where id = ?", String.class, DEMO_TENANT))
                    .isEqualTo("12345678");
            // a requisite the chairman edited survives the next run
            owner.update("update cooperative_settings set official_name = 'Власна назва' where tenant_id = ?",
                    DEMO_TENANT);
            DemoLedgerData.restore(owner);
            assertThat(owner.queryForObject("select official_name from cooperative_settings where tenant_id = ?",
                    String.class, DEMO_TENANT)).isEqualTo("Власна назва");
        } finally {
            DemoLedgerData.clear(owner);
            owner.update("update cooperative_settings set official_name = null, legal_address = null, "
                    + "bank_name = null, iban = null where tenant_id = ?", DEMO_TENANT);
            owner.update("update tenant set edrpou = null where id = ?", DEMO_TENANT);
        }
    }

    private void runScript() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/db/demo/R__demo_report_templates.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("${demoledger}", "on");
            owner.execute(sql);
        }
    }
}
