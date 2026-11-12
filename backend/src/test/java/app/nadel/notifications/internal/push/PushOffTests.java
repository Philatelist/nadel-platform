package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.notifications.TestPushDevice;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * A server without push keys (feature 021 §2.2): the application starts and works, the account learns that push is off
 * and gets no key, registering a device is 409 {@code PUSH_DISABLED}, the member can still list and delete their
 * devices, and issuing invoices queues no push.
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
    }
}
