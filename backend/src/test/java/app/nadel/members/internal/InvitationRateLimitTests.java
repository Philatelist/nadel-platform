package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.identity.FakeSignInAccounts;
import app.nadel.notifications.Mailpit;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The invitation limits over HTTP (feature 020, owner decisions on PR 71), here 5 per 15 minutes for each counter: a
 * usable link opens any number of times from one address; the sixth link refused as {@code INVITATION_INVALID}
 * (unknown or expired) from one address blocks every public request from it, other addresses are not affected; the
 * sixth sent invitation of one member (invite and resend together) is 429 {@code TOO_MANY_REQUESTS} from any address,
 * and so is the sixth from one address; an invitation that is refused is not counted. The window itself is tested with
 * a moving clock in {@link InvitationRateLimiterTests}. Every test uses its own addresses and members, as the counters
 * live as long as the context.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "nadel.test.invitation-rate-limit=5")
class InvitationRateLimitTests extends IntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeSignInAccounts accounts;

    private final Mailpit mailpit = Mailpit.shared();

    @BeforeEach
    void setUp() throws Exception {
        mailpit.clear();
        new InvitationRequests(mvc).mailboxOnMailpit();
    }

    @AfterEach
    void cleanUp() {
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from event_publication where completion_date is null", Integer.class) == 0);
        ownerJdbc().update("delete from delivery_settings where tenant_id = ?", DEMO_TENANT);
        accounts.reset();
        unlinkByEmail(DEMO_TENANT, "chairman@demo.local");
    }

    private static String address() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }

    private static String freshEmail() {
        return "limit-" + UUID.randomUUID() + "@example.com";
    }

    private static MockHttpServletRequestBuilder from(String address, MockHttpServletRequestBuilder request) {
        return request.with(r -> {
            r.setRemoteAddr(address);
            return r;
        });
    }

    private ResultActions inspect(String address, String token) throws Exception {
        return mvc.perform(from(address, post("/api/v1/public/invitations/inspect")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonMapper.shared().writeValueAsString(Map.of("token", token)))));
    }

    private ResultActions accept(String address, String token) throws Exception {
        return mvc.perform(from(address, post("/api/v1/public/invitations/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonMapper.shared().writeValueAsString(Map.of("token", token, "password", PASSWORD)))));
    }

    private ResultActions invite(String address, UUID member) throws Exception {
        return mvc.perform(from(address, post("/api/v1/members/" + member + "/invitation")
                .header(HttpHeaders.AUTHORIZATION, InvitationRequests.chairman())));
    }

    private ResultActions resend(String address, UUID member) throws Exception {
        return mvc.perform(from(address, post("/api/v1/members/" + member + "/invitation/resend")
                .header(HttpHeaders.AUTHORIZATION, InvitationRequests.chairman())));
    }

    private UUID invitee(String email) {
        return newMember(DEMO_TENANT, "Ліміт", "Тест", null, email);
    }

    /** A link that expired an hour ago, of a fresh member; answers its token. */
    private String expiredLink() {
        UUID member = invitee(freshEmail());
        String token = InvitationTokens.newToken();
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusHours(73);
        inTenant(DEMO_TENANT, sql -> sql.update("""
                insert into member_invitation (tenant_id, member_id, token_hash, email_bidx, created_at, expires_at,
                    sent_at)
                select tenant_id, id, ?, email_bidx, ?, ?, ? from member where id = ?
                """, InvitationTokens.hash(token), past, past.plusHours(72), past, member));
        return token;
    }

    @Test
    void aUsableLinkOpensAnyNumberOfTimesFromOneAddress(CapturedOutput output) throws Exception {
        String email = freshEmail();
        invite(address(), invitee(email)).andExpect(status().isOk());
        String token = InvitationRequests.tokenOfLetterTo(email);
        String client = address();

        for (int i = 0; i < 20; i++) {
            inspect(client, token).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        }
        accept(client, token).andExpect(status().isNoContent());

        assertThat(output).doesNotContain(token).doesNotContain(email);
    }

    @Test
    void theSixthFailedLinkFromOneAddressBlocksItAndOtherAddressesAreNot() throws Exception {
        String email = freshEmail();
        invite(address(), invitee(email)).andExpect(status().isOk());
        String usable = InvitationRequests.tokenOfLetterTo(email);
        String client = address();
        inspect(client, expiredLink()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        accept(client, expiredLink()).andExpect(status().isNotFound());
        for (int i = 0; i < 3; i++) {
            inspect(client, InvitationTokens.newToken()).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        }

        // blocked: even a usable link is refused from this address, on both endpoints
        inspect(client, usable).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.status").value(429));
        accept(client, usable).andExpect(status().isTooManyRequests());

        // another address opens it, again and again
        String other = address();
        for (int i = 0; i < 6; i++) {
            inspect(other, usable).andExpect(status().isOk());
        }
        accept(other, usable).andExpect(status().isNoContent());
    }

    @Test
    void theSixthSentInvitationOfOneMemberIsRefusedFromAnyAddress() throws Exception {
        String email = freshEmail();
        UUID member = invitee(email);
        invite(address(), member).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            resend(address(), member).andExpect(status().isOk());
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> mailpit.lettersTo(email).size() == 5);

        resend(address(), member).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));

        Thread.sleep(500); // a letter, if one were sent, would be in Mailpit by now
        assertThat(mailpit.lettersTo(email)).as("nothing more is sent").hasSize(5);
        // another member is not affected
        invite(address(), invitee(freshEmail())).andExpect(status().isOk());
    }

    @Test
    void theSixthSentInvitationFromOneAddressIsRefusedAndOtherAddressesAreNot() throws Exception {
        String client = address();
        for (int i = 0; i < 5; i++) {
            invite(client, invitee(freshEmail())).andExpect(status().isOk());
        }

        UUID sixth = invitee(freshEmail());
        invite(client, sixth).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        invite(address(), sixth).andExpect(status().isOk());
    }

    @Test
    void anInvitationThatIsRefusedIsNotCounted() throws Exception {
        String client = address();
        UUID noEmail = invitee(null);
        for (int i = 0; i < 6; i++) {
            invite(client, noEmail).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MEMBER_EMAIL_MISSING"));
        }
        invite(client, invitee(freshEmail())).andExpect(status().isOk());
    }
}
