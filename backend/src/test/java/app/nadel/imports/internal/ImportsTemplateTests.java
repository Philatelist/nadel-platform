package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** GET /imports/template: who may download it and what comes back. */
class ImportsTemplateTests extends IntegrationTest {

    private static final String TEMPLATE = "/api/v1/imports/template";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final List<String> DEMO_EMAILS = List.of(
            "chairman@demo.local", "treasurer@demo.local", "member@demo.local", "electrician@demo.local");

    @Autowired
    MockMvc mvc;

    private List<String> unlinkedBefore = List.of();

    /** The first sign-in links a demo member to a Keycloak subject; other tests expect their own subject. */
    @BeforeEach
    void rememberUnlinkedDemoMembers() {
        unlinkedBefore = inTenant(DEMO_TENANT, sql -> sql.queryForList(
                "select email from member where keycloak_subject is null and email = any (string_to_array(?, ','))",
                String.class, String.join(",", DEMO_EMAILS)));
    }

    @AfterEach
    void unlinkWhatThisTestLinked() {
        for (String email : unlinkedBefore) {
            inTenant(DEMO_TENANT, sql -> sql.update(
                    "update member set keycloak_subject = null where email = ?", email));
        }
    }

    @Test
    void theChairmanDownloadsAnXlsxAttachmentWithThreeSheets() throws Exception {
        MvcResult result = mvc.perform(get(TEMPLATE).header(HttpHeaders.AUTHORIZATION, demo("chairman@demo.local")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, XLSX))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"nadel-import-template.xlsx\""))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(result.getResponse().getContentLength()).isEqualTo(body.length);
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(body))) {
            assertThat(workbook.getSheets().map(Sheet::getName)).containsExactly("Plots", "Members", "Ownership");
        }
    }

    @Test
    void aBrowserClientAskingForJsonStillGetsTheFile() throws Exception {
        mvc.perform(get(TEMPLATE).header(HttpHeaders.AUTHORIZATION, demo("chairman@demo.local"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, XLSX));
    }

    @Test
    void aCustomRoleWithImportRunMayDownloadIt() throws Exception {
        String email = "import-custom-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(DEMO_TENANT, email);
        UUID role = UUID.randomUUID();
        inTenant(DEMO_TENANT, sql -> {
            sql.update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, DEMO_TENANT, "Import " + role);
            sql.update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, 'IMPORT_RUN')",
                    DEMO_TENANT, role);
            return sql.update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)",
                    DEMO_TENANT, member, role);
        });
        try {
            mvc.perform(get(TEMPLATE).header(HttpHeaders.AUTHORIZATION,
                            TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email)))
                    .andExpect(status().isOk());
        } finally {
            inTenant(DEMO_TENANT, sql -> {
                sql.update("delete from member_role where role_id = ?", role);
                sql.update("delete from role_permission where role_id = ?", role);
                return sql.update("delete from role where id = ?", role);
            });
        }
    }

    @Test
    void theTreasurerAndMembersAreRefused() throws Exception {
        for (String email : List.of("treasurer@demo.local", "member@demo.local", "electrician@demo.local")) {
            mvc.perform(get(TEMPLATE).header(HttpHeaders.AUTHORIZATION, demo(email)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }

    @Test
    void thePlatformOperatorIsRefused() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        mvc.perform(get(TEMPLATE).header(HttpHeaders.AUTHORIZATION, operator))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void anonymousCallersGet401() throws Exception {
        mvc.perform(get(TEMPLATE)).andExpect(status().isUnauthorized());
    }

    private static String demo(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }
}
