package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two requests that each take Chairman away from a different one of exactly two chairmen run at the same time:
 * exactly one succeeds, the other is refused with {@code LAST_CHAIRMAN}, and one chairman is left.
 * <p>
 * Runs in the other cooperative, which has no chairman of its own, so the two fixture chairmen are all there is.
 * The acting member holds a custom role with ROLES_MANAGE only (not Chairman), so it does not count as a chairman.
 */
class LastChairmanConcurrencyTests extends IntegrationTest {

    private static final int ROUNDS = 20;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private UUID chairmanA;
    private UUID chairmanB;
    private UUID memberRole;
    private UUID chairmanRole;
    private UUID managerRole;
    private String actorToken;

    @BeforeEach
    void fixture() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        chairmanA = newMember(OTHER_TENANT, "chair-a-" + tag + "@example.com");
        chairmanB = newMember(OTHER_TENANT, "chair-b-" + tag + "@example.com");
        String actorEmail = "manager-" + tag + "@example.com";
        UUID actor = newMember(OTHER_TENANT, actorEmail);
        memberRole = baseRole("MEMBER");
        chairmanRole = baseRole("CHAIRMAN");
        managerRole = UUID.randomUUID();
        inTenant(OTHER_TENANT, sql -> {
            sql.update("insert into role (id, tenant_id, name) values (?, ?, ?)", managerRole, OTHER_TENANT,
                    "Manager " + managerRole);
            sql.update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, 'ROLES_MANAGE')",
                    OTHER_TENANT, managerRole);
            return sql.update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)",
                    OTHER_TENANT, actor, managerRole);
        });
        giveBaseRole(OTHER_TENANT, chairmanA, "CHAIRMAN");
        giveBaseRole(OTHER_TENANT, chairmanB, "CHAIRMAN");
        actorToken = TestTokens.bearer(OTHER_TENANT, "sub-" + actorEmail, actorEmail);
    }

    @AfterEach
    void removeRole() {
        inTenant(OTHER_TENANT, sql -> {
            sql.update("delete from member_role where role_id = ?", managerRole);
            sql.update("delete from role_permission where role_id = ?", managerRole);
            return sql.update("delete from role where id = ?", managerRole);
        });
    }

    @Test
    void ofTwoConcurrentRemovalsOfTheChairmanRoleExactlyOneSucceeds() throws Exception {
        int succeeded = 0;
        for (int round = 0; round < ROUNDS; round++) {
            List<MockHttpServletResponse> responses = ConcurrentRequests.run(
                    () -> removeChairman(chairmanA),
                    () -> removeChairman(chairmanB));

            List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).sorted().toList();
            assertThat(statuses).as("round %d statuses %s", round, statuses).containsExactly(200, 409);
            for (MockHttpServletResponse response : responses) {
                if (response.getStatus() == 409) {
                    assertThat(response.getContentAsString()).contains("LAST_CHAIRMAN");
                }
            }
            assertThat(activeChairmen()).as("round %d: one chairman must remain", round).isEqualTo(1);
            succeeded++;
            // give the chairman role back to whoever lost it, for the next round
            inTenant(OTHER_TENANT, sql -> sql.update(
                    "insert into member_role (tenant_id, member_id, role_id) select ?, m, ? "
                            + "from unnest(array[?, ?]::uuid[]) m on conflict do nothing",
                    OTHER_TENANT, chairmanRole, chairmanA, chairmanB));
        }
        assertThat(succeeded).isEqualTo(ROUNDS);
    }

    private MockHttpServletResponse removeChairman(UUID member) throws Exception {
        return mvc.perform(put("/api/v1/members/" + member + "/roles")
                        .header(HttpHeaders.AUTHORIZATION, actorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("roleIds", List.of(memberRole)))))
                .andReturn().getResponse();
    }

    private int activeChairmen() {
        return inTenant(OTHER_TENANT, sql -> sql.queryForObject(
                "select count(distinct m.id) from member m join member_role mr on mr.member_id = m.id "
                        + "join role r on r.id = mr.role_id "
                        + "where m.archived_at is null and r.base_code = 'CHAIRMAN'", Integer.class));
    }

    private UUID baseRole(String code) {
        return inTenant(OTHER_TENANT, sql -> sql.queryForObject(
                "select id from role where base_code = ?", UUID.class, code));
    }
}
