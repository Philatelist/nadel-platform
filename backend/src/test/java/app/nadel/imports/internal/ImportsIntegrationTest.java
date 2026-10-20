package app.nadel.imports.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Base of the import tests that call the API as demo users. The first sign-in of a demo member links it to a Keycloak
 * subject, and other tests expect their own subject, so what a test linked is unlinked again. Import runs the test
 * created are removed too (the application role cannot delete them).
 */
abstract class ImportsIntegrationTest extends IntegrationTest {

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String IMPORTS = "/api/v1/imports";

    private static final List<String> DEMO_EMAILS = List.of(
            "chairman@demo.local", "treasurer@demo.local", "member@demo.local", "electrician@demo.local");

    @Autowired
    protected MockMvc mvc;

    private List<String> unlinkedBefore = List.of();

    @BeforeEach
    void rememberUnlinkedDemoMembers() {
        unlinkedBefore = inTenant(DEMO_TENANT, sql -> sql.queryForList(
                "select email from member where keycloak_subject is null and email = any (string_to_array(?, ','))",
                String.class, String.join(",", DEMO_EMAILS)));
    }

    @AfterEach
    void cleanUpAfterTheTest() {
        for (String email : unlinkedBefore) {
            inTenant(DEMO_TENANT, sql -> sql.update(
                    "update member set keycloak_subject = null where email = ?", email));
        }
        ownerJdbc().update("delete from import_run");
    }

    protected static String demo(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }

    protected static String chairman() {
        return demo("chairman@demo.local");
    }

    /** POST /imports as the given caller (an Authorization header value) with one file part. */
    protected ResultActions upload(String authorization, String fileName, byte[] content) throws Exception {
        return mvc.perform(multipart(IMPORTS)
                .file(new MockMultipartFile("file", fileName, XLSX, content))
                .header(HttpHeaders.AUTHORIZATION, authorization));
    }

    protected ResultActions upload(byte[] content) throws Exception {
        return upload(chairman(), "plots.xlsx", content);
    }

    protected int count(String table) {
        return ownerJdbc().queryForObject("select count(*) from " + table + " where tenant_id = '" + DEMO_TENANT + "'",
                Integer.class);
    }
}
