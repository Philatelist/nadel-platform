package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * GET /imports: the import history of the cooperative, newest first, with the derived EXPIRED status, paged, only for
 * people who may run imports, and never with the stored rows.
 */
class ImportHistoryTests extends ImportCommitSupport {

    private static final String COUNTS = "{\"plots\": {\"created\": 3, \"updated\": 2, \"unchanged\": 1}, "
            + "\"members\": {\"created\": 4, \"updated\": 0, \"unchanged\": 5}, "
            + "\"ownerships\": {\"created\": 6, \"updated\": 0, \"unchanged\": 7}}";

    private static final String SECRET_PLAN = "{\"version\": 1, \"plots\": [{\"number\": \"SECRET-PLOT-777\"}], "
            + "\"members\": [{\"lastName\": \"SecretSurname\"}]}";

    private final Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private ResultActions list(String authorization, String query) throws Exception {
        return mvc.perform(get(IMPORTS + query).header(HttpHeaders.AUTHORIZATION, authorization));
    }

    private ResultActions list(String query) throws Exception {
        return list(chairman(), query);
    }

    /**
     * Inserts a run directly (the application role cannot be used for fixtures with a past upload time).
     * Defaults: author is a removed member, expires 30 minutes after it was uploaded.
     */
    private UUID insert(UUID tenant, String fileName, Instant createdAt, Instant expiresAt, String status,
            Instant committedAt, UUID memberId, String authorName, String plan, int problems) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into import_run (id, tenant_id, file_name, created_by_member_id, "
                        + "created_by_subject, created_by_name, created_at, expires_at, committed_at, status, counts, "
                        + "problem_count, plan, plan_digest) values (?, ?, ?, ?, 's', ?, ?::timestamptz, "
                        + "?::timestamptz, ?::timestamptz, ?, ?::jsonb, ?, ?::jsonb, ?)",
                id, tenant, fileName, memberId, authorName, createdAt.toString(), expiresAt.toString(),
                committedAt == null ? null : committedAt.toString(), status, COUNTS, problems, plan,
                plan == null ? null : "digest");
        return id;
    }

    private UUID insert(String fileName, Instant createdAt, String status) {
        return insert(DEMO_TENANT, fileName, createdAt, createdAt.plus(30, ChronoUnit.MINUTES), status,
                "COMMITTED".equals(status) ? createdAt.plus(1, ChronoUnit.MINUTES) : null, null, "Snapshot Author",
                null, 0);
    }

    private static Instant ago(Instant from, long minutes) {
        return from.minus(minutes, ChronoUnit.MINUTES);
    }

    // ---- order, statuses, shape

    @Test
    void theHistoryIsListedNewestFirst() throws Exception {
        insert("old.xlsx", ago(base, 300), "HAS_ERRORS");
        insert("newest.xlsx", ago(base, 100), "HAS_ERRORS");
        insert("middle.xlsx", ago(base, 200), "HAS_ERRORS");

        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].fileName").value("newest.xlsx"))
                .andExpect(jsonPath("$.items[1].fileName").value("middle.xlsx"))
                .andExpect(jsonPath("$.items[2].fileName").value("old.xlsx"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void uploadsOfTheSameInstantAreOrderedStably() throws Exception {
        for (int i = 0; i < 4; i++) {
            insert("same-" + i + ".xlsx", base, "HAS_ERRORS");
        }

        List<String> first = names(list("?size=2&page=0"));
        List<String> second = names(list("?size=2&page=1"));

        assertThat(first).doesNotContainAnyElementsOf(second).hasSize(2);
        assertThat(second).hasSize(2);
    }

    @Test
    void everyStatusIsShownIncludingExpiredWhichIsDerived() throws Exception {
        // a preview still valid, and one whose 30 minutes passed without a commit
        insert(DEMO_TENANT, "valid.xlsx", ago(base, 1), base.plus(29, ChronoUnit.MINUTES), "PREVIEW", null, null,
                "Snapshot Author", SECRET_PLAN, 0);
        insert(DEMO_TENANT, "expired.xlsx", ago(base, 120), ago(base, 90), "PREVIEW", null, null, "Snapshot Author",
                null, 0);
        // the plan of an expired preview may still be stored (cleared lazily): it is expired all the same
        insert(DEMO_TENANT, "expired-with-plan.xlsx", ago(base, 130), ago(base, 100), "PREVIEW", null, null,
                "Snapshot Author", SECRET_PLAN, 0);
        insert(DEMO_TENANT, "errors.xlsx", ago(base, 140), ago(base, 110), "HAS_ERRORS", null, null,
                "Snapshot Author", null, 7);
        insert("committed.xlsx", ago(base, 150), "COMMITTED");
        insert("stale.xlsx", ago(base, 160), "STALE");

        MvcResult result = list("").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(6))).andReturn();

        JsonNode items = body(result).get("items");
        assertThat(statusOf(items, "valid.xlsx")).isEqualTo("PREVIEW");
        assertThat(statusOf(items, "expired.xlsx")).isEqualTo("EXPIRED");
        assertThat(statusOf(items, "expired-with-plan.xlsx")).isEqualTo("EXPIRED");
        assertThat(statusOf(items, "errors.xlsx")).isEqualTo("HAS_ERRORS");
        assertThat(statusOf(items, "committed.xlsx")).isEqualTo("COMMITTED");
        assertThat(statusOf(items, "stale.xlsx")).isEqualTo("STALE");
        // an expired preview is not stored as such
        assertThat(ownerJdbc().queryForObject("select count(*) from import_run where status = 'EXPIRED'",
                Integer.class)).isZero();
    }

    @Test
    void anItemCarriesFileAuthorTimesStatusAndCounts() throws Exception {
        Instant uploaded = ago(base, 10);
        Instant committed = uploaded.plus(2, ChronoUnit.MINUTES);
        insert(DEMO_TENANT, "committed.xlsx", uploaded, uploaded.plus(30, ChronoUnit.MINUTES), "COMMITTED", committed,
                null, "Snapshot Author", null, 0);

        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").isNotEmpty())
                .andExpect(jsonPath("$.items[0].fileName").value("committed.xlsx"))
                .andExpect(jsonPath("$.items[0].uploadedBy").value("Snapshot Author"))
                .andExpect(jsonPath("$.items[0].status").value("COMMITTED"))
                .andExpect(jsonPath("$.items[0].problemCount").value(0))
                .andExpect(jsonPath("$.items[0].counts.plots.created").value(3))
                .andExpect(jsonPath("$.items[0].counts.plots.updated").value(2))
                .andExpect(jsonPath("$.items[0].counts.plots.unchanged").value(1))
                .andExpect(jsonPath("$.items[0].counts.members.created").value(4))
                .andExpect(jsonPath("$.items[0].counts.members.unchanged").value(5))
                .andExpect(jsonPath("$.items[0].counts.ownerships.created").value(6))
                .andExpect(jsonPath("$.items[0].counts.ownerships.unchanged").value(7))
                .andExpect(jsonPath("$.items[0].uploadedAt").value(org.hamcrest.Matchers.startsWith(
                        uploaded.toString().substring(0, 19))))
                .andExpect(jsonPath("$.items[0].committedAt").value(org.hamcrest.Matchers.startsWith(
                        committed.toString().substring(0, 19))));
    }

    @Test
    void onlyACommittedImportHasACommitTimeAndAFileWithProblemsReportsTheirNumber() throws Exception {
        insert(DEMO_TENANT, "errors.xlsx", ago(base, 5), ago(base, -25), "HAS_ERRORS", null, null, "Snapshot Author",
                null, 12);

        list("")
                .andExpect(jsonPath("$.items[0].status").value("HAS_ERRORS"))
                .andExpect(jsonPath("$.items[0].problemCount").value(12))
                .andExpect(jsonPath("$.items[0].committedAt").doesNotExist());
    }

    // ---- who uploaded

    @Test
    void theAuthorIsShownByTheirCurrentNameLikeInTheAuditLog() throws Exception {
        UUID member = newMember(DEMO_TENANT, "HistRenamed", "Now", "Current", "hist." + tag + "@import.example.com");
        insert(DEMO_TENANT, "renamed.xlsx", ago(base, 1), base.plus(29, ChronoUnit.MINUTES), "HAS_ERRORS", null,
                member, "Old Name Atupload", null, 1);

        list("").andExpect(jsonPath("$.items[0].uploadedBy").value("HistRenamed Now Current"));
    }

    @Test
    void aRemovedAuthorKeepsTheNameFromTheUpload() throws Exception {
        insert(DEMO_TENANT, "gone.xlsx", ago(base, 1), base.plus(29, ChronoUnit.MINUTES), "HAS_ERRORS", null,
                UUID.randomUUID(), "Name Atupload", null, 1);

        list("").andExpect(jsonPath("$.items[0].uploadedBy").value("Name Atupload"));
    }

    // ---- paging

    @Test
    void pagesCarryTheirPartOfTheHistoryAndTheTotals() throws Exception {
        for (int i = 0; i < 5; i++) {
            insert("page-" + i + ".xlsx", ago(base, 10L * (i + 1)), "HAS_ERRORS");
        }

        list("?page=0&size=2")
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].fileName").value("page-0.xlsx"))
                .andExpect(jsonPath("$.items[1].fileName").value("page-1.xlsx"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
        list("?page=2&size=2")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].fileName").value("page-4.xlsx"))
                .andExpect(jsonPath("$.page").value(2));
        list("?page=3&size=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalItems").value(5));
    }

    @Test
    void anEmptyHistoryIsAnEmptyPage() throws Exception {
        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void invalidPagingIsRejected() throws Exception {
        for (String query : List.of("?size=101", "?size=0", "?page=-1")) {
            list(query).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        list("?size=abc").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // ---- the real flow

    @Test
    void uploadsAndCommitsShowUpInTheHistory() throws Exception {
        String valid = preview(workbook(List.of(newPlot("a")), List.of(), List.of())).get("id").asString();
        JsonNode broken = preview(workbook(List.of(plotWithoutArea("bad")), List.of(), List.of()));
        String brokenId = broken.get("id").asString();
        String committedId = preview(workbook(List.of(newPlot("b")), List.of(), List.of())).get("id").asString();
        commit(committedId).andExpect(status().isOk());

        MvcResult result = list("").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(3))).andReturn();

        JsonNode items = body(result).get("items");
        assertThat(byId(items, valid).get("status").asString()).isEqualTo("PREVIEW");
        assertThat(byId(items, brokenId).get("status").asString()).isEqualTo("HAS_ERRORS");
        assertThat(byId(items, brokenId).get("problemCount").asInt()).isPositive();
        JsonNode committed = byId(items, committedId);
        assertThat(committed.get("status").asString()).isEqualTo("COMMITTED");
        assertThat(committed.get("committedAt").asString()).isNotBlank();
        assertThat(committed.get("counts").get("plots").get("created").asInt()).isEqualTo(1);
        assertThat(committed.get("uploadedBy").asString()).contains("Kovalenko");
        assertThat(committed.get("fileName").asString()).isEqualTo("plots.xlsx");
        // newest first: the commit was the last upload
        assertThat(items.get(0).get("id").asString()).isEqualTo(committedId);
    }

    @Test
    void aStaleImportIsShownAsStale() throws Exception {
        String id = preview(workbook(List.of(newPlot("s")), List.of(), List.of())).get("id").asString();
        // a plot with the file's number appears meanwhile: the commit is refused as stale
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 600)",
                UUID.randomUUID(), DEMO_TENANT, plotNo("s"));
        commit(id).andExpect(status().isConflict());

        list("").andExpect(jsonPath("$.items[0].id").value(id)).andExpect(jsonPath("$.items[0].status").value("STALE"));
    }

    private List<Object> plotWithoutArea(String name) {
        return ImportWorkbooks.plot(plotNo(name), null, "PLOT", null, null, null, null, null);
    }

    // ---- the stored plan, other cooperatives

    @Test
    void theStoredPlanIsNeverReturned() throws Exception {
        insert(DEMO_TENANT, "with-plan.xlsx", ago(base, 1), base.plus(29, ChronoUnit.MINUTES), "PREVIEW", null, null,
                "Snapshot Author", SECRET_PLAN, 0);

        MvcResult result = list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].plan").doesNotExist())
                .andExpect(jsonPath("$.items[0].planDigest").doesNotExist())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        assertThat(json).doesNotContain("SECRET-PLOT-777").doesNotContain("SecretSurname").doesNotContain("digest");
        assertThat(ownerJdbc().queryForObject("select plan is not null from import_run where file_name = ?",
                Boolean.class, "with-plan.xlsx")).isTrue();
    }

    @Test
    void anotherCooperativesRunsAreNeverListed() throws Exception {
        insert("mine.xlsx", ago(base, 5), "HAS_ERRORS");
        insert(OTHER_TENANT, "theirs.xlsx", ago(base, 1), base.plus(29, ChronoUnit.MINUTES), "PREVIEW", null, null,
                "Other Author", SECRET_PLAN, 0);

        list("")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].fileName").value("mine.xlsx"))
                .andExpect(jsonPath("$.totalItems").value(1));
        // the other cooperative's chairman, if there was one, would see theirs only: the owner-level check
        assertThat(ownerJdbc().queryForObject("select count(*) from import_run", Integer.class)).isEqualTo(2);
    }

    // ---- access

    @Test
    void peopleWithoutImportRunAreRefused() throws Exception {
        insert("x.xlsx", ago(base, 5), "HAS_ERRORS");
        for (String email : List.of("treasurer@demo.local", "member@demo.local", "electrician@demo.local")) {
            list(demo(email), "").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }

    @Test
    void thePlatformOperatorIsRefused() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        list(operator, "").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void anonymousCallersGet401() throws Exception {
        mvc.perform(get(IMPORTS)).andExpect(status().isUnauthorized());
    }

    // ---- helpers

    private List<String> names(ResultActions actions) throws Exception {
        JsonNode items = body(actions.andExpect(status().isOk()).andReturn()).get("items");
        return items.valueStream().map(item -> item.get("fileName").asString()).toList();
    }

    private static String statusOf(JsonNode items, String fileName) {
        return items.valueStream().filter(item -> fileName.equals(item.get("fileName").asString()))
                .findFirst().orElseThrow().get("status").asString();
    }

    private static JsonNode byId(JsonNode items, String id) {
        return items.valueStream().filter(item -> id.equals(item.get("id").asString()))
                .findFirst().orElseThrow();
    }
}
