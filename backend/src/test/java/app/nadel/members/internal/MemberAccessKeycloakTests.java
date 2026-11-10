package app.nadel.members.internal;

import static app.nadel.members.internal.InvitationRequests.chairman;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.KeycloakIntegrationTest;
import app.nadel.TestKeycloak;
import app.nadel.TestTokens;
import app.nadel.notifications.Mailpit;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * Disable, archive and e-mail change of feature 020 against a real Keycloak with the project's realm
 * ({@link TestKeycloak}) and Mailpit: {@link MemberAccessListener} turns the account off after a disable or an archive
 * (and a restore leaves it off), moves it to the new e-mail (not verified) after an e-mail change and sends the new link,
 * with which the member signs in under the new login; an e-mail that signs in to another cooperative is refused.
 * Inviting a disabled member again turns the account off, removes its password and ends its sessions, so only the new
 * link lets the member in, to the same account and the same member.
 */
class MemberAccessKeycloakTests extends KeycloakIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "another-horse-battery";

    @Autowired
    MockMvc mvc;

    private final TestKeycloak keycloak = TestKeycloak.shared();
    private final Mailpit mailpit = Mailpit.shared();
    private InvitationRequests http;

    @BeforeEach
    void setUp() throws Exception {
        http = new InvitationRequests(mvc);
        mailpit.clear();
        http.mailboxOnMailpit();
    }

    @AfterEach
    void cleanUp() {
        await().atMost(Duration.ofSeconds(20)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from event_publication where completion_date is null", Integer.class) == 0);
        ownerJdbc().update("delete from delivery_settings where tenant_id = ?", DEMO_TENANT);
        unlinkByEmail(DEMO_TENANT, "chairman@demo.local");
    }

    private static String freshEmail() {
        return "kc-access-" + UUID.randomUUID() + "@example.com";
    }

    private JsonNode user(String email) {
        List<JsonNode> users = keycloak.usersByEmail(email);
        assertThat(users).hasSize(1);
        return users.getFirst();
    }

    /** Invites the member and sets the password from the letter; answers the Keycloak id. */
    private String activate(UUID member, String email) throws Exception {
        http.invite(chairman(), member).andExpect(status().isOk());
        http.accept(InvitationRequests.tokenOfLetterTo(email), PASSWORD).andExpect(status().isNoContent());
        assertThat(keycloak.signIn(email, PASSWORD)).isEqualTo(200);
        return user(email).path("id").asString();
    }

    @Test
    void disableTurnsTheAccountOffAndTheMemberCannotSignIn() throws Exception {
        String email = freshEmail();
        UUID member = newMember(DEMO_TENANT, "Коваль", "Олена", null, email);
        String keycloakId = activate(member, email);

        http.disable(chairman(), member).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));

        await().atMost(Duration.ofSeconds(20)).until(() -> !user(email).path("enabled").asBoolean());
        assertThat(keycloak.signIn(email, PASSWORD)).isNotEqualTo(200);
        http.me(TestTokens.bearer(DEMO_TENANT, keycloakId, email)).andExpect(status().isForbidden());
    }

    @Test
    void archiveTurnsTheAccountOffAndRestoreLeavesItOff() throws Exception {
        String email = freshEmail();
        UUID member = newMember(DEMO_TENANT, "Коваль", "Олена", null, email);
        activate(member, email);

        http.archive(chairman(), member).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(20)).until(() -> !user(email).path("enabled").asBoolean());
        assertThat(keycloak.signIn(email, PASSWORD)).isNotEqualTo(200);

        http.restore(chairman(), member).andExpect(status().isOk())
                .andExpect(jsonPath("$.access.status").value("DISABLED"));
        Thread.sleep(1000); // a listener, if one were triggered, would have turned the account on within this time
        assertThat(user(email).path("enabled").asBoolean()).isFalse();
        assertThat(keycloak.signIn(email, PASSWORD)).isNotEqualTo(200);
    }

    /**
     * The member's own sign-in after the link: a real Keycloak token of the password, its claims (subject,
     * {@code tenant_id}, e-mail) put into a test token, so {@code /me} resolves exactly what the app would.
     */
    private String bearerOfSignIn(String email, String password) {
        JsonNode claims = TestKeycloak.claims(keycloak.userToken(email, password));
        return TestTokens.token().subject(claims.path("sub").asString())
                .tenant(UUID.fromString(claims.path("tenant_id").asString()))
                .email(claims.path("email").asString())
                .emailVerified(claims.path("email_verified").asBoolean())
                .bearer();
    }

    /** After the board invited again: the account is off, has no password and no session, so the old one is useless. */
    private void assertLockedOut(String email, String keycloakId) {
        assertThat(keycloak.signIn(email, PASSWORD)).as("the old password is refused").isNotEqualTo(200);
        JsonNode account = user(email);
        assertThat(account.path("id").asString()).as("the same account").isEqualTo(keycloakId);
        assertThat(account.path("enabled").asBoolean()).as("off until the new link is used").isFalse();
        assertThat(keycloak.credentials(keycloakId)).as("no credential is left").isEmpty();
        assertThat(keycloak.sessions(keycloakId)).as("every session ended").isEmpty();
    }

    @Test
    void reinvitingADisabledMemberRefusesTheOldPasswordAndTheNewLinkSignsInToTheSameMember() throws Exception {
        String email = freshEmail();
        UUID member = newMember(DEMO_TENANT, "Коваль", "Олена", null, email);
        String keycloakId = activate(member, email);
        http.disable(chairman(), member).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(20)).until(() -> !user(email).path("enabled").asBoolean());

        http.invite(chairman(), member).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVITED"));

        assertLockedOut(email, keycloakId);
        await().atMost(Duration.ofSeconds(20)).until(() -> mailpit.lettersTo(email).size() == 2);
        http.accept(InvitationRequests.tokenOfLetterTo(email), NEW_PASSWORD).andExpect(status().isNoContent());

        assertThat(keycloak.signIn(email, PASSWORD)).as("the old password stays refused").isNotEqualTo(200);
        http.me(bearerOfSignIn(email, NEW_PASSWORD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(member.toString()));
        assertThat(keycloak.usersByEmail(email)).singleElement()
                .satisfies(account -> assertThat(account.path("id").asString()).isEqualTo(keycloakId));
        assertThat(subjectOf(DEMO_TENANT, member)).isEqualTo(keycloakId);
        http.card(chairman(), member).andExpect(jsonPath("$.access.status").value("ACTIVE"));
    }

    @Test
    void reinvitingBeforeTheListenerTurnedTheAccountOffStillEndsTheOldPasswordAndSessions() throws Exception {
        String email = freshEmail();
        UUID member = newMember(DEMO_TENANT, "Коваль", "Олена", null, email);
        String keycloakId = activate(member, email);
        keycloak.userToken(email, PASSWORD); // a session of the old password
        assertThat(keycloak.sessions(keycloakId)).isNotEmpty();
        // disabled, but the listener has not reached Keycloak yet: the account is still on with its sessions
        ownerJdbc().update("update member_access set state = 'DISABLED', disabled_at = now() where member_id = ?",
                member);

        http.invite(chairman(), member).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVITED"));

        assertLockedOut(email, keycloakId);
        http.accept(InvitationRequests.tokenOfLetterTo(email), NEW_PASSWORD).andExpect(status().isNoContent());
        http.me(bearerOfSignIn(email, NEW_PASSWORD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(member.toString()));
    }

    @Test
    void reinvitingAMemberWhoseLinkWasNeverUsedKillsTheOldLinkAndTheNewOneSignsIn() throws Exception {
        String email = freshEmail();
        UUID member = newMember(DEMO_TENANT, "Коваль", "Олена", null, email);
        http.invite(chairman(), member).andExpect(status().isOk());
        String oldToken = InvitationRequests.tokenOfLetterTo(email);
        String keycloakId = user(email).path("id").asString();
        http.disable(chairman(), member).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(20)).until(() -> !user(email).path("enabled").asBoolean());

        http.invite(chairman(), member).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVITED"));

        assertThat(user(email).path("enabled").asBoolean()).isFalse();
        http.inspect(oldToken).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        http.accept(oldToken, PASSWORD).andExpect(status().isNotFound());
        await().atMost(Duration.ofSeconds(20)).until(() -> mailpit.lettersTo(email).size() == 2);
        http.accept(InvitationRequests.tokenOfLetterTo(email), NEW_PASSWORD).andExpect(status().isNoContent());

        http.me(bearerOfSignIn(email, NEW_PASSWORD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(member.toString()));
        assertThat(keycloak.usersByEmail(email)).singleElement()
                .satisfies(account -> assertThat(account.path("id").asString()).isEqualTo(keycloakId));
    }

    @Test
    void anEmailChangeMovesTheLoginAndTheMemberSignsInWithTheNewOne() throws Exception {
        String email = freshEmail();
        UUID member = newMember(DEMO_TENANT, "Коваль", "Олена", null, email);
        String keycloakId = activate(member, email);
        String newEmail = freshEmail();

        http.update(chairman(), member, "Коваль", "Олена", newEmail).andExpect(status().isOk())
                .andExpect(jsonPath("$.access.status").value("INVITED"));

        await().atMost(Duration.ofSeconds(20)).until(() -> keycloak.usersByEmail(newEmail).size() == 1);
        JsonNode moved = user(newEmail);
        assertThat(moved.path("id").asString()).as("the same account").isEqualTo(keycloakId);
        assertThat(moved.path("username").asString()).isEqualTo(newEmail);
        assertThat(moved.path("emailVerified").asBoolean()).isFalse();
        assertThat(keycloak.usersByEmail(email)).isEmpty();
        assertThat(keycloak.signIn(email, PASSWORD)).as("the old login is gone").isNotEqualTo(200);
        http.me(TestTokens.bearer(DEMO_TENANT, keycloakId, newEmail)).andExpect(status().isForbidden());

        await().atMost(Duration.ofSeconds(20)).until(() -> mailpit.lettersTo(newEmail).size() == 1);
        http.accept(InvitationRequests.tokenOfLetterTo(newEmail), NEW_PASSWORD).andExpect(status().isNoContent());

        assertThat(keycloak.signIn(newEmail, NEW_PASSWORD)).isEqualTo(200);
        assertThat(user(newEmail).path("emailVerified").asBoolean()).isTrue();
        http.me(TestTokens.bearer(DEMO_TENANT, keycloakId, newEmail)).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(member.toString()));
    }

    @Test
    void anEmailChangeOfAnInvitedMemberKillsTheOldLink() throws Exception {
        String email = freshEmail();
        UUID member = newMember(DEMO_TENANT, "Коваль", "Олена", null, email);
        http.invite(chairman(), member).andExpect(status().isOk());
        String oldToken = InvitationRequests.tokenOfLetterTo(email);
        String newEmail = freshEmail();

        http.update(chairman(), member, "Коваль", "Олена", newEmail).andExpect(status().isOk());

        http.inspect(oldToken).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        await().atMost(Duration.ofSeconds(20)).until(() -> mailpit.lettersTo(newEmail).size() == 1);
        // the letter is out before the link is marked as sent (the second phase commits after it): wait for the mark
        await().atMost(Duration.ofSeconds(20)).until(() -> ownerJdbc().queryForObject("select count(*) from "
                + "member_invitation where member_id = ? and sent_at is not null and used_at is null "
                + "and revoked_at is null", Integer.class, member) == 1);
        String newToken = InvitationRequests.tokenOfLetterTo(newEmail);
        http.inspect(newToken).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(newEmail));
        http.accept(newToken, PASSWORD).andExpect(status().isNoContent());
        assertThat(keycloak.signIn(newEmail, PASSWORD)).isEqualTo(200);
    }

    @Test
    void anEmailThatSignsInToAnotherCooperativeIsRefusedOnChange() throws Exception {
        String email = freshEmail();
        UUID member = newMember(DEMO_TENANT, "Коваль", "Олена", null, email);
        activate(member, email);

        http.update(chairman(), member, "Коваль", "Олена", "member@other.local").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_IN_OTHER_COOPERATIVE"))
                .andExpect(jsonPath("$.errors[0].field").value("email"));

        assertThat(memberEmail(DEMO_TENANT, member)).isEqualTo(email);
        http.card(chairman(), member).andExpect(jsonPath("$.access.status").value("ACTIVE"));
        assertThat(keycloak.signIn(email, PASSWORD)).isEqualTo(200);
    }
}
