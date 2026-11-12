package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.identity.Permission;
import app.nadel.ledger.PaymentRecorded;
import app.nadel.members.MemberAccessRevoked;
import app.nadel.members.RoleHolders;
import app.nadel.meters.ReadingDecided;
import app.nadel.meters.ReadingStatus;
import app.nadel.meters.ReadingSubmitted;
import app.nadel.notifications.FakePushService.Received;
import app.nadel.notifications.TestPushDevice;
import app.nadel.notifications.internal.AnnouncementSent;
import app.nadel.tenancy.TenantContext;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The other pushes end to end on the server (feature 021 §2.2): «Платіж зараховано» to the payer while the cooperative
 * sends payment notices, «Показ лічильника прийнято / відхилено» to the submitter, «Нове оголошення від правління» to
 * every recipient of an announcement (paper included), «Нові покази на перевірку» to the users who approve readings
 * (not the submitter) at most once in a rolling hour with an hour's time to live and its {@code Topic}, and leaving
 * the cooperative (archive, disabled access) removing every device. Each push reaches exactly the right members'
 * devices and decrypts to the code and the screen only; a redelivered event sends nothing again; the logs carry no
 * endpoint, key or name.
 */
@ExtendWith(OutputCaptureExtension.class)
class PushEventsTests extends PushTestSupport {

    @Autowired
    PaymentPushes paymentPushes;

    @Autowired
    ReadingPushes readingPushes;

    @Autowired
    AnnouncementPushes announcementPushes;

    @Autowired
    RevokedAccessDevices revokedAccessDevices;

    @Autowired
    RoleHolders roleHolders;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void removeReadingsAnnouncementsAndAccess() {
        awaitListeners();
        UUID id = tenant;
        ownerJdbc().update("delete from meter_reading where tenant_id = ?", id);
        ownerJdbc().update("delete from meter where tenant_id = ?", id);
        asLedgerOwner(sql -> {
            for (String table : List.of("delivery", "announcement", "delivery_settings", "member_invitation",
                    "member_access")) {
                sql.update("delete from " + table + " where tenant_id = ?", id);
            }
        });
    }

    private static String expected(String code, String url, String language) {
        return "{\"v\":1,\"code\":\"" + code + "\",\"params\":{},\"url\":\"" + url + "\",\"lang\":\"" + language + "\"}";
    }

    // ---- «Платіж зараховано»

    @Test
    void aRecordedPaymentPushesItsPayerOnlyOncePerDevice() throws Exception {
        String anna = member("Анна");
        UUID annaId = lastPerson;
        String borys = member("Борис");
        UUID borysId = lastPerson;
        UUID shared = plot("1", annaId, borysId);
        TestPushDevice annaPhone = new TestPushDevice();
        TestPushDevice annaLaptop = new TestPushDevice();
        TestPushDevice borysPhone = new TestPushDevice();
        register(anna, annaPhone, "uk");
        register(anna, annaLaptop, "en");
        register(borys, borysPhone, "uk");

        List<UUID> payments = pay(shared, annaId, "500.00");

        assertThat(queuedPushes()).hasSize(2).allSatisfy(job -> {
            assertThat(job.code()).isEqualTo("PAYMENT_RECORDED");
            assertThat(job.ref()).isEqualTo(payments.getFirst().toString());
            assertThat(job.month()).isNull();
        });
        assertThat(runQueuedPushes()).containsOnly("DELIVERED").hasSize(2);
        assertThat(annaPhone.decrypt(only(annaPhone).body()))
                .isEqualTo(expected("PAYMENT_RECORDED", "/account#my-charges", "uk"));
        assertThat(annaLaptop.decrypt(only(annaLaptop).body()))
                .isEqualTo(expected("PAYMENT_RECORDED", "/account#my-charges", "en"));
        // a co-owner who did not pay is not told by push
        assertThat(pushService.received(borysPhone.id())).isEmpty();

        // redelivered: the jobs already ran, nothing new
        paymentPushes.queue(new PaymentRecorded(tenant, payments));
        assertThat(queuedPushes()).isEmpty();
        assertThat(pushService.received(annaPhone.id())).hasSize(1);
    }

    @Test
    void nothingIsPushedWhileTheCooperativeSendsNoPaymentNoticesOrWithoutAPayer() throws Exception {
        String anna = member("Анна");
        UUID annaId = lastPerson;
        UUID first = plot("1", annaId);
        UUID second = plot("2", annaId);
        TestPushDevice phone = new TestPushDevice();
        register(anna, phone, "uk");

        List<UUID> withoutPayer = pay(first, null, "100.00");
        assertThat(queuedPushes()).isEmpty();

        send(chairman, put("/api/v1/settings/delivery"), Map.of("notifyPayments", false)).andExpect(status().isOk());
        List<UUID> one = pay(first, annaId, "200.00");
        List<UUID> other = pay(second, annaId, "300.00");
        assertThat(queuedPushes()).isEmpty();

        // switched on again: one event naming two payments of the same payer is one push per device
        send(chairman, put("/api/v1/settings/delivery"), Map.of("notifyPayments", true)).andExpect(status().isOk());
        List<UUID> both = new ArrayList<>(one);
        both.addAll(other);
        both.addAll(withoutPayer);
        assertThat(paymentPushes.queue(new PaymentRecorded(tenant, both))).isEqualTo(1);
        assertThat(runQueuedPushes()).containsExactly("DELIVERED");
        assertThat(phone.decrypt(only(phone).body())).isEqualTo(expected("PAYMENT_RECORDED", "/account#my-charges",
                "uk"));
    }

    // ---- «Показ лічильника прийнято / відхилено»

    @Test
    void theSubmitterIsToldOfTheBoardsDecision() throws Exception {
        String anna = member("Анна");
        UUID annaId = lastPerson;
        String borys = member("Борис");
        UUID borysId = lastPerson;
        UUID plot = plot("1", annaId, borysId);
        UUID meter = meter(plot, "ЕЛ-1");
        TestPushDevice annaPhone = new TestPushDevice();
        TestPushDevice borysPhone = new TestPushDevice();
        register(anna, annaPhone, "uk");
        register(borys, borysPhone, "uk");

        UUID approved = submit(anna, plot, meter, "1100");
        send(chairman, post("/api/v1/readings/" + approved + "/approve"), null).andExpect(status().isOk());
        assertThat(queuedPushes()).singleElement().satisfies(job -> {
            assertThat(job.code()).isEqualTo("READING_APPROVED");
            assertThat(job.ref()).isEqualTo(approved.toString());
        });
        assertThat(runQueuedPushes()).containsExactly("DELIVERED");
        assertThat(annaPhone.decrypt(only(annaPhone).body()))
                .isEqualTo(expected("READING_APPROVED", "/account#my-meters", "uk"));

        UUID rejected = submit(anna, plot, meter, "1200");
        send(chairman, post("/api/v1/readings/" + rejected + "/reject"), Map.of("reason", "Фото нечітке"))
                .andExpect(status().isOk());
        assertThat(runQueuedPushes()).containsExactly("DELIVERED");
        List<Received> received = pushService.received(annaPhone.id());
        assertThat(received).hasSize(2);
        assertThat(annaPhone.decrypt(received.get(1).body()))
                .isEqualTo(expected("READING_REJECTED", "/account#my-meters", "uk"));
        // the co-owner did not submit
        assertThat(pushService.received(borysPhone.id())).isEmpty();

        // redelivered decisions queue nothing new
        readingPushes.queue(new ReadingDecided(tenant, approved, plot, annaId, ReadingStatus.APPROVED));
        readingPushes.queue(new ReadingDecided(tenant, rejected, plot, annaId, ReadingStatus.REJECTED));
        assertThat(queuedPushes()).isEmpty();
    }

    // ---- «Нові покази на перевірку»

    @Test
    void theApproversExceptTheSubmitterGetOnePushAnHour() throws Exception {
        String chairmanToken = chairman;
        UUID chairmanId = memberOf("CHAIRMAN");
        // the chairman holds every permission whatever rows are stored
        ownerJdbc().update("delete from role_permission where tenant_id = ? and permission = 'READINGS_APPROVE'",
                tenant);
        String electrician = member("Електрик");
        UUID electricianId = lastPerson;
        grant(electricianId, Permission.READINGS_APPROVE);
        String submitter = member("Анна");
        UUID submitterId = lastPerson;
        grant(submitterId, Permission.READINGS_APPROVE); // an approver who submits is not told of their own reading
        String archivedApprover = member("Архівний");
        UUID archivedId = lastPerson;
        grant(archivedId, Permission.READINGS_APPROVE);
        String plain = member("Борис");
        UUID plot = plot("1", submitterId);
        UUID meter = meter(plot, "ЕЛ-1");

        TestPushDevice chairmanPhone = new TestPushDevice();
        TestPushDevice electricianPhone = new TestPushDevice();
        TestPushDevice electricianLaptop = new TestPushDevice();
        TestPushDevice submitterPhone = new TestPushDevice();
        TestPushDevice archivedPhone = new TestPushDevice();
        TestPushDevice treasurerPhone = new TestPushDevice();
        TestPushDevice plainPhone = new TestPushDevice();
        register(chairmanToken, chairmanPhone, "uk");
        register(electrician, electricianPhone, "uk");
        register(electrician, electricianLaptop, "en");
        register(submitter, submitterPhone, "uk");
        register(archivedApprover, archivedPhone, "uk");
        register(treasurer, treasurerPhone, "uk");
        register(plain, plainPhone, "uk");
        ownerJdbc().update("update member set archived_at = now() where id = ?", archivedId);

        assertThat(approvers()).containsExactlyInAnyOrder(chairmanId, electricianId, submitterId);

        UUID first = submit(submitter, plot, meter, "1100");
        submit(submitter, plot, meter, "1200");
        submit(submitter, plot, meter, "1300");

        List<SendPush> queued = queuedPushes();
        assertThat(queued).hasSize(3).allSatisfy(job -> {
            assertThat(job.code()).isEqualTo("READINGS_TO_APPROVE");
            assertThat(job.ref()).isEqualTo(first.toString());
        });
        assertThat(runQueuedPushes()).containsOnly("DELIVERED").hasSize(3);
        for (TestPushDevice device : List.of(chairmanPhone, electricianPhone)) {
            Received push = only(device);
            assertThat(device.decrypt(push.body()))
                    .isEqualTo(expected("READINGS_TO_APPROVE", "/readings/approvals", "uk"));
            assertThat(push.headers()).containsEntry("topic", "readings-approvals");
            assertThat(Long.parseLong(push.headers().get("ttl"))).isBetween(3_500L, 3_600L);
        }
        assertThat(electricianLaptop.decrypt(only(electricianLaptop).body()))
                .isEqualTo(expected("READINGS_TO_APPROVE", "/readings/approvals", "en"));
        for (TestPushDevice nobody : List.of(submitterPhone, archivedPhone, treasurerPhone, plainPhone)) {
            assertThat(pushService.received(nobody.id())).isEmpty();
        }

        // an hour later: the same value again changes nothing and tells nobody, a new value tells each approver again
        ownerJdbc().update("update push_throttle set last_sent_at = last_sent_at - interval '61 minutes' "
                + "where tenant_id = ?", tenant);
        submit(submitter, plot, meter, "1300");
        assertThat(queuedPushes()).isEmpty();
        UUID fourth = submit(submitter, plot, meter, "1400");
        assertThat(queuedPushes()).hasSize(3).allSatisfy(job -> assertThat(job.ref()).isEqualTo(fourth.toString()));
        assertThat(runQueuedPushes()).containsOnly("DELIVERED").hasSize(3);
        assertThat(pushService.received(chairmanPhone.id())).hasSize(2);
        assertThat(pushService.received(electricianPhone.id())).hasSize(2);

        // redelivered within the hour: the throttle refuses, nothing new
        assertThat(readingPushes.queue(new ReadingSubmitted(tenant, fourth, plot, submitterId))).isZero();
        assertThat(queuedPushes()).isEmpty();

        // the board's own entry waits for nobody's approval: no push, even after the hour
        ownerJdbc().update("update push_throttle set last_sent_at = last_sent_at - interval '61 minutes' "
                + "where tenant_id = ?", tenant);
        send(chairmanToken, post("/api/v1/readings"), Map.of("rows", List.of(Map.of("meterId", meter.toString(),
                "takenOn", today().toString(), "value", "1500")))).andExpect(status().isOk());
        assertThat(queuedPushes()).isEmpty();
    }

    @Test
    void twoSubmissionsAtOnceClaimTheThrottleOnce() throws Exception {
        String chairmanToken = chairman;
        TestPushDevice chairmanPhone = new TestPushDevice();
        register(chairmanToken, chairmanPhone, "uk");
        UUID submitterId = UUID.randomUUID();
        UUID plot = UUID.randomUUID();

        List<Integer> counts = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            threads.add(Thread.ofVirtual().start(() -> counts.add(readingPushes.queue(
                    new ReadingSubmitted(tenant, UUID.randomUUID(), plot, submitterId)))));
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(counts).hasSize(4).containsOnly(0, 1);
        assertThat(counts.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(queuedPushes()).hasSize(1);
    }

    // ---- «Нове оголошення від правління»

    @Test
    void everyRecipientOfAnAnnouncementPaperIncludedGetsOnePush() throws Exception {
        ownerJdbc().update("insert into delivery_settings (tenant_id, smtp_host, smtp_port, smtp_security, "
                + "smtp_from_address) values (?, 'smtp.push.test', 587, 'STARTTLS', 'board@push.test')", tenant);
        String anna = member("Анна");
        UUID annaId = lastPerson;
        ownerJdbc().update("update member set delivery_channel = 'EMAIL' where id = ?", annaId);
        String borys = member("Борис");
        UUID borysId = lastPerson;
        String archived = member("Архівний");
        UUID archivedId = lastPerson;
        TestPushDevice annaPhone = new TestPushDevice();
        TestPushDevice borysPhone = new TestPushDevice();
        TestPushDevice archivedPhone = new TestPushDevice();
        register(anna, annaPhone, "uk");
        register(borys, borysPhone, "en");
        register(archived, archivedPhone, "uk");
        ownerJdbc().update("update member set archived_at = now() where id = ?", archivedId);

        JsonNode sent = body(send(chairman, post("/api/v1/announcements?preview=false"), Map.of("text",
                "Збори в неділю", "streets", List.of(), "channels", List.of("EMAIL"))).andExpect(status().isOk()));
        UUID announcement = UUID.fromString(sent.get("announcementId").asString());

        Map<UUID, String> channels = new LinkedHashMap<>();
        ownerJdbc().queryForList("select member_id, channel from delivery where tenant_id = ? and announcement_id = ?",
                tenant, announcement).forEach(row -> channels.put((UUID) row.get("member_id"),
                        (String) row.get("channel")));
        assertThat(channels).containsEntry(annaId, "EMAIL").containsEntry(borysId, "PAPER")
                .doesNotContainKey(archivedId);

        assertThat(queuedPushes()).hasSize(2).allSatisfy(job -> {
            assertThat(job.code()).isEqualTo("ANNOUNCEMENT");
            assertThat(job.ref()).isEqualTo(announcement.toString());
        });
        assertThat(runQueuedPushes()).containsOnly("DELIVERED").hasSize(2);
        assertThat(annaPhone.decrypt(only(annaPhone).body())).isEqualTo(expected("ANNOUNCEMENT", "/account", "uk"));
        assertThat(borysPhone.decrypt(only(borysPhone).body())).isEqualTo(expected("ANNOUNCEMENT", "/account", "en"));
        assertThat(pushService.received(archivedPhone.id())).isEmpty();

        announcementPushes.queue(new AnnouncementSent(tenant, announcement));
        assertThat(queuedPushes()).isEmpty();
    }

    // ---- leaving the cooperative

    @Test
    void archivingAMemberOrDisablingTheirAccessRemovesEveryDevice() throws Exception {
        String anna = member("Анна");
        UUID annaId = lastPerson;
        String borys = member("Борис");
        UUID borysId = lastPerson;
        String chorna = member("Чорна");
        UUID chornaId = lastPerson;
        for (UUID member : List.of(annaId, borysId, chornaId)) {
            ownerJdbc().update("insert into member_access (member_id, tenant_id, state, activated_at) "
                    + "values (?, ?, 'ACTIVE', now())", member, tenant);
        }
        TestPushDevice annaPhone = new TestPushDevice();
        TestPushDevice annaLaptop = new TestPushDevice();
        TestPushDevice borysPhone = new TestPushDevice();
        TestPushDevice borysLaptop = new TestPushDevice();
        TestPushDevice chornaPhone = new TestPushDevice();
        register(anna, annaPhone, "uk");
        register(anna, annaLaptop, "uk");
        register(borys, borysPhone, "uk");
        register(borys, borysLaptop, "uk");
        UUID kept = register(chorna, chornaPhone, "uk");

        send(chairman, post("/api/v1/members/" + annaId + "/archive"), null).andExpect(status().isOk());
        send(chairman, post("/api/v1/members/" + borysId + "/access/disable"), null).andExpect(status().isOk());
        awaitListeners();

        assertThat(devices()).singleElement().satisfies(row -> assertThat(row.get("id")).isEqualTo(kept));

        // neither device receives anything further: an announcement reaches the member who stays only
        send(chairman, post("/api/v1/announcements?preview=false"), Map.of("text", "Збори", "streets", List.of(),
                "channels", List.of("EMAIL"))).andExpect(status().isOk());
        assertThat(runQueuedPushes()).containsExactly("DELIVERED");
        assertThat(pushService.received(chornaPhone.id())).hasSize(1);
        assertThat(pushService.received()).hasSize(1);

        // redelivered: nothing left to delete, the other member's device stays
        assertThat(revokedAccessDevices.remove(new MemberAccessRevoked(tenant, annaId))).isZero();
        assertThat(devices()).hasSize(1);
    }

    @Test
    void aJobQueuedBeforeTheMemberLeftSendsNothing() throws Exception {
        String anna = member("Анна");
        UUID annaId = lastPerson;
        UUID plot = plot("1", annaId);
        TestPushDevice phone = new TestPushDevice();
        register(anna, phone, "uk");
        readingPushes.queue(new ReadingDecided(tenant, UUID.randomUUID(), plot, annaId, ReadingStatus.APPROVED));

        assertThat(revokedAccessDevices.remove(new MemberAccessRevoked(tenant, annaId))).isEqualTo(1);

        assertThat(runQueuedPushes()).containsExactly("NO_DEVICE");
        assertThat(pushService.received(phone.id())).isEmpty();
    }

    // ---- logs

    @Test
    void theLogsCarryNoEndpointKeyOrName(CapturedOutput output) throws Exception {
        String anna = member("Анна");
        UUID annaId = lastPerson;
        UUID plot = plot("1", annaId);
        UUID meter = meter(plot, "ЕЛ-1");
        TestPushDevice annaPhone = new TestPushDevice();
        TestPushDevice chairmanPhone = new TestPushDevice();
        register(anna, annaPhone, "uk");
        register(chairman, chairmanPhone, "uk");

        pay(plot, annaId, "100.00");
        UUID reading = submit(anna, plot, meter, "1100");
        send(chairman, post("/api/v1/readings/" + reading + "/approve"), null).andExpect(status().isOk());
        assertThat(runQueuedPushes()).containsOnly("DELIVERED").hasSize(3);
        revokedAccessDevices.remove(new MemberAccessRevoked(tenant, annaId));

        String logged = output.getAll();
        assertThat(logged).contains("Push PAYMENT_RECORDED to a device of member " + annaId)
                .contains("Push READINGS_TO_APPROVE to a device of member")
                .contains("Push READING_APPROVED to a device of member " + annaId)
                .contains("Push devices of member " + annaId + " of cooperative " + tenant + " removed: 1");
        for (TestPushDevice device : List.of(annaPhone, chairmanPhone)) {
            assertThat(logged).doesNotContain(device.id()).doesNotContain(device.p256dh())
                    .doesNotContain(device.auth());
        }
        assertThat(logged).doesNotContain(pushService.origin()).doesNotContain("vapid t=").doesNotContain("Анна");
    }

    // ---- helpers

    private Received only(TestPushDevice device) {
        List<Received> received = pushService.received(device.id());
        assertThat(received).hasSize(1);
        return received.getFirst();
    }

    /** The member of the cooperative with the base role (the first one). */
    private UUID memberOf(String baseRole) {
        return ownerJdbc().queryForObject("select mr.member_id from member_role mr join role r on r.id = mr.role_id "
                + "where mr.tenant_id = ? and r.base_code = ? limit 1", UUID.class, tenant, baseRole);
    }

    /** A custom role with the permission for the member. */
    private void grant(UUID member, Permission permission) {
        UUID role = UUID.randomUUID();
        ownerJdbc().update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, tenant,
                "Роль " + role.toString().substring(0, 8));
        ownerJdbc().update("insert into role_permission (role_id, permission, tenant_id) values (?, ?, ?)", role,
                permission.name(), tenant);
        ownerJdbc().update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)", tenant, member,
                role);
    }

    /** The approvers as the members module answers them, in a transaction of the cooperative. */
    private List<UUID> approvers() {
        return TenantContext.runAs(tenant, () -> new TransactionTemplate(transactionManager)
                .execute(status -> roleHolders.activeWithPermission(Permission.READINGS_APPROVE)));
    }

    /** An active electricity meter of the plot installed on New Year's Day with 1000, registered by the chairman. */
    private UUID meter(UUID plot, String serial) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "PLOT");
        body.put("resource", "ELECTRICITY");
        body.put("plotId", plot.toString());
        body.put("serial", serial);
        body.put("installedOn", today().withDayOfYear(1).toString());
        body.put("initialReading", "1000");
        return UUID.fromString(body(send(chairman, post("/api/v1/meters"), body).andExpect(status().isCreated()))
                .get("id").asString());
    }

    /** The member submits a reading of the meter dated today; the pending reading of that day. */
    private UUID submit(String bearer, UUID plot, UUID meter, String value) throws Exception {
        send(bearer, post("/api/v1/plots/" + plot + "/readings"), Map.of("meterId", meter.toString(), "value", value))
                .andExpect(status().isCreated());
        return ownerJdbc().queryForObject("select id from meter_reading where meter_id = ? and status = 'PENDING' "
                + "and taken_on = ?", UUID.class, meter, today());
    }

    /** A cash payment for the plot received today, saved by the treasurer; the ids of its payments. */
    private List<UUID> pay(UUID plot, UUID payer, String amount) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plotId", plot.toString());
        body.put("source", "CASH");
        body.put("amount", amount);
        body.put("receivedOn", today().toString());
        if (payer != null) {
            body.put("payerMemberId", payer.toString());
        }
        List<UUID> ids = new ArrayList<>();
        body(send(treasurer, post("/api/v1/payments?preview=false"), body).andExpect(status().isCreated()))
                .get("payments").forEach(saved -> ids.add(UUID.fromString(saved.get("id").asString())));
        return ids;
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Europe/Kyiv"));
    }
}
