package app.nadel.members.internal;

import static app.nadel.members.internal.InvitationRequests.chairman;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import app.nadel.identity.FakeSignInAccounts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A cooperative never locks its board out (feature 020, owner decision on PR 71): nobody turns off their own access
 * (409 {@code OWN_ACCESS}), and the access of the last active chairman whose access is on cannot be turned off, neither
 * by the disable action nor by the archive, which turns access off too (409 {@code LAST_CHAIRMAN}). A chairman whose
 * access is already off, or who was never invited, does not count. The board member who acts here holds a custom role
 * with the member permissions, so the chairmen under test are other members.
 */
class MemberAccessGuardTests extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeSignInAccounts accounts;

    private final List<UUID> roles = new ArrayList<>();
    private InvitationRequests http;

    @BeforeEach
    void setUp() {
        http = new InvitationRequests(mvc);
        // the demo chairman is not linked and has no access row: never invited, so not a chairman with access
        resetDemoChairman();
        assertThat(chairmenWithAccess()).as("no chairman of the demo cooperative has access yet").isEmpty();
    }

    @AfterEach
    void cleanUp() {
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from event_publication where completion_date is null", Integer.class) == 0);
        for (UUID role : roles) {
            inTenant(DEMO_TENANT, sql -> {
                sql.update("delete from member_role where role_id = ?", role);
                sql.update("delete from role_permission where role_id = ?", role);
                return sql.update("delete from role where id = ?", role);
            });
        }
        roles.clear();
        accounts.reset();
        resetDemoChairman();
    }

    private void resetDemoChairman() {
        UUID demoChairman = memberIdByEmail(DEMO_TENANT, "chairman@demo.local");
        ownerJdbc().update("delete from member_access where member_id = ?", demoChairman);
        unlinkByEmail(DEMO_TENANT, "chairman@demo.local");
    }

    private List<UUID> chairmenWithAccess() {
        return inTenant(DEMO_TENANT, sql -> sql.queryForList("""
                select distinct m.id from member m
                join member_role mr on mr.member_id = m.id
                join role r on r.id = mr.role_id
                left join member_access a on a.member_id = m.id
                where m.archived_at is null and r.base_code = 'CHAIRMAN'
                  and (a.state in ('INVITED', 'ACTIVE') or (a.member_id is null and m.keycloak_subject is not null))
                """, UUID.class));
    }

    private static String freshEmail() {
        return "guard-" + UUID.randomUUID() + "@example.com";
    }

    private String state(UUID member) {
        List<String> states = ownerJdbc().queryForList("select state from member_access where member_id = ?",
                String.class, member);
        return states.isEmpty() ? null : states.getFirst();
    }

    /** A board member whose only role is a custom role with the member permissions; signs in by e-mail. */
    private String boardMember(String email) {
        UUID member = newMember(DEMO_TENANT, "Секретар", "Олена", null, email);
        UUID role = UUID.randomUUID();
        roles.add(role);
        inTenant(DEMO_TENANT, sql -> {
            sql.update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, DEMO_TENANT,
                    "Guard " + role);
            for (String permission : List.of("MEMBERS_VIEW", "MEMBERS_EDIT", "MEMBERS_INVITE")) {
                sql.update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)",
                        DEMO_TENANT, role, permission);
            }
            return sql.update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)",
                    DEMO_TENANT, member, role);
        });
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }

    /** A chairman linked to a sign-in account (no access row: read as {@code ACTIVE}). */
    private UUID chairmanWithAccess() {
        String email = freshEmail();
        UUID member = newMember(DEMO_TENANT, "Голова", "Інший", null, email);
        giveBaseRole(DEMO_TENANT, member, "CHAIRMAN");
        String account = accounts.add(email, DEMO_TENANT, true);
        inTenant(DEMO_TENANT, sql -> sql.update("update member set keycloak_subject = ? where id = ?", account,
                member));
        return member;
    }

    @Test
    void nobodyTurnsOffTheirOwnAccess() throws Exception {
        String email = freshEmail();
        String board = boardMember(email);
        http.me(board).andExpect(status().isOk());
        UUID self = memberIdByEmail(DEMO_TENANT, email);

        http.disable(board, self).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWN_ACCESS"));
        // the chairman, who holds every permission, neither
        UUID demoChairman = memberIdByEmail(DEMO_TENANT, "chairman@demo.local");
        http.disable(chairman(), demoChairman).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWN_ACCESS"));

        assertThat(state(self)).isNull();
        assertThat(state(demoChairman)).isNull();
        http.me(board).andExpect(status().isOk());
        http.me(chairman()).andExpect(status().isOk());
    }

    @Test
    void theLastChairmanWithAccessKeepsItAndAnotherChairmanLetsItGo() throws Exception {
        String board = boardMember(freshEmail());
        UUID last = chairmanWithAccess();

        http.disable(board, last).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_CHAIRMAN"));
        assertThat(state(last)).as("nothing changed").isNull();

        // a chairman whose access is already off does not count
        UUID disabled = chairmanWithAccess();
        inTenant(DEMO_TENANT, sql -> sql.update("insert into member_access (member_id, tenant_id, state, disabled_at) "
                + "values (?, ?, 'DISABLED', now())", disabled, DEMO_TENANT));
        http.disable(board, last).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_CHAIRMAN"));
        // the archive turns access off too: refused, although another (never invited) chairman stays active
        http.archive(board, last).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_CHAIRMAN"));
        http.card(board, last).andExpect(jsonPath("$.archivedAt").doesNotExist())
                .andExpect(jsonPath("$.access.status").value("ACTIVE"));

        // with a second chairman who has access, a normal disable works
        UUID second = chairmanWithAccess();
        http.disable(board, last).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
        assertThat(state(last)).isEqualTo("DISABLED");
        String account = subjectOf(DEMO_TENANT, last);
        await().atMost(Duration.ofSeconds(10)).until(() -> !accounts.account(account).orElseThrow().enabled());

        // and now the second one is the last
        http.disable(board, second).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_CHAIRMAN"));
        assertThat(state(second)).isNull();
    }
}
