package app.nadel.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** A signed-in person is recognised as a member by Keycloak subject, or by verified e-mail on the first sign-in. */
class MemberLinkingTests extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void firstSignInLinksTheMemberByEmailIgnoringCase() throws Exception {
        UUID member = newMember(DEMO_TENANT, "linking.case@demo.local");
        String subject = UUID.randomUUID().toString();

        me(TestTokens.bearer(DEMO_TENANT, subject, "Linking.CASE@Demo.Local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(member.toString()))
                .andExpect(jsonPath("$.userId").value(subject));

        assertThat(subjectOf(DEMO_TENANT, member)).isEqualTo(subject);
    }

    @Test
    void personWithoutMatchingMemberIsNotLinked() throws Exception {
        me(TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), "nobody@demo.local"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_LINKED"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void emailOfAnotherCooperativeDoesNotMatch() throws Exception {
        newMember(OTHER_TENANT, "only.in.other@other.local");

        me(TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), "only.in.other@other.local"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_LINKED"));
    }

    @Test
    void unverifiedEmailDoesNotLink() throws Exception {
        UUID member = newMember(DEMO_TENANT, "unverified@demo.local");

        me(TestTokens.token().tenant(DEMO_TENANT).email("unverified@demo.local").emailVerified(false).bearer())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_LINKED"));

        assertThat(subjectOf(DEMO_TENANT, member)).isNull();
    }

    @Test
    void archivedMemberIsRefused() throws Exception {
        UUID member = newMember(DEMO_TENANT, "archived@demo.local");
        inTenant(DEMO_TENANT, sql -> sql.update("update member set archived_at = now() where id = ?", member));

        me(TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), "archived@demo.local"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_LINKED"));

        assertThat(subjectOf(DEMO_TENANT, member)).isNull();
    }

    @Test
    void memberArchivedAfterLinkingIsRefused() throws Exception {
        UUID member = newMember(DEMO_TENANT, "archived.later@demo.local");
        String bearer = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), "archived.later@demo.local");
        me(bearer).andExpect(status().isOk());

        inTenant(DEMO_TENANT, sql -> sql.update("update member set archived_at = now() where id = ?", member));

        me(bearer).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEMBER_NOT_LINKED"));
    }

    @Test
    void linkSurvivesAnEmailEdit() throws Exception {
        UUID member = newMember(DEMO_TENANT, "before.edit@demo.local");
        String subject = UUID.randomUUID().toString();
        me(TestTokens.bearer(DEMO_TENANT, subject, "before.edit@demo.local")).andExpect(status().isOk());

        inTenant(DEMO_TENANT, sql -> sql.update(
                "update member set email = 'after.edit@demo.local' where id = ?", member));

        me(TestTokens.bearer(DEMO_TENANT, subject, "after.edit@demo.local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(member.toString()));
        // even a token whose e-mail matches nothing is recognised by subject
        me(TestTokens.bearer(DEMO_TENANT, subject, "changed.in.keycloak@demo.local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(member.toString()));
    }

    @Test
    void secondSubjectCannotTakeALinkedMember() throws Exception {
        UUID member = newMember(DEMO_TENANT, "taken@demo.local");
        String first = UUID.randomUUID().toString();
        me(TestTokens.bearer(DEMO_TENANT, first, "taken@demo.local")).andExpect(status().isOk());

        me(TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), "taken@demo.local"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_LINKED"));

        assertThat(subjectOf(DEMO_TENANT, member)).isEqualTo(first);
    }

    @Test
    void subjectHeldByAnArchivedMemberIsNotLinkedToAnotherOne() throws Exception {
        String subject = UUID.randomUUID().toString();
        UUID old = newMember(DEMO_TENANT, "old.holder@demo.local");
        inTenant(DEMO_TENANT, sql -> sql.update(
                "update member set keycloak_subject = ?, archived_at = now() where id = ?", subject, old));
        UUID current = newMember(DEMO_TENANT, "new.holder@demo.local");

        // linking would violate the unique index on (tenant, subject): the answer is "not linked", not a 500
        me(TestTokens.bearer(DEMO_TENANT, subject, "new.holder@demo.local"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_LINKED"));

        assertThat(subjectOf(DEMO_TENANT, current)).isNull();
    }

    @Test
    void tokenWithoutTenantClaimIsNotLinked() throws Exception {
        me(TestTokens.token().email("chairman@demo.local").bearer())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_LINKED"));
    }

    @Test
    void platformOperatorIsRecognisedWithoutAMember() throws Exception {
        me(TestTokens.token().subject("operator-sub").email("operator@nadel.local").name("Platform Operator")
                .realmRoles("PLATFORM_OPERATOR").bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformOperator").value(true))
                .andExpect(jsonPath("$.userId").value("operator-sub"))
                .andExpect(jsonPath("$.fullName").value("Platform Operator"))
                .andExpect(jsonPath("$.roles").isEmpty())
                .andExpect(jsonPath("$.permissions").isEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("memberId"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("tenant\""))));
    }

    @Test
    void noTokenIsUnauthenticated() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    void unknownTokenIsUnauthenticated() throws Exception {
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE));
    }

    private ResultActions me(String bearer) throws Exception {
        return mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer));
    }
}
