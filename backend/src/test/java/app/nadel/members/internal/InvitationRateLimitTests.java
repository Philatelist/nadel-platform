package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * The limit of the public invitation endpoints over HTTP (feature 020, owner decision on PR 71), with the default of 5
 * requests per 15 minutes: the sixth request from one client address is 429 {@code TOO_MANY_REQUESTS}, other addresses
 * are not affected, the e-mail of one invitation is limited across addresses, an unknown token meets only the address
 * limit, and each endpoint counts apart. The window itself is tested with a moving clock in
 * {@link InvitationRateLimiterTests}. Every test uses its own addresses and links, as the counters live as long as the
 * context.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "nadel.test.invitation-rate-limit=5")
class InvitationRateLimitTests extends IntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    MockMvc mvc;

    private static String address() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }

    private ResultActions inspect(String address, String token) throws Exception {
        return mvc.perform(post("/api/v1/public/invitations/inspect").contentType(MediaType.APPLICATION_JSON)
                .content(JsonMapper.shared().writeValueAsString(Map.of("token", token)))
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                }));
    }

    private ResultActions accept(String address, String token) throws Exception {
        return mvc.perform(post("/api/v1/public/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                .content(JsonMapper.shared().writeValueAsString(Map.of("token", token, "password", PASSWORD)))
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                }));
    }

    private static int statusOf(ResultActions result) {
        return result.andReturn().getResponse().getStatus();
    }

    /** A sent link of a fresh member of the demo cooperative, made for the member's e-mail; answers its token. */
    private String link(String email) {
        UUID member = newMember(DEMO_TENANT, "Ліміт", "Тест", null, email);
        String token = InvitationTokens.newToken();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        inTenant(DEMO_TENANT, sql -> sql.update("""
                insert into member_invitation (tenant_id, member_id, token_hash, email_bidx, created_at, expires_at,
                    sent_at)
                select tenant_id, id, ?, email_bidx, ?, ?, ? from member where id = ?
                """, InvitationTokens.hash(token), now, now.plusHours(72), now, member));
        return token;
    }

    @Test
    void theSixthRequestFromOneAddressIsRefusedAndOtherAddressesAreNot() throws Exception {
        String client = address();
        for (int i = 0; i < 5; i++) {
            inspect(client, InvitationTokens.newToken()).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        }

        inspect(client, InvitationTokens.newToken()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.status").value(429));

        inspect(address(), InvitationTokens.newToken()).andExpect(status().isNotFound());
        // the other endpoint counts apart
        accept(client, InvitationTokens.newToken()).andExpect(status().isNotFound());
    }

    @Test
    void theEmailOfOneInvitationIsLimitedAcrossAddresses(CapturedOutput output) throws Exception {
        String email = "limit-" + UUID.randomUUID() + "@example.com";
        String token = link(email);
        for (int i = 0; i < 5; i++) {
            assertThat(statusOf(inspect(address(), token))).isNotEqualTo(429);
        }

        inspect(address(), token).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        accept(address(), token).andExpect(status().isNotFound()); // the other endpoint counts apart

        // another invitation, and a token of no link (only the address limit), are not affected
        String other = link("limit-" + UUID.randomUUID() + "@example.com");
        assertThat(statusOf(inspect(address(), other))).isNotEqualTo(429);
        inspect(address(), InvitationTokens.newToken()).andExpect(status().isNotFound());

        assertThat(output).doesNotContain(token).doesNotContain(email);
    }

    @Test
    void acceptIsLimitedByAddressBeforeAnythingIsChecked() throws Exception {
        String client = address();
        String token = link("limit-" + UUID.randomUUID() + "@example.com");
        for (int i = 0; i < 5; i++) {
            accept(client, InvitationTokens.newToken()).andExpect(status().isNotFound());
        }

        accept(client, token).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        // the refused request did not count against the link's e-mail
        for (int i = 0; i < 5; i++) {
            assertThat(statusOf(accept(address(), token))).isNotEqualTo(429);
        }
        accept(address(), token).andExpect(status().isTooManyRequests());
    }
}
