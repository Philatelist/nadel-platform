package app.nadel.imports.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.util.List;
import java.util.UUID;
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

    private List<UUID> unlinkedBefore = List.of();

    @BeforeEach
    void rememberUnlinkedDemoMembers() {
        unlinkedBefore = unlinkedMembersByEmail(DEMO_TENANT, DEMO_EMAILS.toArray(String[]::new));
    }

    @AfterEach
    void cleanUpAfterTheTest() {
        unlinkMembers(DEMO_TENANT, unlinkedBefore);
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
