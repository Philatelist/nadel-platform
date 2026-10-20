package app.nadel.imports.internal;

import static app.nadel.imports.internal.ImportWorkbooks.MEMBER_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.OWNERSHIP_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.PLOT_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.member;
import static app.nadel.imports.internal.ImportWorkbooks.plot;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base of the tests that commit imports. Everything an import creates in the demo cooperative has a recognisable
 * name (plot numbers {@code CMT-}, {@code IMP-} and {@code LRG-}, member last names {@code Fixt}, e-mails at
 * {@code import.example.com}), and is removed after each test with the audit entries and owners that came with it,
 * so no test sees another's data and the demo data is as it was.
 */
abstract class ImportCommitSupport extends ImportsIntegrationTest {

    protected static final JsonMapper JSON = JsonMapper.builder().build();
    /** Demo plot 26 is free in the demo data: a first owner can be recorded on it. */
    protected static final String FREE_DEMO_PLOT = "26";

    protected final String tag = UUID.randomUUID().toString().substring(0, 8);

    protected String plotNo(String name) {
        return "CMT-" + tag + "-" + name;
    }

    protected String email(String local) {
        return local + "." + tag + "@import.example.com";
    }

    /** A new plot of 600 square metres. */
    protected List<Object> newPlot(String name) {
        return plot(plotNo(name), 600);
    }

    /** A new member found by its e-mail on a later import. */
    protected List<Object> newMember(String key, String name) {
        return member(key, "Fixt" + tag + name, "Person", email(name.toLowerCase()));
    }

    protected static byte[] workbook(List<List<?>> plots, List<List<?>> members, List<List<?>> ownership) {
        return ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER, plots.toArray(List<?>[]::new))
                .sheet("Members", MEMBER_HEADER, members.toArray(List<?>[]::new))
                .sheet("Ownership", OWNERSHIP_HEADER, ownership.toArray(List<?>[]::new))
                .build();
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /** Uploads the file as the chairman and returns the preview, which must have been accepted. */
    protected JsonNode preview(byte[] file) throws Exception {
        return body(upload(file).andExpect(status().isCreated()).andReturn());
    }

    protected ResultActions commit(String authorization, String importId) throws Exception {
        return mvc.perform(post(IMPORTS + "/" + importId + "/commit").header(HttpHeaders.AUTHORIZATION, authorization));
    }

    protected ResultActions commit(String importId) throws Exception {
        return commit(chairman(), importId);
    }

    protected Map<String, Object> runRow(String importId) {
        return ownerJdbc().queryForMap("select * from import_run where id = ?::uuid", importId);
    }

    protected static UUID plotId(String importId, String number) {
        return derived(importId, "plot", number);
    }

    protected static UUID memberId(String importId, String key) {
        return derived(importId, "member", key);
    }

    private static UUID derived(String importId, String kind, String key) {
        return UUID.nameUUIDFromBytes((importId + "/" + kind + "/" + key.toLowerCase()).getBytes(StandardCharsets.UTF_8));
    }

    protected int plotsLike(String prefix) {
        return ownerJdbc().queryForObject("select count(*) from plot where tenant_id = ? and number like ?",
                Integer.class, DEMO_TENANT, prefix + "%");
    }

    protected int membersLike(String lastNamePrefix) {
        return ownerJdbc().queryForObject("select count(*) from member where tenant_id = ? and last_name like ?",
                Integer.class, DEMO_TENANT, lastNamePrefix + "%");
    }

    protected int ownershipOfPlotsLike(String prefix) {
        return ownerJdbc().queryForObject("select count(*) from ownership o join plot p on p.id = o.plot_id "
                + "where p.tenant_id = ? and p.number like ?", Integer.class, DEMO_TENANT, prefix + "%");
    }

    protected String statusOfPlot(String number) {
        return ownerJdbc().queryForObject("select s.status from plot_status s join plot p on p.id = s.plot_id "
                + "where p.tenant_id = ? and p.number = ?", String.class, DEMO_TENANT, number);
    }

    /** Audit entries of the demo cooperative with this action whose label starts with the prefix. */
    protected int auditLike(String action, String labelPrefix) {
        return ownerJdbc().queryForObject("select count(*) from audit_entry where tenant_id = ? and action = ? "
                + "and entity_label like ?", Integer.class, DEMO_TENANT, action, labelPrefix + "%");
    }

    /** The audit entries are written after the commit by an asynchronous listener. */
    protected void awaitAudit(String action, String labelPrefix, int expected) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                org.assertj.core.api.Assertions.assertThat(auditLike(action, labelPrefix)).isEqualTo(expected));
    }

    @AfterEach
    void removeImportedRecords() {
        try {
            // every audit entry still owed is written first, so none appears after the cleanup
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() ->
                    ownerJdbc().queryForObject("select count(*) from event_publication", Integer.class) == 0);
        } catch (ConditionTimeoutException e) {
            // clean up anyway
        }
        JdbcTemplate owner = ownerJdbc();
        String plots = ids(owner, "select id from plot where tenant_id = ? and number ~ '^(CMT|IMP|LRG)-'");
        String members = ids(owner, "select id from member where tenant_id = ? and (last_name like 'Fixt%' "
                + "or email like '%@import.example.com')");
        String freePlot = ids(owner, "select id from plot where tenant_id = ? and number = '" + FREE_DEMO_PLOT + "'");
        owner.update("delete from audit_entry where tenant_id = ? and (entity_type = 'IMPORT' "
                + "or entity_id = any (string_to_array(?, ',')::uuid[]) "
                + "or entity_id = any (string_to_array(?, ',')::uuid[]) "
                + "or entity_id = any (string_to_array(?, ',')::uuid[]))", DEMO_TENANT, plots, members, freePlot);
        owner.update("delete from ownership where plot_id = any (string_to_array(?, ',')::uuid[]) "
                + "or member_id = any (string_to_array(?, ',')::uuid[]) "
                + "or (plot_id = any (string_to_array(?, ',')::uuid[]) and id::text not like 'dddddddd-%')",
                plots, members, freePlot);
        owner.update("delete from plot_status where plot_id = any (string_to_array(?, ',')::uuid[])", plots);
        owner.update("delete from plot where id = any (string_to_array(?, ',')::uuid[])", plots);
        owner.update("delete from member where id = any (string_to_array(?, ',')::uuid[])", members);
        owner.update("update plot_status set status = 'GREY' where plot_id = any (string_to_array(?, ',')::uuid[])",
                freePlot);
    }

    private static String ids(JdbcTemplate owner, String sql) {
        return String.join(",", owner.queryForList(sql, UUID.class, DEMO_TENANT).stream()
                .map(UUID::toString).toList());
    }
}
