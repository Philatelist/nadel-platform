package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.nadel.notifications.TestPushDevice;
import java.time.Duration;
import java.util.UUID;
import org.jobrunr.server.BackgroundJobServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * The push job on JobRunr's own background job server (feature 021, ADR 0007): the {@link SendPush} job an invoice
 * build queues is stored, read back and run by the server, and the device receives the message. The job's arguments
 * are ids, codes, the month and times only. The context is closed after the class, as for {@code DeliveryJobTests}:
 * a cached context would keep its server polling the shared database and run the jobs the later classes queue.
 */
@DirtiesContext
@TestPropertySource(properties = {"nadel.test.job-server=true",
        "jobrunr.background-job-server.poll-interval-in-seconds=5"})
class PushJobTests extends PushTestSupport {

    @Autowired
    BackgroundJobServer server;

    @Test
    void theJobAnInvoiceBuildQueuesDeliversThePush() throws Exception {
        String anna = member("Анна");
        UUID annaId = lastPerson;
        charge(plot("1", annaId), "100.00");
        TestPushDevice phone = new TestPushDevice();
        register(anna, phone, "uk");

        buildInvoices();

        assertThat(server.isRunning()).isTrue();
        await().atMost(Duration.ofSeconds(30)).until(() -> !pushService.received(phone.id()).isEmpty());
        assertThat(phone.decrypt(pushService.received(phone.id()).getFirst().body())).isEqualTo(
                "{\"v\":1,\"code\":\"INVOICES_ISSUED\",\"params\":{\"month\":\"" + month() + "\"},"
                        + "\"url\":\"/account#my-invoices\",\"lang\":\"uk\"}");
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from jobrunr_jobs where state = 'SUCCEEDED' and jobasjson like ? and jobasjson like ?",
                Integer.class, "%SendPush%", "%" + tenant + "%") == 1);
        assertThat(storedJobs()).singleElement().satisfies(job -> assertThat(job).contains("INVOICES_ISSUED")
                .doesNotContain(phone.id()).doesNotContain(phone.p256dh()).doesNotContain("Анна"));
        assertThat(devices()).singleElement().satisfies(row -> assertThat(row.get("last_sent_at")).isNotNull());
    }
}
