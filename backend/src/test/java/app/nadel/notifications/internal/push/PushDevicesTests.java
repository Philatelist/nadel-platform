package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import app.nadel.notifications.TestPushDevice;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.IndexKind;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * «Сповіщення на цьому пристрої» on the server (feature 021 §2.2): the push configuration; a member registers, lists
 * and deletes only their own devices; another member's or another cooperative's device is 404; the platform operator
 * 403; the endpoint and the keys are stored sealed, found by their blind index, and never in a response; the same
 * endpoint is one device and moves to the member who registers it; at most ten devices; bad input 400; nothing is
 * audited.
 */
class PushDevicesTests extends PushTestSupport {

    @Autowired
    PersonalDataCipher cipher;

    @Autowired
    TransactionTemplate tx;

    @Test
    void theConfigurationIsOnWithThePublicKey() throws Exception {
        String member = member("Конфіг");

        send(member, get("/api/v1/me/push"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.publicKey").value(PUSH_KEYS[0]));
    }

    @Test
    void aMemberRegistersListsAndDeletesTheirOwnDevicesWithoutEverSeeingTheEndpoint() throws Exception {
        String member = member("Власник");
        UUID memberId = lastPerson;
        TestPushDevice phone = new TestPushDevice();
        TestPushDevice laptop = new TestPushDevice();

        String saved = send(member, post(DEVICES), phone.registration("uk", "Телефон"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.label").value("Телефон"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.lastSentAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID laptopId = register(member, laptop, "en");
        String list = send(member, get(DEVICES), null).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(laptopId.toString()))
                .andReturn().getResponse().getContentAsString();

        for (String response : List.of(saved, list)) {
            for (TestPushDevice device : List.of(phone, laptop)) {
                assertThat(response).doesNotContain(device.endpoint()).doesNotContain(device.id())
                        .doesNotContain(device.p256dh()).doesNotContain(device.auth());
            }
            assertThat(response).doesNotContain("endpoint").doesNotContain("p256dh").doesNotContain("auth");
        }

        send(member, delete(DEVICES + "/" + laptopId), null).andExpect(status().isNoContent());
        send(member, delete(DEVICES + "/" + laptopId), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(member, get(DEVICES), null).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].label").value("Телефон"));
        assertThat(devices()).singleElement().satisfies(row -> assertThat(row.get("member_id")).isEqualTo(memberId));
        // turning push on or off is not audited
        assertThat(ownerJdbc().queryForObject("select count(*) from audit_entry where tenant_id = ? "
                + "and entity_id = ?", Integer.class, tenant, memberId)).isZero();
    }

    @Test
    void theEndpointAndTheKeysAreSealedAndFoundByTheirBlindIndex() throws Exception {
        String member = member("Сейф");
        TestPushDevice device = new TestPushDevice();
        UUID id = register(member, device, "uk");

        Map<String, Object> row = ownerJdbc().queryForMap("select endpoint_enc, p256dh_enc, auth_enc, endpoint_index, "
                + "server_key_id, label, language from push_subscription where id = ?", id);

        for (String column : List.of("endpoint_enc", "p256dh_enc", "auth_enc")) {
            String raw = new String((byte[]) row.get(column), StandardCharsets.ISO_8859_1);
            assertThat(raw).as(column).doesNotContain(device.id()).doesNotContain(device.p256dh())
                    .doesNotContain(device.auth()).doesNotContain("127.0.0.1");
        }
        Map<String, String> opened = TenantContext.runAs(tenant, () -> tx.execute(status -> {
            Map<String, String> values = new HashMap<>();
            for (String column : List.of("endpoint_enc", "p256dh_enc", "auth_enc")) {
                String place = "p256dh_enc".equals(column) ? "ua_public_enc" : column;
                values.put(column, cipher.decrypt(tenant, new FieldRef("push_subscription", place, id),
                        (byte[]) row.get(column)));
            }
            values.put("index", Base64.getEncoder().encodeToString(
                    cipher.blindIndex(tenant, IndexKind.PUSH_ENDPOINT, device.endpoint())));
            return values;
        }));
        assertThat(opened).containsEntry("endpoint_enc", device.endpoint())
                .containsEntry("p256dh_enc", device.p256dh()).containsEntry("auth_enc", device.auth());
        assertThat(Base64.getEncoder().encodeToString((byte[]) row.get("endpoint_index")))
                .isEqualTo(opened.get("index"));
        assertThat((byte[]) row.get("server_key_id")).isEqualTo(
                VapidKeys.keyId(Base64.getUrlDecoder().decode(PUSH_KEYS[0])));
        assertThat(row).containsEntry("label", "Телефон").containsEntry("language", "uk");
    }

    @Test
    void theSameEndpointIsOneDeviceAndMovesToTheMemberWhoRegistersIt() throws Exception {
        String first = member("Перший");
        UUID firstId = lastPerson;
        String second = member("Другий");
        UUID secondId = lastPerson;
        TestPushDevice shared = new TestPushDevice();

        UUID id = register(first, shared, "uk");
        assertThat(register(first, shared, "en")).isEqualTo(id);
        assertThat(devices()).singleElement().satisfies(row -> assertThat(row.get("member_id")).isEqualTo(firstId));

        assertThat(register(second, shared, "uk")).isEqualTo(id);

        assertThat(devices()).singleElement().satisfies(row -> assertThat(row.get("member_id")).isEqualTo(secondId));
        send(first, get(DEVICES), null).andExpect(jsonPath("$", hasSize(0)));
        send(second, get(DEVICES), null).andExpect(jsonPath("$[*].id", contains(id.toString())));
    }

    @Test
    void aMemberKeepsTheTenNewestDevices() throws Exception {
        String member = member("Багато");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            ids.add(register(member, new TestPushDevice(), "uk"));
        }

        send(member, get(DEVICES), null).andExpect(jsonPath("$", hasSize(10)));
        assertThat(devices()).extracting(row -> (UUID) row.get("id")).hasSize(10).doesNotContain(ids.getFirst())
                .containsAll(ids.subList(1, 11));
    }

    @Test
    void anotherMembersAndAnotherCooperativesDevicesAreNotFound() throws Exception {
        String owner = member("Свій");
        String stranger = member("Чужий");
        UUID id = register(owner, new TestPushDevice(), "uk");
        String otherCooperative = TestTokens.bearer(OTHER_TENANT, "sub-member@other.local", "member@other.local");

        send(stranger, delete(DEVICES + "/" + id), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(stranger, get(DEVICES), null).andExpect(jsonPath("$", hasSize(0)));
        send(otherCooperative, delete(DEVICES + "/" + id), null).andExpect(status().isNotFound());
        send(otherCooperative, get(DEVICES), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem(id.toString()))));
        send(owner, delete(DEVICES + "/" + UUID.randomUUID()), null).andExpect(status().isNotFound());

        assertThat(devices()).hasSize(1);
    }

    @Test
    void thePlatformOperatorIsRefused() throws Exception {
        String operator = TestTokens.token().subject("operator").email("operator@nadel.local")
                .realmRoles("PLATFORM_OPERATOR").bearer();

        send(operator, get("/api/v1/me/push"), null).andExpect(status().isForbidden());
        send(operator, get(DEVICES), null).andExpect(status().isForbidden());
        send(operator, post(DEVICES), new TestPushDevice().registration("uk", null)).andExpect(status().isForbidden());
        send(operator, delete(DEVICES + "/" + UUID.randomUUID()), null).andExpect(status().isForbidden());
    }

    @Test
    void badInputIsRefusedWithTheField() throws Exception {
        String member = member("Помилка");
        TestPushDevice device = new TestPushDevice();

        expectInvalid(member, with(device, "endpoint", "https://push.evil.example/push/abc"), "endpoint");
        expectInvalid(member, with(device, "endpoint", "http://fcm.googleapis.com/fcm/send/abc"), "endpoint");
        expectInvalid(member, with(device, "endpoint", "https://user@fcm.googleapis.com/fcm/send/abc"), "endpoint");
        byte[] offCurve = Base64.getUrlDecoder().decode(device.p256dh());
        offCurve[64] ^= 1;
        expectInvalid(member, keys(device, Base64.getUrlEncoder().withoutPadding().encodeToString(offCurve),
                device.auth()), "keys.p256dh");
        expectInvalid(member, keys(device, device.p256dh(), "A".repeat(23)), "keys.auth"); // 17 bytes
        expectInvalid(member, keys(device, device.p256dh(), "AAAA AAAAAAAAAAAAAAAAA"), "keys.auth");

        Map<String, Object> german = device.registration("de", null);
        send(member, post(DEVICES), german).andExpect(status().isBadRequest());
        Map<String, Object> longLabel = device.registration("uk", "x".repeat(81));
        send(member, post(DEVICES), longLabel).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        Map<String, Object> longEndpoint = with(device, "endpoint", device.endpoint() + "x".repeat(2048));
        send(member, post(DEVICES), longEndpoint).andExpect(status().isBadRequest());

        assertThat(devices()).isEmpty();
    }

    private void expectInvalid(String bearer, Map<String, Object> body, String field) throws Exception {
        send(bearer, post(DEVICES), body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)));
    }

    private static Map<String, Object> with(TestPushDevice device, String key, Object value) {
        Map<String, Object> body = device.registration("uk", null);
        body.put(key, value);
        return body;
    }

    private static Map<String, Object> keys(TestPushDevice device, String p256dh, String auth) {
        return with(device, "keys", Map.of("p256dh", p256dh, "auth", auth));
    }
}
