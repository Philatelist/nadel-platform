package app.nadel.notifications.internal;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import app.nadel.TestTokens;
import app.nadel.notifications.FakeBotApi;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** HTTP helpers of the delivery settings tests (feature 017 §2.1); the delivery rows are removed after each test. */
abstract class DeliverySettingsTestSupport extends NotificationsTestSupport {

    static final String SETTINGS = "/api/v1/settings/delivery";
    static final String TEST_EMAIL = SETTINGS + "/test-email";
    static final String TEST_TELEGRAM = SETTINGS + "/test-telegram";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @AfterEach
    void unlinkDemoMembers() {
        // the audit entries of this test are written before the next one counts its own
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from event_publication where completion_date is null "
                        + "and serialized_event like '%DELIVERY_SETTINGS_CHANGED%'", Integer.class) == 0);
        // a first sign-in links the demo members to a subject; other tests expect them unlinked
        unlinkByEmail(DEMO_TENANT, "chairman@demo.local", "treasurer@demo.local", "member@demo.local");
        unlinkByEmail(OTHER_TENANT, "member@other.local");
        FakeBotApi.shared().reset();
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

    /** A chairman of the other demo cooperative (it has none of its own); removed after the test. */
    String otherChairman() {
        String email = "delivery-chairman-" + UUID.randomUUID() + "@other.local";
        UUID member = newMember(OTHER_TENANT, email);
        giveBaseRole(OTHER_TENANT, member, "CHAIRMAN");
        return TestTokens.bearer(OTHER_TENANT, "sub-" + email, email);
    }

    ResultActions read(String bearer) throws Exception {
        return mvc.perform(get(SETTINGS).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    ResultActions save(String bearer, Map<String, ?> body) throws Exception {
        return mvc.perform(put(SETTINGS)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    ResultActions testEmail(String bearer) throws Exception {
        return mvc.perform(post(TEST_EMAIL).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    ResultActions testTelegram(String bearer) throws Exception {
        return mvc.perform(post(TEST_TELEGRAM).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    /** A mailbox section of a change; {@code password} null leaves it out. */
    static Map<String, Object> mailbox(String host, int port, String user, String password) {
        var email = new java.util.LinkedHashMap<String, Object>();
        email.put("host", host);
        email.put("port", port);
        email.put("user", user);
        if (password != null) {
            email.put("password", password);
        }
        email.put("fromAddress", "board@coop.example");
        email.put("fromName", "СТ «Надія»");
        return email;
    }

    /** The audit entries of the delivery settings of the cooperative, oldest first. */
    List<Map<String, Object>> auditEntries(UUID tenant) {
        return inTenant(tenant, sql -> sql.queryForList(
                "select action, entity_label, cast(changes as text) as changes from audit_entry "
                        + "where entity_type = 'DELIVERY_SETTINGS' and entity_id = ? order by occurred_at, id",
                tenant));
    }
}
