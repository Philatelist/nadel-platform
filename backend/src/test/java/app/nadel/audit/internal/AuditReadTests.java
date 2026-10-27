package app.nadel.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GET /audit: permission, filters, the Europe/Kyiv date range, ordering, paging, tenant isolation, actor names,
 * field changes and sealed personal values (shown only with MEMBERS_VIEW, otherwise hidden).
 * <p>
 * {@code audit_entry} is append-only for the application role, so fixture rows cannot be removed. Every test therefore
 * writes its rows with ids that occur nowhere else (a fresh entity id and, where the test needs one, a fresh actor)
 * and asserts through filters on those ids, so leftover rows never disturb other tests.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuditReadTests extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private final List<UUID> apiCreatedMembers = new ArrayList<>();
    private final List<UUID> customRoles = new ArrayList<>();

    @AfterEach
    void removeMembersCreatedThroughTheApi() {
        for (UUID id : apiCreatedMembers) {
            inTenant(DEMO_TENANT, sql -> sql.update("delete from member where id = ?", id));
        }
        for (UUID role : customRoles) {
            inTenant(DEMO_TENANT, sql -> {
                sql.update("delete from member_role where role_id = ?", role);
                sql.update("delete from role_permission where role_id = ?", role);
                return sql.update("delete from role where id = ?", role);
            });
        }
    }

    // ---- permission -------------------------------------------------------------------------------------------

    @Test
    void aHolderOfAuditViewReadsTheLog() throws Exception {
        UUID entity = UUID.randomUUID();
        UUID entry = insert(DEMO_TENANT, "2026-05-10T10:00:00Z", null, "MEMBER_CREATED", "MEMBER", entity, "Label", "[]");

        getAs(auditViewer(DEMO_TENANT), "/api/v1/audit?entityId=" + entity)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(entry.toString()));
    }

    @Test
    void theDemoTreasurerHoldsAuditView() throws Exception {
        // Demo data: the treasurer base role carries AUDIT_VIEW.
        // the token carries a random subject, so the treasurer must start unlinked whatever ran before
        unlinkByEmail(DEMO_TENANT, "treasurer@demo.local");
        String token = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), "treasurer@demo.local");
        try {
            getAs(token, "/api/v1/audit?size=1").andExpect(status().isOk());
        } finally {
            unlinkByEmail(DEMO_TENANT, "treasurer@demo.local");
        }
    }

    @Test
    void aCallerWithoutAuditViewIsDenied() throws Exception {
        UUID plain = newMember(DEMO_TENANT, "plain-" + tag() + "@demo.local");
        giveBaseRole(DEMO_TENANT, plain, "MEMBER");
        String token = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), emailOf(DEMO_TENANT, plain));

        getAs(token, "/api/v1/audit")
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void thePlatformOperatorIsDenied() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        getAs(operator, "/api/v1/audit")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void aTokenIsRequired() throws Exception {
        mvc.perform(get("/api/v1/audit"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---- content ----------------------------------------------------------------------------------------------

    @Test
    void entriesCarryTheActorsCurrentNameAndTheFieldChanges() throws Exception {
        String tag = tag();
        UUID actor = newMember(DEMO_TENANT, "Actor" + tag, "Anna", "Petrivna", null);
        UUID entity = UUID.randomUUID();
        UUID entry = insert(DEMO_TENANT, "2026-05-10T10:00:00Z", actor, "MEMBER_UPDATED", "MEMBER", entity,
                "Old Label", """
                        [{"field": "phones", "old": "+380501112233", "new": "+380671112233"},
                         {"field": "middleName", "old": null, "new": "Ivanovych"},
                         {"field": "email", "old": "a@example.com", "new": null}]""");
        // the name is resolved when the log is read, not when the entry was written
        inTenant(DEMO_TENANT, sql -> sql.update("update member set last_name = ? where id = ?", "Renamed" + tag, actor));

        getAs(auditViewer(DEMO_TENANT), "/api/v1/audit?entityId=" + entity)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(entry.toString()))
                .andExpect(jsonPath("$.items[0].occurredAt").value("2026-05-10T10:00:00Z"))
                .andExpect(jsonPath("$.items[0].actorMemberId").value(actor.toString()))
                .andExpect(jsonPath("$.items[0].actorName").value("Renamed" + tag + " Anna Petrivna"))
                .andExpect(jsonPath("$.items[0].action").value("MEMBER_UPDATED"))
                .andExpect(jsonPath("$.items[0].entityType").value("MEMBER"))
                .andExpect(jsonPath("$.items[0].entityId").value(entity.toString()))
                .andExpect(jsonPath("$.items[0].entityLabel").value("Old Label"))
                .andExpect(jsonPath("$.items[0].changes", hasSize(3)))
                .andExpect(jsonPath("$.items[0].changes[0].field").value("phones"))
                .andExpect(jsonPath("$.items[0].changes[0].oldValue").value("+380501112233"))
                .andExpect(jsonPath("$.items[0].changes[0].newValue").value("+380671112233"))
                .andExpect(jsonPath("$.items[0].changes[1].field").value("middleName"))
                .andExpect(jsonPath("$.items[0].changes[1].oldValue").doesNotExist())
                .andExpect(jsonPath("$.items[0].changes[1].newValue").value("Ivanovych"))
                .andExpect(jsonPath("$.items[0].changes[2].field").value("email"))
                .andExpect(jsonPath("$.items[0].changes[2].oldValue").value("a@example.com"))
                .andExpect(jsonPath("$.items[0].changes[2].newValue").doesNotExist());
    }

    @Test
    void anEntryWithoutAnActorOrWithAMissingActorHasNoName() throws Exception {
        UUID missingActor = UUID.randomUUID();
        UUID system = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        insert(DEMO_TENANT, "2026-05-10T10:00:00Z", null, "MEMBER_CREATED", "MEMBER", system, null, "[]");
        insert(DEMO_TENANT, "2026-05-10T10:00:00Z", missingActor, "MEMBER_CREATED", "MEMBER", gone, null, "[]");
        String token = auditViewer(DEMO_TENANT);

        getAs(token, "/api/v1/audit?entityId=" + system)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].actorMemberId").doesNotExist())
                .andExpect(jsonPath("$.items[0].actorName").doesNotExist())
                .andExpect(jsonPath("$.items[0].entityLabel").doesNotExist())
                .andExpect(jsonPath("$.items[0].changes", hasSize(0)));
        getAs(token, "/api/v1/audit?entityId=" + gone)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].actorMemberId").value(missingActor.toString()))
                .andExpect(jsonPath("$.items[0].actorName").doesNotExist());
    }

    @Test
    void aChangeMadeThroughTheMembersApiShowsUpInTheLog() throws Exception {
        UUID chairman = newMember(DEMO_TENANT, "chairman-" + tag() + "@demo.local");
        giveBaseRole(DEMO_TENANT, chairman, "CHAIRMAN");
        String token = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), emailOf(DEMO_TENANT, chairman));
        String tag = tag();

        String created = mvc.perform(post("/api/v1/members").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\": \"Api" + tag + "\", \"firstName\": \"Created\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID member = UUID.fromString(json.readTree(created).path("id").asString());
        apiCreatedMembers.add(member);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                getAs(token, "/api/v1/audit?entityId=" + member)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalItems").value(1))
                        .andExpect(jsonPath("$.items[0].action").value("MEMBER_CREATED"))
                        .andExpect(jsonPath("$.items[0].entityType").value("MEMBER"))
                        .andExpect(jsonPath("$.items[0].entityLabel").value("Api" + tag + " Created"))
                        .andExpect(jsonPath("$.items[0].actorMemberId").value(chairman.toString()))
                        .andExpect(jsonPath("$.items[0].actorName").value("Test Member")));
    }

    // ---- personal values (feature 014, ADR 0016) ---------------------------------------------------------------

    @Test
    void personalValuesAreHiddenFromAReaderWhoMayNotViewMembers() throws Exception {
        MemberUpdate update = memberWithAPhoneChange();
        String auditOnly = auditViewOnly(DEMO_TENANT);

        JsonNode entry = updatedEntry(auditOnly, update.member());
        JsonNode phones = field(entry, "phones");
        assertThat(phones.path("hidden").asBoolean()).isTrue();
        assertThat(phones.has("oldValue")).isFalse();
        assertThat(phones.has("newValue")).isFalse();
        // a field without personal data is shown as before
        JsonNode lastName = field(entry, "lastName");
        assertThat(lastName.path("hidden").asBoolean()).isFalse();
        assertThat(lastName.get("oldValue").asString()).isEqualTo(update.oldLastName());
        assertThat(lastName.get("newValue").asString()).isEqualTo(update.newLastName());
        assertThat(entry.toString()).doesNotContain(update.oldPhoneDigits()).doesNotContain(update.newPhoneDigits());
    }

    @Test
    void personalValuesAreShownToTheChairmanAndTheTreasurer() throws Exception {
        MemberUpdate update = memberWithAPhoneChange();

        for (String reader : List.of(update.chairman(), auditViewer(DEMO_TENANT))) {
            JsonNode phones = field(updatedEntry(reader, update.member()), "phones");
            assertThat(phones.path("hidden").asBoolean()).isFalse();
            assertThat(phones.get("oldValue").asString()).isEqualTo("+" + update.oldPhoneDigits());
            assertThat(phones.get("newValue").asString()).isEqualTo("+" + update.newPhoneDigits());
        }
    }

    @Test
    void aSealedValueCopiedToAnotherEntryIsHiddenAndOnlyTheEntryIsLogged(CapturedOutput output) throws Exception {
        MemberUpdate update = memberWithAPhoneChange();
        UUID original = UUID.fromString(updatedEntry(update.chairman(), update.member()).get("id").asString());
        UUID copy = UUID.randomUUID();
        UUID copyEntity = UUID.randomUUID();
        // someone with database access copies the ciphertext of one entry into another
        ownerJdbc().update("""
                insert into audit_entry (id, tenant_id, occurred_at, actor_member_id, actor_subject, action,
                                         entity_type, entity_id, entity_label, changes)
                select ?, tenant_id, occurred_at, actor_member_id, actor_subject, action, entity_type, ?, entity_label,
                       changes
                from audit_entry where id = ?
                """, copy, copyEntity, original);

        JsonNode copied = getJson(update.chairman(), "/api/v1/audit?entityId=" + copyEntity).path("items").get(0);

        JsonNode phones = field(copied, "phones");
        assertThat(phones.path("hidden").asBoolean()).isTrue();
        assertThat(phones.has("oldValue")).isFalse();
        assertThat(phones.has("newValue")).isFalse();
        assertThat(field(copied, "lastName").get("newValue").asString()).isEqualTo(update.newLastName());
        assertThat(output.getAll())
                .contains("PERSONAL_DATA_UNREADABLE: a sealed value of audit entry " + copy + " could not be opened")
                .doesNotContain(update.oldPhoneDigits()).doesNotContain(update.newPhoneDigits());
        // the original still opens where it was sealed
        assertThat(field(updatedEntry(update.chairman(), update.member()), "phones").get("newValue").asString())
                .isEqualTo("+" + update.newPhoneDigits());
    }

    // ---- filters ----------------------------------------------------------------------------------------------

    @Test
    void filtersByEntityType() throws Exception {
        UUID actor = UUID.randomUUID(); // scopes the assertions to this test's rows
        UUID memberEntry = insert(DEMO_TENANT, "2026-05-10T10:00:00Z", actor, "MEMBER_UPDATED", "MEMBER",
                UUID.randomUUID(), null, "[]");
        UUID roleEntry = insert(DEMO_TENANT, "2026-05-10T11:00:00Z", actor, "ROLE_CREATED", "ROLE",
                UUID.randomUUID(), null, "[]");
        String token = auditViewer(DEMO_TENANT);

        assertThat(idsOf(getJson(token, "/api/v1/audit?actorMemberId=" + actor))).containsExactly(roleEntry, memberEntry);
        assertThat(idsOf(getJson(token, "/api/v1/audit?actorMemberId=" + actor + "&entityType=MEMBER")))
                .containsExactly(memberEntry);
        assertThat(idsOf(getJson(token, "/api/v1/audit?actorMemberId=" + actor + "&entityType=ROLE")))
                .containsExactly(roleEntry);
    }

    @Test
    void filtersByEntityId() throws Exception {
        UUID entity = UUID.randomUUID();
        UUID mine = insert(DEMO_TENANT, "2026-05-10T10:00:00Z", null, "MEMBER_UPDATED", "MEMBER", entity, null, "[]");
        insert(DEMO_TENANT, "2026-05-10T10:00:00Z", null, "MEMBER_UPDATED", "MEMBER", UUID.randomUUID(), null, "[]");

        assertThat(idsOf(getJson(auditViewer(DEMO_TENANT), "/api/v1/audit?entityId=" + entity))).containsExactly(mine);
    }

    @Test
    void filtersByActorMemberId() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID mine = insert(DEMO_TENANT, "2026-05-10T10:00:00Z", actor, "MEMBER_UPDATED", "MEMBER",
                UUID.randomUUID(), null, "[]");
        insert(DEMO_TENANT, "2026-05-10T10:00:00Z", other, "MEMBER_UPDATED", "MEMBER", UUID.randomUUID(), null, "[]");

        assertThat(idsOf(getJson(auditViewer(DEMO_TENANT), "/api/v1/audit?actorMemberId=" + actor)))
                .containsExactly(mine);
    }

    @Test
    void filtersCombineWithAnd() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        UUID match = insert(DEMO_TENANT, "2026-05-10T10:00:00Z", actor, "MEMBER_UPDATED", "MEMBER", entity, null, "[]");
        insert(DEMO_TENANT, "2026-05-10T10:00:00Z", UUID.randomUUID(), "MEMBER_UPDATED", "MEMBER", entity, null, "[]");
        insert(DEMO_TENANT, "2026-05-10T10:00:00Z", actor, "ROLE_UPDATED", "ROLE", entity, null, "[]");

        assertThat(idsOf(getJson(auditViewer(DEMO_TENANT),
                "/api/v1/audit?entityId=" + entity + "&actorMemberId=" + actor + "&entityType=MEMBER")))
                .containsExactly(match);
    }

    // ---- date range: whole days in Europe/Kyiv, both ends inclusive ----------------------------------------------

    @Test
    void theRangeStartsAtKyivMidnightInWinter() throws Exception {
        // 2026-03-14 is before the switch to summer time: Kyiv is UTC+2, so Kyiv midnight is 22:00 UTC the day before.
        UUID actor = UUID.randomUUID();
        UUID lastSecondOfThe13th = insert(DEMO_TENANT, "2026-03-13T21:59:59Z", actor, "MEMBER_UPDATED", "MEMBER",
                UUID.randomUUID(), null, "[]");
        UUID firstInstantOfThe14th = insert(DEMO_TENANT, "2026-03-13T22:00:00Z", actor, "MEMBER_UPDATED", "MEMBER",
                UUID.randomUUID(), null, "[]");
        String token = auditViewer(DEMO_TENANT);
        String scope = "/api/v1/audit?actorMemberId=" + actor;

        assertThat(idsOf(getJson(token, scope + "&from=2026-03-14"))).containsExactly(firstInstantOfThe14th);
        assertThat(idsOf(getJson(token, scope + "&to=2026-03-13"))).containsExactly(lastSecondOfThe13th);
    }

    @Test
    void theRangeStartsAtKyivMidnightInSummer() throws Exception {
        // 2026-07-15 is in summer time: Kyiv is UTC+3, so Kyiv midnight is 21:00 UTC the day before.
        UUID actor = UUID.randomUUID();
        UUID lastSecondOfThe14th = insert(DEMO_TENANT, "2026-07-14T20:59:59Z", actor, "MEMBER_UPDATED", "MEMBER",
                UUID.randomUUID(), null, "[]");
        UUID firstInstantOfThe15th = insert(DEMO_TENANT, "2026-07-14T21:00:00Z", actor, "MEMBER_UPDATED", "MEMBER",
                UUID.randomUUID(), null, "[]");
        String token = auditViewer(DEMO_TENANT);
        String scope = "/api/v1/audit?actorMemberId=" + actor;

        assertThat(idsOf(getJson(token, scope + "&from=2026-07-15"))).containsExactly(firstInstantOfThe15th);
        assertThat(idsOf(getJson(token, scope + "&to=2026-07-14"))).containsExactly(lastSecondOfThe14th);
    }

    @Test
    void theLastDayIsIncludedUntilKyivMidnight() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID lastSecond = insert(DEMO_TENANT, "2026-03-14T21:59:59Z", actor, "MEMBER_UPDATED", "MEMBER",
                UUID.randomUUID(), null, "[]"); // 23:59:59 on the 14th in Kyiv
        UUID nextDay = insert(DEMO_TENANT, "2026-03-14T22:00:00Z", actor, "MEMBER_UPDATED", "MEMBER",
                UUID.randomUUID(), null, "[]"); // 00:00:00 on the 15th in Kyiv
        String token = auditViewer(DEMO_TENANT);
        String scope = "/api/v1/audit?actorMemberId=" + actor;

        assertThat(idsOf(getJson(token, scope + "&to=2026-03-14"))).containsExactly(lastSecond);
        assertThat(idsOf(getJson(token, scope + "&to=2026-03-15"))).containsExactly(nextDay, lastSecond);
    }

    @Test
    void theDayIsTheKyivDayNotTheUtcDay() throws Exception {
        // 22:30 UTC on the 14th is 00:30 on the 15th in Kyiv (UTC+2).
        UUID actor = UUID.randomUUID();
        UUID entry = insert(DEMO_TENANT, "2026-03-14T22:30:00Z", actor, "MEMBER_UPDATED", "MEMBER",
                UUID.randomUUID(), null, "[]");
        String token = auditViewer(DEMO_TENANT);
        String scope = "/api/v1/audit?actorMemberId=" + actor;

        assertThat(idsOf(getJson(token, scope + "&from=2026-03-14&to=2026-03-14"))).isEmpty();
        assertThat(idsOf(getJson(token, scope + "&from=2026-03-15&to=2026-03-15"))).containsExactly(entry);
    }

    @Test
    void aSingleDayRangeCoversTheWholeKyivDayAndBothEndsAreInclusive() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID before = insert(DEMO_TENANT, "2026-03-13T21:59:59Z", actor, "MEMBER_UPDATED", "MEMBER",
                UUID.randomUUID(), null, "[]");
        UUID first = insert(DEMO_TENANT, "2026-03-13T22:00:00Z", actor, "MEMBER_UPDATED", "MEMBER",
                UUID.randomUUID(), null, "[]");
        UUID noon = insert(DEMO_TENANT, "2026-03-14T10:00:00Z", actor, "MEMBER_UPDATED", "MEMBER",
                UUID.randomUUID(), null, "[]");
        UUID last = insert(DEMO_TENANT, "2026-03-14T21:59:59Z", actor, "MEMBER_UPDATED", "MEMBER",
                UUID.randomUUID(), null, "[]");
        UUID after = insert(DEMO_TENANT, "2026-03-14T22:00:00Z", actor, "MEMBER_UPDATED", "MEMBER",
                UUID.randomUUID(), null, "[]");
        String token = auditViewer(DEMO_TENANT);

        assertThat(idsOf(getJson(token, "/api/v1/audit?actorMemberId=" + actor + "&from=2026-03-14&to=2026-03-14")))
                .containsExactly(last, noon, first);
        assertThat(idsOf(getJson(token, "/api/v1/audit?actorMemberId=" + actor + "&from=2026-03-13&to=2026-03-15")))
                .containsExactly(after, last, noon, first, before);
    }

    @Test
    void aStartAfterTheEndIsRejected() throws Exception {
        getAs(auditViewer(DEMO_TENANT), "/api/v1/audit?from=2026-03-15&to=2026-03-14")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
    }

    @Test
    void invalidParametersAreRejected() throws Exception {
        String token = auditViewer(DEMO_TENANT);

        // a value that cannot be parsed is MALFORMED_REQUEST, a parsed value outside its constraints is VALIDATION_FAILED
        for (String query : List.of("entityType=UNKNOWN", "entityId=not-a-uuid", "from=14.03.2026", "size=abc")) {
            getAs(token, "/api/v1/audit?" + query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        for (String query : List.of("size=101", "size=0", "page=-1")) {
            getAs(token, "/api/v1/audit?" + query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    // ---- order and paging -------------------------------------------------------------------------------------

    @Test
    void newestFirstAndPaged() throws Exception {
        UUID actor = UUID.randomUUID();
        List<UUID> byAge = new ArrayList<>(); // oldest first
        for (int minute = 0; minute < 5; minute++) {
            byAge.add(insert(DEMO_TENANT, "2026-05-10T10:0" + minute + ":00Z", actor, "MEMBER_UPDATED", "MEMBER",
                    UUID.randomUUID(), null, "[]"));
        }
        String token = auditViewer(DEMO_TENANT);
        String scope = "/api/v1/audit?actorMemberId=" + actor;

        JsonNode first = getJson(token, scope + "&size=2&page=0");
        assertThat(idsOf(first)).containsExactly(byAge.get(4), byAge.get(3));
        assertThat(first.path("page").asInt()).isEqualTo(0);
        assertThat(first.path("size").asInt()).isEqualTo(2);
        assertThat(first.path("totalItems").asLong()).isEqualTo(5);
        assertThat(first.path("totalPages").asInt()).isEqualTo(3);
        assertThat(idsOf(getJson(token, scope + "&size=2&page=1"))).containsExactly(byAge.get(2), byAge.get(1));
        assertThat(idsOf(getJson(token, scope + "&size=2&page=2"))).containsExactly(byAge.get(0));
        getAs(token, scope + "&size=2&page=3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalItems").value(5));
    }

    @Test
    void entriesAtTheSameInstantAreOrderedByIdDescending() throws Exception {
        UUID actor = UUID.randomUUID();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(insert(DEMO_TENANT, "2026-05-10T10:00:00Z", actor, "MEMBER_UPDATED", "MEMBER",
                    UUID.randomUUID(), null, "[]"));
        }
        // PostgreSQL compares uuids bytewise, which matches the order of their lower-case text form
        List<UUID> expected = ids.stream()
                .sorted(java.util.Comparator.comparing((UUID id) -> id.toString()).reversed())
                .toList();

        assertThat(idsOf(getJson(auditViewer(DEMO_TENANT), "/api/v1/audit?actorMemberId=" + actor)))
                .containsExactlyElementsOf(expected);
    }

    @Test
    void theDefaultPageSizeIs50() throws Exception {
        getAs(auditViewer(DEMO_TENANT), "/api/v1/audit")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50));
    }

    // ---- tenant isolation -------------------------------------------------------------------------------------

    @Test
    void anotherCooperativeSeesNoneOfTheEntries() throws Exception {
        UUID entity = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID demoEntry = insert(DEMO_TENANT, "2026-05-10T10:00:00Z", actor, "MEMBER_UPDATED", "MEMBER", entity, null, "[]");
        UUID otherEntry = insert(OTHER_TENANT, "2026-05-10T10:00:00Z", actor, "MEMBER_UPDATED", "MEMBER", entity, null,
                "[]");
        String demo = auditViewer(DEMO_TENANT);
        String other = auditViewer(OTHER_TENANT);
        String query = "/api/v1/audit?entityId=" + entity;

        assertThat(idsOf(getJson(demo, query))).containsExactly(demoEntry);
        assertThat(idsOf(getJson(other, query))).containsExactly(otherEntry);
        // an unfiltered read of the other cooperative never contains the demo entry
        assertThat(idsOf(getJson(other, "/api/v1/audit?actorMemberId=" + actor + "&size=100")))
                .doesNotContain(demoEntry);
    }

    @Test
    void anActorOfAnotherCooperativeIsNotNamed() throws Exception {
        UUID otherMember = newMember(OTHER_TENANT, "Foreign" + tag(), "Person", null, null);
        UUID entity = UUID.randomUUID();
        insert(DEMO_TENANT, "2026-05-10T10:00:00Z", otherMember, "MEMBER_UPDATED", "MEMBER", entity, null, "[]");

        getAs(auditViewer(DEMO_TENANT), "/api/v1/audit?entityId=" + entity)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].actorMemberId").value(otherMember.toString()))
                .andExpect(jsonPath("$.items[0].actorName").doesNotExist());
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** A member created and then updated by a chairman through the API: a new phone and a new last name. */
    private record MemberUpdate(String chairman, UUID member, String oldLastName, String newLastName,
            String oldPhoneDigits, String newPhoneDigits) {
    }

    private MemberUpdate memberWithAPhoneChange() throws Exception {
        UUID chairmanMember = newMember(DEMO_TENANT, "chairman-" + tag() + "@demo.local");
        giveBaseRole(DEMO_TENANT, chairmanMember, "CHAIRMAN");
        String chairman = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(),
                emailOf(DEMO_TENANT, chairmanMember));
        String oldPhone = "38067" + digits(7);
        String newPhone = "38050" + digits(7);
        String oldName = "Sealed" + tag();
        String newName = "Renamed" + tag();
        String created = mvc.perform(post("/api/v1/members").header(HttpHeaders.AUTHORIZATION, chairman)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("lastName", oldName, "firstName", "Person",
                                "phones", List.of("+" + oldPhone)))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID member = UUID.fromString(json.readTree(created).path("id").asString());
        apiCreatedMembers.add(member);
        mvc.perform(put("/api/v1/members/" + member).header(HttpHeaders.AUTHORIZATION, chairman)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("lastName", newName, "firstName", "Person",
                                "phones", List.of("+" + newPhone)))))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(getJson(chairman, "/api/v1/audit?entityId=" + member).path("totalItems").asInt())
                        .isEqualTo(2));
        return new MemberUpdate(chairman, member, oldName, newName, oldPhone, newPhone);
    }

    /** The MEMBER_UPDATED entry of the member as the given reader sees it. */
    private JsonNode updatedEntry(String bearer, UUID member) throws Exception {
        for (JsonNode item : getJson(bearer, "/api/v1/audit?entityId=" + member).path("items")) {
            if ("MEMBER_UPDATED".equals(item.path("action").asString())) {
                return item;
            }
        }
        throw new AssertionError("no MEMBER_UPDATED entry");
    }

    private static JsonNode field(JsonNode entry, String name) {
        for (JsonNode change : entry.path("changes")) {
            if (name.equals(change.path("field").asString())) {
                return change;
            }
        }
        throw new AssertionError("no change of " + name);
    }

    /**
     * A signed-in member of a custom role whose only permission is AUDIT_VIEW (no MEMBERS_VIEW); the role and the
     * member are removed after the test.
     */
    private String auditViewOnly(UUID tenant) {
        String email = "auditonly-" + tag() + "@demo.local";
        UUID member = newMember(tenant, email);
        UUID role = UUID.randomUUID();
        customRoles.add(role);
        inTenant(tenant, sql -> {
            sql.update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, tenant, "AuditOnly " + role);
            sql.update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, 'AUDIT_VIEW')",
                    tenant, role);
            return sql.update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)", tenant,
                    member, role);
        });
        return TestTokens.bearer(tenant, UUID.randomUUID().toString(), email);
    }

    private static String digits(int count) {
        StringBuilder digits = new StringBuilder();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            digits.append(random.nextInt(10));
        }
        return digits.toString();
    }

    /** A signed-in treasurer (holds AUDIT_VIEW) of the given cooperative; the member is removed after the test. */
    private String auditViewer(UUID tenant) {
        String email = "viewer-" + tag() + "@" + (tenant.equals(DEMO_TENANT) ? "demo" : "other") + ".local";
        UUID member = newMember(tenant, email);
        giveBaseRole(tenant, member, "TREASURER");
        return TestTokens.bearer(tenant, UUID.randomUUID().toString(), email);
    }

    /** Inserts an audit entry under the cooperative (what the application role may do) and returns its id. */
    private UUID insert(UUID tenant, String occurredAt, UUID actorMemberId, String action, String entityType,
            UUID entityId, String entityLabel, String changesJson) {
        UUID id = UUID.randomUUID();
        inTenant(tenant, sql -> sql.update("""
                insert into audit_entry (id, tenant_id, occurred_at, actor_member_id, actor_subject, action, entity_type,
                                         entity_id, entity_label, changes)
                values (?, ?, ?, ?, 'fixture-subject', ?, ?, ?, ?, cast(? as jsonb))
                """, id, tenant, java.sql.Timestamp.from(Instant.parse(occurredAt)), actorMemberId, action, entityType,
                entityId, entityLabel, changesJson));
        return id;
    }

    private String emailOf(UUID tenant, UUID member) {
        return memberEmail(tenant, member);
    }

    /** Lower-case token that is unique per call. */
    private static String tag() {
        return "zq" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private ResultActions getAs(String bearer, String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private JsonNode getJson(String bearer, String path) throws Exception {
        String body = getAs(bearer, path).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static List<UUID> idsOf(JsonNode page) {
        return page.path("items").valueStream().map(item -> UUID.fromString(item.path("id").asString())).toList();
    }
}
