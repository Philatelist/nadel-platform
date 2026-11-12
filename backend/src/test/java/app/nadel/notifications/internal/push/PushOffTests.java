package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.notifications.TestPushDevice;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * A server without push keys (feature 021 §2.2): the application starts and works, the account learns that push is off
 * and gets no key, registering a device is 409 {@code PUSH_DISABLED}, the member can still list and delete their
 * devices, issuing invoices queues no push, and the announcement form counts nobody as getting the push.
 */
@TestPropertySource(properties = "nadel.test.push-public-key=")
class PushOffTests extends PushTestSupport {

    @Autowired
    VapidKeys keys;

    @Test
    void withoutKeysPushIsOffAndTheRestWorks() throws Exception {
        String member = member("Вимкнено");
        UUID memberId = lastPerson;
        charge(plot("1", memberId), "100.00");

        assertThat(keys.enabled()).isFalse();
        assertThat(keys.offReason()).contains(VapidKeys.Reason.MISSING);
        send(member, get("/api/v1/me/push"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.publicKey").doesNotExist());
        send(member, post(DEVICES), new TestPushDevice().registration("uk", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUSH_DISABLED"));
        send(member, get(DEVICES), null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        send(member, delete(DEVICES + "/" + UUID.randomUUID()), null).andExpect(status().isNotFound());

        assertThat(buildInvoices().get("built").asInt()).isEqualTo(1);
        assertThat(queuedPushes()).isEmpty();

        // a device kept from before counts for nobody: the announcement form says no one gets the push
        ownerJdbc().update("insert into push_subscription (id, tenant_id, member_id, endpoint_enc, p256dh_enc, "
                + "auth_enc, endpoint_index, server_key_id, language) values (gen_random_uuid(), ?, ?, '\\x01', "
                + "'\\x02', '\\x03', sha256(?::bytea), '\\x0102030405060708', 'uk')", tenant, memberId,
                memberId.toString().getBytes());
        send(chairman, post("/api/v1/announcements?preview=true"), Map.of("text", "Збори", "streets", List.of(),
                "channels", List.of("EMAIL"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.pushMembers").value(0));
    }
}
