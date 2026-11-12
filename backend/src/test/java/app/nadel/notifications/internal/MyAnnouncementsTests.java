package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * «Оголошення» in the member account (feature 021 follow-up): a member lists the board's announcements they received
 * through any channel (paper included), newest first, with the full text and whether they opened them; opening one is
 * idempotent and keeps the first time; an announcement to another street, of another cooperative or older than twelve
 * months is not theirs (not listed, 404 on opening); the platform operator gets 403 and an anonymous caller 401.
 */
class MyAnnouncementsTests extends DeliveryTestSupport {

    private static final String MINE = "/api/v1/me/announcements";

    private String member;
    private UUID memberId;
    private String neighbour;

    @BeforeEach
    void twoMembersOnTwoStreets() {
        member = person("MEMBER", "Мельник", "Ольга", "melnyk-" + UUID.randomUUID() + "@delivery.test");
        memberId = lastPerson();
        neighbour = person("MEMBER", "Гончар", "Петро", "honchar-" + UUID.randomUUID() + "@delivery.test");
        plotOn("Вишнева", "1", memberId);
        plotOn("Садова", "2", lastPerson());
    }

    @Test
    void aMemberReadsTheAnnouncementsTheyReceivedNewestFirstAndMarksThemOpened() throws Exception {
        UUID older = announce("Збори в неділю о 10:00.\nПорядок денний: кошторис.", List.of());
        UUID newer = announce("Відключення світла в суботу", List.of());

        JsonNode list = mine(member);
        assertThat(ids(list)).containsExactly(newer, older);
        assertThat(list.get(1).get("text").asString()).isEqualTo("Збори в неділю о 10:00.\nПорядок денний: кошторис.");
        assertThat(list.get(0).get("sentAt").asString()).isNotBlank();
        assertThat(list.get(0).propertyNames()).containsExactlyInAnyOrder("id", "sentAt", "text", "unread");
        assertThat(list).allSatisfy(item -> assertThat(item.get("unread").asBoolean()).isTrue());

        markRead(member, older).andExpect(status().isNoContent());
        Timestamp first = readAt(older);
        markRead(member, older).andExpect(status().isNoContent());

        list = mine(member);
        assertThat(list.get(0).get("unread").asBoolean()).isTrue();
        assertThat(list.get(1).get("unread").asBoolean()).isFalse();
        // opened once: one mark, the first time kept; the neighbour's own state is untouched
        assertThat(ownerJdbc().queryForObject("select count(*) from announcement_read where tenant_id = ?",
                Integer.class, tenant)).isEqualTo(1);
        assertThat(readAt(older)).isEqualTo(first);
        assertThat(mine(neighbour)).allSatisfy(item -> assertThat(item.get("unread").asBoolean()).isTrue());
        // the text never leaves through a log line
        assertThat(new AnnouncementReads.Received(older, Instant.now(), "Збори в неділю", true).toString())
                .doesNotContain("Збори");
        // not an admin change: nothing in the audit log
        assertThat(ownerJdbc().queryForObject("select count(*) from audit_entry where tenant_id = ? "
                + "and entity_id = ? and action <> 'ANNOUNCEMENT_SENT'", Integer.class, tenant, older)).isZero();
    }

    @Test
    void anAnnouncementToAnotherStreetIsNotTheMembersOwn() throws Exception {
        UUID everyone = announce("Для всіх", List.of());
        UUID sadova = announce("Тільки для Садової", List.of("Садова"));

        assertThat(ids(mine(member))).containsExactly(everyone);
        assertThat(ids(mine(neighbour))).containsExactly(sadova, everyone);
        markRead(member, sadova).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        markRead(member, UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(ownerJdbc().queryForObject("select count(*) from announcement_read where tenant_id = ?",
                Integer.class, tenant)).isZero();
    }

    @Test
    void onlyTheLastTwelveMonthsAreListed() throws Exception {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Europe/Kyiv"));
        UUID recent = sentAt("Одинадцять місяців тому", now.minusMonths(11).toInstant());
        UUID old = sentAt("Тринадцять місяців тому", now.minusMonths(13).toInstant());

        assertThat(ids(mine(member))).containsExactly(recent);
        // still the member's own: opening it from an old link is fine
        markRead(member, old).andExpect(status().isNoContent());
    }

    @Test
    void anotherCooperativeTheOperatorAndAnAnonymousCallerGetNothing() throws Exception {
        UUID announcement = announce("Збори", List.of());
        String email = "other-member-" + UUID.randomUUID() + "@other.local";
        newMember(OTHER_TENANT, email);
        String otherMember = TestTokens.bearer(OTHER_TENANT, "sub-" + email, email);
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        assertThat(ids(mine(otherMember))).doesNotContain(announcement);
        markRead(otherMember, announcement).andExpect(status().isNotFound());
        for (ResultActions call : List.of(send(operator, get(MINE), null),
                send(operator, post(MINE + "/" + announcement + "/read"), null))) {
            call.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        mvc.perform(get(MINE)).andExpect(status().isUnauthorized());
        mvc.perform(post(MINE + "/" + announcement + "/read")).andExpect(status().isUnauthorized());
        assertThat(ownerJdbc().queryForObject("select count(*) from announcement_read where announcement_id = ?",
                Integer.class, announcement)).isZero();
    }

    // ---- helpers

    /** Sends an announcement as the chairman (Telegram chosen, no bot: everyone on paper); its id. */
    private UUID announce(String text, List<String> streets) throws Exception {
        JsonNode sent = body(send(chairman, post("/api/v1/announcements?preview=false"), Map.of("text", text,
                "streets", streets, "channels", List.of("TELEGRAM"))).andExpect(status().isOk()));
        return UUID.fromString(sent.get("announcementId").asString());
    }

    /** An announcement sent at the given time to the member on paper, written as the database owner; its id. */
    private UUID sentAt(String text, Instant at) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into announcement (id, tenant_id, text, channels, created_at) "
                + "values (?, ?, ?, '{TELEGRAM}', ?)", id, tenant, text, Timestamp.from(at));
        ownerJdbc().update("insert into delivery (tenant_id, kind, announcement_id, member_id, channel, status, "
                + "created_at) values (?, 'ANNOUNCEMENT', ?, ?, 'PAPER', 'TO_PRINT', ?)", tenant, id, memberId,
                Timestamp.from(at));
        return id;
    }

    private JsonNode mine(String bearer) throws Exception {
        return body(send(bearer, get(MINE), null).andExpect(status().isOk()));
    }

    private ResultActions markRead(String bearer, UUID announcement) throws Exception {
        return send(bearer, post(MINE + "/" + announcement + "/read"), null);
    }

    private Timestamp readAt(UUID announcement) {
        return ownerJdbc().queryForObject("select read_at from announcement_read where announcement_id = ? "
                + "and member_id = ?", Timestamp.class, announcement, memberId);
    }

    private static List<UUID> ids(JsonNode list) {
        List<UUID> ids = new ArrayList<>();
        list.forEach(item -> ids.add(UUID.fromString(item.get("id").asString())));
        return ids;
    }
}
