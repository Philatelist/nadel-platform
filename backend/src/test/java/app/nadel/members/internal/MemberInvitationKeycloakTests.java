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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * The invitation of feature 020 end to end against a real Keycloak with the project's realm ({@link TestKeycloak}) and
 * Mailpit: the chairman invites, the letter arrives, the set-password page inspects and accepts the link, and the
 * member signs in to Keycloak with the new password; a used or expired link is invalid; an e-mail that signs in to
 * another cooperative is refused; an existing account of the cooperative is linked, not duplicated; nothing writes the
 * link or the password down.
 */
@ExtendWith(OutputCaptureExtension.class)
class MemberInvitationKeycloakTests extends KeycloakIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final UUID DEMO_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");

    /** The application clock, moved forward to let a link expire. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock(ZoneId.of("Europe/Kyiv"));
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    MovableClock clock;

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
        clock.reset();
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from event_publication where completion_date is null", Integer.class) == 0);
        ownerJdbc().update("delete from delivery_settings where tenant_id = ?", DEMO_TENANT);
        ownerJdbc().update("delete from member_invitation where member_id = ?", DEMO_MEMBER);
        ownerJdbc().update("delete from member_access where member_id = ?", DEMO_MEMBER);
        unlinkByEmail(DEMO_TENANT, "chairman@demo.local", "member@demo.local");
    }

    private static String freshEmail() {
        return "kc-invitee-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void theMemberSetsThePasswordFromTheLetterAndSignsIn(CapturedOutput output) throws Exception {
        String email = freshEmail();
        UUID member = newMember(DEMO_TENANT, "Коваль", "Олена", null, email);

        http.invite(chairman(), member).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVITED"));

        List<JsonNode> users = keycloak.usersByEmail(email);
        assertThat(users).singleElement().satisfies(user -> {
            assertThat(user.path("username").asString()).isEqualTo(email);
            assertThat(user.path("emailVerified").asBoolean()).isFalse();
            assertThat(user.path("attributes").path("tenant_id").get(0).asString()).isEqualTo(DEMO_TENANT.toString());
        });
        String keycloakId = users.getFirst().path("id").asString();
        assertThat(subjectOf(DEMO_TENANT, member)).isEqualTo(keycloakId);
        assertThat(keycloak.signIn(email, PASSWORD)).as("no password before the link is used").isNotEqualTo(200);

        String token = InvitationRequests.tokenOfLetterTo(email);
        http.inspect(token).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        http.accept(token, PASSWORD).andExpect(status().isNoContent());

        assertThat(keycloak.signIn(email, PASSWORD)).isEqualTo(200);
        JsonNode user = keycloak.usersByEmail(email).getFirst();
        assertThat(user.path("emailVerified").asBoolean()).isTrue();
        assertThat(user.path("enabled").asBoolean()).isTrue();
        assertThat(user.path("requiredActions")).isEmpty();
        http.me(TestTokens.bearer(DEMO_TENANT, keycloakId, email)).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(member.toString()));
        http.card(chairman(), member).andExpect(jsonPath("$.access.status").value("ACTIVE"));

        // the link is used up
        http.accept(token, "another-password-1").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        assertThat(keycloak.signIn(email, "another-password-1")).isNotEqualTo(200);

        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from audit_entry where entity_id = ? and action = 'MEMBER_INVITED'", Integer.class,
                member) == 1);
        assertThat(output.getAll()).doesNotContain(token).doesNotContain(PASSWORD);
        assertThat(ownerJdbc().queryForObject("select count(*) from audit_entry where cast(changes as text) like ? "
                + "or cast(changes as text) like ?", Integer.class, "%" + token + "%", "%" + PASSWORD + "%")).isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from event_publication where serialized_event like ? "
                + "or serialized_event like ?", Integer.class, "%" + token + "%", "%" + PASSWORD + "%")).isZero();
    }

    @Test
    void aLinkOlderThanSeventyTwoHoursIsInvalid() throws Exception {
        String email = freshEmail();
        UUID member = newMember(DEMO_TENANT, "Коваль", "Олена", null, email);
        http.invite(chairman(), member).andExpect(status().isOk());
        String token = InvitationRequests.tokenOfLetterTo(email);

        clock.advance(Duration.ofHours(71));
        http.inspect(token).andExpect(status().isOk());
        clock.advance(Duration.ofHours(2));

        http.inspect(token).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        http.accept(token, PASSWORD).andExpect(status().isNotFound());
        assertThat(keycloak.signIn(email, PASSWORD)).isNotEqualTo(200);
    }

    @Test
    void anEmailThatSignsInToAnotherCooperativeIsRefused() throws Exception {
        UUID member = newMember(DEMO_TENANT, "Інший", "Кооператив", null, "member@other.local");

        http.invite(chairman(), member).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_IN_OTHER_COOPERATIVE"));

        assertThat(mailpit.letters()).isEmpty();
        assertThat(keycloak.usersByEmail("member@other.local")).singleElement().satisfies(user -> assertThat(
                user.path("attributes").path("tenant_id").get(0).asString()).isEqualTo(OTHER_TENANT.toString()));
        assertThat(subjectOf(DEMO_TENANT, member)).isNull();
    }

    @Test
    void theExistingAccountOfTheCooperativeIsLinkedNotDuplicated() throws Exception {
        assertThat(subjectOf(DEMO_TENANT, DEMO_MEMBER)).as("the demo member starts unlinked").isNull();
        String keycloakId = keycloak.usersByEmail("member@demo.local").getFirst().path("id").asString();

        http.invite(chairman(), DEMO_MEMBER).andExpect(status().isOk());

        assertThat(keycloak.usersByEmail("member@demo.local")).singleElement()
                .satisfies(user -> assertThat(user.path("id").asString()).isEqualTo(keycloakId));
        assertThat(subjectOf(DEMO_TENANT, DEMO_MEMBER)).isEqualTo(keycloakId);
        // invited: the old password of the demo account no longer lets the member into the application
        http.me(TestTokens.bearer(DEMO_TENANT, keycloakId, "member@demo.local")).andExpect(status().isForbidden());

        http.accept(InvitationRequests.tokenOfLetterTo("member@demo.local"), PASSWORD).andExpect(status().isNoContent());
        assertThat(keycloak.signIn("member@demo.local", PASSWORD)).isEqualTo(200);
        http.me(TestTokens.bearer(DEMO_TENANT, keycloakId, "member@demo.local")).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(DEMO_MEMBER.toString()));
    }

    /** The system clock plus an offset a test moves forward. */
    static final class MovableClock extends Clock {

        private final Clock base;
        private volatile Duration offset = Duration.ZERO;

        MovableClock(ZoneId zone) {
            this.base = Clock.system(zone);
        }

        void advance(Duration duration) {
            offset = offset.plus(duration);
        }

        void reset() {
            offset = Duration.ZERO;
        }

        @Override
        public ZoneId getZone() {
            return base.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return base.withZone(zone);
        }

        @Override
        public Instant instant() {
            return base.instant().plus(offset);
        }
    }
}
