package app.nadel.members.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import app.nadel.TestTokens;
import app.nadel.notifications.Mailpit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP helpers of the invitation tests (feature 020): the board's invite call, the public set-password calls (without
 * any token), the mailbox of the demo cooperative pointed at Mailpit or at a closed port, and the link of a letter.
 */
final class InvitationRequests {

    static final UUID DEMO_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Pattern LINK = Pattern.compile("(\\S+/set-password\\?token=([A-Za-z0-9_-]+))");

    private final MockMvc mvc;

    InvitationRequests(MockMvc mvc) {
        this.mvc = mvc;
    }

    static String demo(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }

    static String chairman() {
        return demo("chairman@demo.local");
    }

    static String operator() {
        return TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();
    }

    ResultActions invite(String bearer, UUID member) throws Exception {
        return mvc.perform(post("/api/v1/members/" + member + "/invitation").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    ResultActions card(String bearer, UUID member) throws Exception {
        return mvc.perform(get("/api/v1/members/" + member).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    ResultActions me(String bearer) throws Exception {
        return mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    /** {@code POST /public/invitations/inspect} without an Authorization header. */
    ResultActions inspect(String token) throws Exception {
        return mvc.perform(post("/api/v1/public/invitations/inspect").contentType(MediaType.APPLICATION_JSON)
                .content(JsonMapper.shared().writeValueAsString(Map.of("token", token))));
    }

    /** {@code POST /public/invitations/accept} without an Authorization header. */
    ResultActions accept(String token, String password) throws Exception {
        return mvc.perform(post("/api/v1/public/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                .content(JsonMapper.shared().writeValueAsString(Map.of("token", token, "password", password))));
    }

    /** The demo cooperative's mailbox on Mailpit (saved by the chairman, as the settings screen does). */
    void mailboxOnMailpit() throws Exception {
        Mailpit mailpit = Mailpit.shared();
        saveMailbox(mailpit.host(), mailpit.smtpPort());
    }

    /** The demo cooperative's mailbox on a port nobody listens on: every letter fails with CONNECTION_FAILED. */
    void mailboxOn(String host, int port) throws Exception {
        saveMailbox(host, port);
    }

    private void saveMailbox(String host, int port) throws Exception {
        Map<String, Object> email = new LinkedHashMap<>();
        email.put("host", host);
        email.put("port", port);
        email.put("user", Mailpit.USER);
        email.put("password", Mailpit.PASSWORD);
        email.put("fromAddress", "board@coop.example");
        email.put("fromName", "СТ «Надія»");
        mvc.perform(put("/api/v1/settings/delivery").header(HttpHeaders.AUTHORIZATION, chairman())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.shared().writeValueAsString(Map.of("email", email))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    /** The whole set-password link of a letter's text. */
    static String linkIn(String text) {
        Matcher matcher = LINK.matcher(text);
        if (!matcher.find()) {
            throw new AssertionError("The letter has no set-password link");
        }
        return matcher.group(1);
    }

    /** The token of the set-password link of a letter's text. */
    static String tokenIn(String text) {
        Matcher matcher = LINK.matcher(text);
        if (!matcher.find()) {
            throw new AssertionError("The letter has no set-password link");
        }
        return matcher.group(2);
    }

    /** The token of the newest letter to {@code email} in Mailpit. */
    static String tokenOfLetterTo(String email) {
        Mailpit mailpit = Mailpit.shared();
        Mailpit.Letter letter = mailpit.lettersTo(email).getFirst();
        return tokenIn(mailpit.text(letter.id()));
    }
}
