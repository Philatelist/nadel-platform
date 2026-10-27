package app.nadel.reports.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who may reach the report template endpoints (feature 013, functional spec 2.11, 2.12 and 2.14): the list and the
 * field catalog need {@code REPORTS_VIEW} or {@code REPORT_TEMPLATES_MANAGE}, generating {@code REPORTS_VIEW},
 * everything else {@code REPORT_TEMPLATES_MANAGE}. The permission is checked before anything else: a member, the
 * electrician and the platform operator are refused with 403 everywhere, the treasurer (only {@code REPORTS_VIEW})
 * passes on the list, the catalog and generating only, and without a token the answer is 401.
 * Requests that pass the check reach the operation: in the demo cooperative (which has no template) the list answers
 * 200 with the empty tax report slot, the uploads refuse the four bytes (a zip signature and nothing more: a damaged
 * archive), and the unknown template and version are 404, so nothing is stored. Generating from a template the chairman
 * stores for the test answers 200 to the chairman and the treasurer; the template and its audit entries are removed
 * from the demo cooperative afterwards.
 */
class ReportTemplatesAccessTests extends IntegrationTest {

    private static final String TEMPLATES = "/api/v1/report-templates";
    private static final UUID TEMPLATE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID VERSION = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    /** The first operations, open to {@code REPORTS_VIEW}: the list, the field catalog and generating. */
    private static final int FOR_VIEWERS = 3;

    /** Every operation, as a request a holder of the permission could send; those open to viewers first. */
    private static final List<Function<String, RequestBuilder>> OPERATIONS = List.of(
            bearer -> authorized(get(TEMPLATES), bearer),
            bearer -> authorized(get(TEMPLATES + "/catalog"), bearer),
            bearer -> authorized(post(TEMPLATES + "/" + TEMPLATE + "/generate").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"from\": \"2025-01-01\", \"to\": \"2025-01-31\"}"), bearer),
            bearer -> authorized(multipart(TEMPLATES).file(workbook()).param("name", "Statement"), bearer),
            bearer -> authorized(get(TEMPLATES + "/" + TEMPLATE + "/versions"), bearer),
            bearer -> authorized(multipart(TEMPLATES + "/" + TEMPLATE + "/versions").file(workbook()), bearer),
            bearer -> authorized(post(TEMPLATES + "/" + TEMPLATE + "/archive"), bearer),
            bearer -> authorized(get(TEMPLATES + "/" + TEMPLATE + "/versions/" + VERSION + "/file"), bearer));

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    private UUID stored;

    @BeforeAll
    static void ownerRole() {
        ReportsTestSupport.createOwnerRole(); // removes the stored template as the ledger owner
    }

    @AfterEach
    void removeStoredTemplate() {
        if (stored == null) {
            return;
        }
        String id = stored.toString();
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from event_publication where completion_date is null and serialized_event like ?",
                Integer.class, "%" + id + "%") == 0);
        ReportsTestSupport.asLedgerOwner(sql -> {
            sql.update("delete from report_template_version where template_id = ?", stored);
            sql.update("delete from report_template where id = ?", stored);
            sql.update("delete from audit_entry where entity_id = ?", stored);
        });
    }

    @Test
    void aRequestWithoutATokenIsUnauthenticated() throws Exception {
        for (Function<String, RequestBuilder> operation : OPERATIONS) {
            mvc.perform(operation.apply(null))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Test
    void aMemberTheElectricianAndThePlatformOperatorAreRefusedEverywhere() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();
        for (String caller : List.of(demo("member@demo.local"), demo("electrician@demo.local"), operator)) {
            for (Function<String, RequestBuilder> operation : OPERATIONS) {
                mvc.perform(operation.apply(caller))
                        .andExpect(status().isForbidden())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            }
        }
    }

    @Test
    void theTreasurerReachesTheListTheCatalogAndGeneratingAndManagesNothing() throws Exception {
        String treasurer = demo("treasurer@demo.local");
        mvc.perform(OPERATIONS.getFirst().apply(treasurer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slot").value("TAX"))
                .andExpect(jsonPath("$[0].status").value("NOT_ADDED"));
        mvc.perform(OPERATIONS.get(1).apply(treasurer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("cooperative.name"));
        // generating: an unknown template is 404 once the permission is checked, a stored one is filled
        mvc.perform(OPERATIONS.get(2).apply(treasurer)).andExpect(status().isNotFound());
        mvc.perform(generate(storeTemplate(), treasurer))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Report-Warnings", "0"));
        for (Function<String, RequestBuilder> operation : OPERATIONS.subList(FOR_VIEWERS, OPERATIONS.size())) {
            mvc.perform(operation.apply(treasurer))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }

    @Test
    void theChairmanReachesEveryOperation() throws Exception {
        String chairman = demo("chairman@demo.local");
        List<Integer> statuses = List.of(200, 200, 404, 400, 404, 400, 404, 404);
        for (int i = 0; i < OPERATIONS.size(); i++) {
            mvc.perform(OPERATIONS.get(i).apply(chairman))
                    .andExpect(status().is(statuses.get(i)));
        }
        mvc.perform(OPERATIONS.get(FOR_VIEWERS).apply(chairman))
                .andExpect(jsonPath("$.code").value("TEMPLATE_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("file"))
                .andExpect(jsonPath("$.errors[0].code").value("BROKEN_ZIP"));
        mvc.perform(OPERATIONS.get(FOR_VIEWERS + 1).apply(chairman)).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(generate(storeTemplate(), chairman))
                .andExpect(status().isOk())
                .andExpect(content().contentType(ReportTemplatesController.XLSX));
    }

    /** A valid template stored in the demo cooperative by its chairman; removed after the test. */
    private UUID storeTemplate() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "statement.xlsx",
                ReportTemplatesController.XLSX.toString(), TemplateFiles.valid());
        String created = mvc.perform(authorized(multipart(TEMPLATES).file(file).param("name",
                        "Access " + UUID.randomUUID()), demo("chairman@demo.local")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        stored = UUID.fromString(json.readTree(created).at("/template/id").asString());
        return stored;
    }

    private static RequestBuilder generate(UUID template, String bearer) {
        return authorized(post(TEMPLATES + "/" + template + "/generate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\": \"2025-01-01\", \"to\": \"2025-01-31\"}"), bearer);
    }

    private static <B extends AbstractMockHttpServletRequestBuilder<B>> B authorized(B request, String bearer) {
        return bearer == null ? request : request.header(HttpHeaders.AUTHORIZATION, bearer);
    }

    /**
     * The zip signature and nothing more: the permission is checked before the file is read, and a chairman's upload
     * is refused as a damaged archive.
     */
    private static MockMultipartFile workbook() {
        return new MockMultipartFile("file", "statement.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {'P', 'K', 3, 4});
    }

    /** A token as Keycloak would issue it for a demo user; the subject is stable per e-mail. */
    private static String demo(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }
}
