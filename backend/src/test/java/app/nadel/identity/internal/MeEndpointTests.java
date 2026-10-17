package app.nadel.identity.internal;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** /me and /members/{id} for the demo users, plus the permission authorities derived from their roles. */
class MeEndpointTests extends IntegrationTest {

    static final UUID CHAIRMAN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID TREASURER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    static final UUID MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    static final UUID ELECTRICIAN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000004");
    static final UUID OTHER_MEMBER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    @Autowired
    MockMvc mvc;

    @AfterEach
    void unlinkDemoMembers() {
        // first sign-in links the demo members; restore the seeded state for other tests
        inTenant(DEMO_TENANT, sql -> sql.update(
                "update member set keycloak_subject = null where id in (?, ?, ?, ?)",
                CHAIRMAN, TREASURER, MEMBER, ELECTRICIAN));
        inTenant(OTHER_TENANT, sql -> sql.update(
                "update member set keycloak_subject = null where id = ?", OTHER_MEMBER));
    }

    @Test
    void chairmanHasEveryPermission() throws Exception {
        getAs(demo("chairman@demo.local"), "/api/v1/me")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.memberId").value(CHAIRMAN.toString()))
                .andExpect(jsonPath("$.platformOperator").value(false))
                .andExpect(jsonPath("$.fullName").value("Kovalenko Oleksandr Petrovych"))
                .andExpect(jsonPath("$.email").value("chairman@demo.local"))
                .andExpect(jsonPath("$.tenant.id").value(DEMO_TENANT.toString()))
                .andExpect(jsonPath("$.tenant.name").value("Demo Garden Cooperative"))
                .andExpect(jsonPath("$.roles", hasSize(1)))
                .andExpect(jsonPath("$.roles[0].name").value("Chairman"))
                .andExpect(jsonPath("$.roles[0].baseCode").value("CHAIRMAN"))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("MEMBERS_VIEW", "MEMBERS_EDIT",
                        "ROLES_MANAGE", "AUDIT_VIEW", "PLOTS_VIEW", "PLOTS_EDIT", "OWNERSHIP_EDIT", "IMPORT_RUN")));
    }

    @Test
    void treasurerHasReadPermissions() throws Exception {
        getAs(demo("treasurer@demo.local"), "/api/v1/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].baseCode").value("TREASURER"))
                .andExpect(jsonPath("$.permissions",
                        containsInAnyOrder("MEMBERS_VIEW", "PLOTS_VIEW", "AUDIT_VIEW")));
    }

    @Test
    void memberHasNoPermissions() throws Exception {
        getAs(demo("member@demo.local"), "/api/v1/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(MEMBER.toString()))
                .andExpect(jsonPath("$.roles[0].baseCode").value("MEMBER"))
                .andExpect(jsonPath("$.permissions").isEmpty());
    }

    @Test
    void customRoleIsListedWithoutBaseCode() throws Exception {
        getAs(demo("electrician@demo.local"), "/api/v1/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasSize(2)))
                .andExpect(jsonPath("$.roles[?(@.name == 'Electrician')].baseCode").isEmpty())
                .andExpect(jsonPath("$.roles[?(@.name == 'Member')].baseCode").value("MEMBER"))
                .andExpect(jsonPath("$.permissions").isEmpty());
    }

    @Test
    void otherCooperativeUserSeesTheirOwnCooperative() throws Exception {
        String bearer = TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), "member@other.local");

        getAs(bearer, "/api/v1/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(OTHER_MEMBER.toString()))
                .andExpect(jsonPath("$.tenant.name").value("Other Cooperative"));
    }

    @Test
    void permissionsAreEnforcedFromTheMembersRoles() throws Exception {
        String treasurer = demo("treasurer@demo.local");
        String member = demo("member@demo.local");

        getAs(treasurer, "/api/v1/members").andExpect(status().isOk());
        // MEMBERS_EDIT guards the write endpoints; an unknown id shows whether the guard let the call through (404)
        postAs(treasurer, "/api/v1/members/" + UUID.randomUUID() + "/archive")
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        getAs(member, "/api/v1/members")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        postAs(demo("chairman@demo.local"), "/api/v1/members/" + UUID.randomUUID() + "/archive")
                .andExpect(status().isNotFound());
    }

    @Test
    void permissionChangeAppliesOnTheNextRequest() throws Exception {
        UUID member = newMember(DEMO_TENANT, "promoted@demo.local");
        giveBaseRole(DEMO_TENANT, member, "MEMBER");
        String bearer = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), "promoted@demo.local");
        getAs(bearer, "/api/v1/members").andExpect(status().isForbidden());

        giveBaseRole(DEMO_TENANT, member, "TREASURER");

        getAs(bearer, "/api/v1/members").andExpect(status().isOk());
    }

    @Test
    void ownCardForAMemberHasNoNotes() throws Exception {
        inTenant(DEMO_TENANT, sql -> sql.update("update member set notes = 'internal note' where id = ?", MEMBER));
        try {
            getAs(demo("member@demo.local"), "/api/v1/members/" + MEMBER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(MEMBER.toString()))
                    .andExpect(jsonPath("$.lastName").value("Bondarenko"))
                    .andExpect(jsonPath("$.phones[0]").value("+380501110003"))
                    .andExpect(jsonPath("$.deliveryChannel").value("EMAIL"))
                    .andExpect(jsonPath("$.archived").value(false))
                    .andExpect(jsonPath("$.roles[0].baseCode").value("MEMBER"))
                    .andExpect(content().string(not(containsString("notes"))))
                    .andExpect(content().string(not(containsString("internal note"))));
        } finally {
            inTenant(DEMO_TENANT, sql -> sql.update("update member set notes = null where id = ?", MEMBER));
        }
    }

    @Test
    void ownCardForAViewerOfMembersHasNotes() throws Exception {
        inTenant(DEMO_TENANT, sql -> sql.update(
                "update member set notes = 'internal note' where id = ?", TREASURER));
        try {
            getAs(demo("treasurer@demo.local"), "/api/v1/members/" + TREASURER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.notes").value("internal note"));
        } finally {
            inTenant(DEMO_TENANT, sql -> sql.update("update member set notes = null where id = ?", TREASURER));
        }
    }

    @Test
    void anotherMembersCardIsNotFound() throws Exception {
        getAs(demo("member@demo.local"), "/api/v1/members/" + CHAIRMAN)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void cardOfAnotherCooperativeAndUnknownIdLookTheSame() throws Exception {
        String member = demo("member@demo.local");

        getAs(member, "/api/v1/members/" + OTHER_MEMBER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        getAs(member, "/api/v1/members/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void malformedIdIsABadRequest() throws Exception {
        getAs(demo("member@demo.local"), "/api/v1/members/not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void operatorHasNoCardAndNoMemberData() throws Exception {
        getAs(TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer(),
                "/api/v1/members/" + MEMBER)
                .andExpect(status().isNotFound());
        getAs(TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer(),
                "/api/v1/members")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void membersCardRequiresAToken() throws Exception {
        mvc.perform(get("/api/v1/members/" + MEMBER))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void plotsStillWorkForALinkedMember() throws Exception {
        getAs(demo("member@demo.local"), "/api/v1/plots")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features", hasSize(13)));
    }

    /** A token as Keycloak would issue it for a demo user; the subject is stable per e-mail. */
    private static String demo(String email) {
        UUID tenant = email.endsWith("@other.local") ? OTHER_TENANT : DEMO_TENANT;
        return TestTokens.bearer(tenant, "sub-" + email, email);
    }

    private ResultActions getAs(String bearer, String path) throws Exception {
        return mvc.perform(get(path)
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions postAs(String bearer, String path) throws Exception {
        return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer));
    }
}
