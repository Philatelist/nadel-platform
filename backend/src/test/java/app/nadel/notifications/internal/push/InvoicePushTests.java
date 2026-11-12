package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.documents.InvoicesIssued;
import app.nadel.notifications.FakePushService.Received;
import app.nadel.notifications.TestPushDevice;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * «Новий рахунок за {місяць}» end to end on the server (feature 021 §2.2): issuing invoices pushes once to every device
 * of the current owners of the plots with a new version, through JobRunr jobs that the in-JVM push service receives
 * and the device decrypts to the code and the month only; an unchanged rebuild sends nothing; an invoice issued on
 * request pushes to its plot's owners; a device that is gone is deleted; an outage is retried until the service is
 * back; a redelivered event sends once; five refusals in a row delete a device; a device of another key pair is
 * dropped; the logs and the job table never carry an endpoint, a key or a name.
 */
@ExtendWith(OutputCaptureExtension.class)
class InvoicePushTests extends PushTestSupport {

    @Autowired
    InvoicePushes invoicePushes;

    private String expected(String language) {
        return "{\"v\":1,\"code\":\"INVOICES_ISSUED\",\"params\":{\"month\":\"" + month() + "\"},"
                + "\"url\":\"/account#my-invoices\",\"lang\":\"" + language + "\"}";
    }

    @Test
    void theOwnersOfThePlotsWithNewInvoicesGetOnePushPerDevice() throws Exception {
        String anna = member("Анна");
        UUID annaId = lastPerson;
        String borys = member("Борис");
        UUID borysId = lastPerson;
        String chorna = member("Чорна");
        UUID chornaId = lastPerson;
        String dmytro = member("Дмитро");
        UUID shared = plot("1", annaId, borysId);
        charge(shared, "100.00");
        charge(plot("2", annaId), "250.50");
        plot("3", chornaId); // nothing due: skipped, no invoice
        TestPushDevice annaPhone = new TestPushDevice();
        TestPushDevice annaLaptop = new TestPushDevice();
        TestPushDevice borysPhone = new TestPushDevice();
        TestPushDevice chornaPhone = new TestPushDevice();
        TestPushDevice dmytroPhone = new TestPushDevice();
        register(anna, annaPhone, "uk");
        register(anna, annaLaptop, "uk");
        register(borys, borysPhone, "en");
        register(chorna, chornaPhone, "uk");
        register(dmytro, dmytroPhone, "uk");

        assertThat(buildInvoices().get("built").asInt()).isEqualTo(2);

        assertThat(queuedPushes()).hasSize(3).allSatisfy(job -> {
            assertThat(job.code()).isEqualTo("INVOICES_ISSUED");
            assertThat(job.month()).isEqualTo(month().toString());
            assertThat(job.attempt()).isZero();
        });
        for (String job : storedJobs()) {
            for (TestPushDevice device : List.of(annaPhone, annaLaptop, borysPhone)) {
                assertThat(job).doesNotContain(device.id()).doesNotContain(device.p256dh());
            }
            assertThat(job).doesNotContain("Анна").doesNotContain("127.0.0.1");
        }
        assertThat(runQueuedPushes()).containsOnly("DELIVERED").hasSize(3);

        assertThat(annaPhone.decrypt(only(annaPhone).body())).isEqualTo(expected("uk"));
        assertThat(annaLaptop.decrypt(only(annaLaptop).body())).isEqualTo(expected("uk"));
        assertThat(borysPhone.decrypt(only(borysPhone).body())).isEqualTo(expected("en"));
        assertThat(pushService.received(chornaPhone.id())).isEmpty();
        assertThat(pushService.received(dmytroPhone.id())).isEmpty();
        Map<String, String> headers = only(annaPhone).headers();
        assertThat(Long.parseLong(headers.get("ttl"))).isBetween(259_000L, 259_200L);
        assertThat(headers.get("content-encoding")).isEqualTo("aes128gcm");
        assertThat(headers.get("urgency")).isEqualTo("normal");
        assertThat(headers.get("authorization")).startsWith("vapid t=").endsWith(", k=" + PUSH_KEYS[0]);
        assertThat(devices()).filteredOn(row -> row.get("last_sent_at") != null).hasSize(3);
        assertThat(queuedPushes()).isEmpty();
    }

    @Test
    void anUnchangedRebuildSendsNothingAndAnInvoiceOnRequestPushesItsPlotsOwners() throws Exception {
        String anna = member("Анна");
        UUID annaId = lastPerson;
        String borys = member("Борис");
        UUID borysId = lastPerson;
        UUID first = plot("1", annaId);
        UUID second = plot("2", borysId);
        charge(first, "100.00");
        charge(second, "100.00");
        TestPushDevice annaPhone = new TestPushDevice();
        TestPushDevice borysPhone = new TestPushDevice();
        register(anna, annaPhone, "uk");
        register(borys, borysPhone, "uk");
        buildInvoices();
        assertThat(runQueuedPushes()).hasSize(2);
        pushService.reset();

        assertThat(buildInvoices().get("unchanged").asInt()).isEqualTo(2);
        assertThat(queuedPushes()).isEmpty();

        charge(second, "40.00");
        issue(second).andExpect(status().isCreated());

        assertThat(runQueuedPushes()).containsExactly("DELIVERED");
        assertThat(pushService.received(annaPhone.id())).isEmpty();
        assertThat(borysPhone.decrypt(only(borysPhone).body())).isEqualTo(expected("uk"));
        issue(second).andExpect(status().isOk()); // nothing changed: no new version, no push
        assertThat(queuedPushes()).isEmpty();
    }

    @Test
    void aDeviceThatIsGoneIsDeletedAndNotTriedAgain() throws Exception {
        String anna = member("Анна");
        UUID annaId = lastPerson;
        UUID first = plot("1", annaId);
        charge(first, "100.00");
        TestPushDevice reset = new TestPushDevice();
        TestPushDevice kept = new TestPushDevice();
        UUID resetId = register(anna, reset, "uk");
        register(anna, kept, "uk");
        pushService.gone(reset.id());

        buildInvoices();

        assertThat(runQueuedPushes()).containsExactlyInAnyOrder("GONE_DELETED", "DELIVERED");
        assertThat(devices()).extracting(row -> row.get("id")).doesNotContain(resetId).hasSize(1);
        charge(first, "20.00");
        issue(first).andExpect(status().isCreated());
        assertThat(queuedPushes()).hasSize(1);
        assertThat(runQueuedPushes()).containsExactly("DELIVERED");
        assertThat(pushService.received(kept.id())).hasSize(2);
        assertThat(pushService.received(reset.id())).isEmpty();
    }

    @Test
    void anOutageIsRetriedAndThePushArrivesOnceTheServiceIsBack() throws Exception {
        String anna = member("Анна");
        UUID annaId = lastPerson;
        charge(plot("1", annaId), "100.00");
        TestPushDevice phone = new TestPushDevice();
        UUID phoneId = register(anna, phone, "uk");
        pushService.fail(phone.id(), 503, 1, "120");
        pushService.fail(phone.id(), 500, 1, null);

        buildInvoices().get("built").asInt(); // the invoices are issued as usual
        Instant before = Instant.now();
        assertThat(runQueuedPushes()).containsExactly("RETRY_SCHEDULED");

        List<Map.Entry<SendPush, Instant>> scheduled = scheduledPushes();
        assertThat(scheduled).singleElement().satisfies(entry -> {
            assertThat(entry.getKey().attempt()).isEqualTo(1);
            assertThat(entry.getKey().subscriptionId()).isEqualTo(phoneId);
            // Retry-After 120 s is longer than the first delay of 1 min
            assertThat(entry.getValue()).isBetween(before.plusSeconds(119), Instant.now().plusSeconds(121));
        });
        assertThat(pushService.received(phone.id())).isEmpty();
        assertThat(devices()).singleElement().satisfies(row ->
                assertThat(((Number) row.get("consecutive_failures")).intValue()).isZero());

        assertThat(runScheduledPushes()).containsExactly("RETRY_SCHEDULED");
        assertThat(scheduledPushes()).singleElement().satisfies(entry -> {
            assertThat(entry.getKey().attempt()).isEqualTo(2);
            assertThat(entry.getValue()).isBetween(Instant.now().plus(Duration.ofMinutes(5)).minusSeconds(5),
                    Instant.now().plus(Duration.ofMinutes(5)));
        });

        assertThat(runScheduledPushes()).containsExactly("DELIVERED");
        assertThat(phone.decrypt(only(phone).body())).isEqualTo(expected("uk"));
        assertThat(scheduledPushes()).isEmpty();
    }

    @Test
    void aRedeliveredEventSendsOnce() throws Exception {
        String anna = member("Анна");
        UUID annaId = lastPerson;
        UUID first = plot("1", annaId);
        TestPushDevice phone = new TestPushDevice();
        TestPushDevice laptop = new TestPushDevice();
        register(anna, phone, "uk");
        register(anna, laptop, "en");
        InvoicesIssued event = new InvoicesIssued(tenant, month(), UUID.randomUUID(), List.of(first));

        assertThat(invoicePushes.queue(event)).isEqualTo(2);
        assertThat(invoicePushes.queue(event)).isEqualTo(2); // handed over again, JobRunr keeps the first jobs

        assertThat(queuedPushes()).hasSize(2);
        assertThat(runQueuedPushes()).containsOnly("DELIVERED").hasSize(2);
        invoicePushes.queue(event); // after the jobs ran
        assertThat(queuedPushes()).isEmpty();
        assertThat(pushService.received(phone.id())).hasSize(1);
        assertThat(pushService.received(laptop.id())).hasSize(1);
        // another build is another message
        invoicePushes.queue(new InvoicesIssued(tenant, month(), UUID.randomUUID(), List.of(first)));
        assertThat(queuedPushes()).hasSize(2);
    }

    @Test
    void fiveRefusalsInARowDeleteADeviceAndADeliveryClearsThem() throws Exception {
        String anna = member("Анна");
        UUID annaId = lastPerson;
        UUID first = plot("1", annaId);
        TestPushDevice phone = new TestPushDevice();
        register(anna, phone, "uk");
        pushService.fail(phone.id(), 400, 2, null);

        for (int i = 0; i < 2; i++) {
            invoicePushes.queue(new InvoicesIssued(tenant, month(), UUID.randomUUID(), List.of(first)));
            assertThat(runQueuedPushes()).containsExactly("REJECTED");
        }
        assertThat(devices()).singleElement().satisfies(row ->
                assertThat(((Number) row.get("consecutive_failures")).intValue()).isEqualTo(2));
        invoicePushes.queue(new InvoicesIssued(tenant, month(), UUID.randomUUID(), List.of(first)));
        assertThat(runQueuedPushes()).containsExactly("DELIVERED");
        assertThat(devices()).singleElement().satisfies(row ->
                assertThat(((Number) row.get("consecutive_failures")).intValue()).isZero());

        pushService.fail(phone.id(), 403, 5, null);
        for (int i = 0; i < 4; i++) {
            invoicePushes.queue(new InvoicesIssued(tenant, month(), UUID.randomUUID(), List.of(first)));
            assertThat(runQueuedPushes()).containsExactly("REJECTED");
        }
        invoicePushes.queue(new InvoicesIssued(tenant, month(), UUID.randomUUID(), List.of(first)));
        assertThat(runQueuedPushes()).containsExactly("REJECTED_DELETED");
        assertThat(devices()).isEmpty();
    }

    @Test
    void aDeviceMadeWithAnotherKeyPairIsDroppedWithoutASend() throws Exception {
        String anna = member("Анна");
        UUID annaId = lastPerson;
        UUID first = plot("1", annaId);
        TestPushDevice before = new TestPushDevice();
        TestPushDevice after = new TestPushDevice();
        UUID beforeId = register(anna, before, "uk");
        UUID afterId = register(anna, after, "uk");

        // a job queued before the rotation finds its device of the old key pair
        invoicePushes.queue(new InvoicesIssued(tenant, month(), UUID.randomUUID(), List.of(first)));
        ownerJdbc().update("update push_subscription set server_key_id = '\\x0102030405060708' where id = ?", afterId);
        assertThat(runQueuedPushes()).containsExactlyInAnyOrder("DELIVERED", "OTHER_KEY_DELETED");
        // the next event drops the old-key device before queueing anything
        ownerJdbc().update("update push_subscription set server_key_id = '\\x0102030405060708' where id = ?", beforeId);
        assertThat(invoicePushes.queue(new InvoicesIssued(tenant, month(), UUID.randomUUID(), List.of(first))))
                .isZero();

        assertThat(devices()).isEmpty();
        assertThat(pushService.received(after.id())).isEmpty();
        assertThat(pushService.received(before.id())).hasSize(1);
    }

    @Test
    void theLogsCarryTheCooperativeTheMemberTheCodeAndTheOutcomeOnly(CapturedOutput output) throws Exception {
        String anna = member("Анна");
        UUID annaId = lastPerson;
        charge(plot("1", annaId), "100.00");
        TestPushDevice delivered = new TestPushDevice();
        TestPushDevice gone = new TestPushDevice();
        TestPushDevice failing = new TestPushDevice();
        TestPushDevice down = new TestPushDevice();
        for (TestPushDevice device : List.of(delivered, gone, failing, down)) {
            register(anna, device, "uk");
        }
        pushService.gone(gone.id());
        pushService.fail(failing.id(), 400, 1, null);
        pushService.fail(down.id(), 503, 1, null);

        buildInvoices();
        assertThat(runQueuedPushes()).containsExactlyInAnyOrder("DELIVERED", "GONE_DELETED", "REJECTED",
                "RETRY_SCHEDULED");

        String logged = output.getAll();
        assertThat(logged).contains("Push INVOICES_ISSUED to a device of member " + annaId + " of cooperative "
                + tenant + ": DELIVERED");
        assertThat(logged).contains(": GONE_DELETED 410").contains(": REJECTED 400").contains(": RETRY_SCHEDULED 503");
        String port = pushService.origin().substring(pushService.origin().lastIndexOf(':') + 1);
        for (TestPushDevice device : List.of(delivered, gone, failing, down)) {
            assertThat(logged).doesNotContain(device.id()).doesNotContain(device.p256dh())
                    .doesNotContain(device.auth());
        }
        assertThat(logged).doesNotContain(pushService.origin()).doesNotContain("127.0.0.1:" + port)
                .doesNotContain("vapid t=").doesNotContain(PUSH_KEYS[1]);
    }

    private Received only(TestPushDevice device) {
        List<Received> received = pushService.received(device.id());
        assertThat(received).hasSize(1);
        return received.getFirst();
    }
}
