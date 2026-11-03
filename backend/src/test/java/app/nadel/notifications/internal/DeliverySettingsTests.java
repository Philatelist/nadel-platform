package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.privacy.FieldRef;
import app.nadel.privacy.PersonalDataCipher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

/**
 * The delivery settings (feature 017 §2.1): defaults, the round trip, write-only secrets that an empty value keeps, the
 * last four characters of the token, the settings version, the audit entry that names a changed secret without its
 * value, and sealed columns without plaintext.
 */
class DeliverySettingsTests extends DeliverySettingsTestSupport {

    @Autowired
    PersonalDataCipher cipher;

    private final String password = "Pa55-" + UUID.randomUUID();
    private final String token = "123456789:AAF" + UUID.randomUUID().toString().replace("-", "") + "x7Qz";

    @Test
    void aCooperativeThatNeverSavedThemHasNoMailboxAndNoBot() throws Exception {
        read(chairman()).andExpect(status().isOk())
                .andExpect(jsonPath("$.email.configured").value(false))
                .andExpect(jsonPath("$.email.passwordSet").value(false))
                .andExpect(jsonPath("$.email.host").doesNotExist())
                .andExpect(jsonPath("$.telegram.configured").value(false))
                .andExpect(jsonPath("$.telegram.tokenLast4").doesNotExist())
                .andExpect(jsonPath("$.emailRatePerSecond").value(1))
                .andExpect(jsonPath("$.notifyPayments").value(true));
        assertThat(rowCount()).isZero();
    }

    @Test
    void savedSettingsComeBackWithoutTheirSecrets() throws Exception {
        Map<String, Object> email = mailbox("smtp.coop.example", 587, "board@coop.example", password);
        email.put("security", "STARTTLS");
        String saved = save(chairman(), Map.of("email", email, "telegram", Map.of("botToken", token),
                "emailRatePerSecond", 3, "notifyPayments", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email.passwordSet").value(true))
                .andReturn().getResponse().getContentAsString();

        // reopening the page: the password is "saved", the token shows its last four characters, never the value
        String reread = read(chairman()).andExpect(status().isOk())
                .andExpect(jsonPath("$.email.host").value("smtp.coop.example"))
                .andExpect(jsonPath("$.email.port").value(587))
                .andExpect(jsonPath("$.email.security").value("STARTTLS"))
                .andExpect(jsonPath("$.email.user").value("board@coop.example"))
                .andExpect(jsonPath("$.email.fromAddress").value("board@coop.example"))
                .andExpect(jsonPath("$.email.fromName").value("СТ «Надія»"))
                .andExpect(jsonPath("$.email.passwordSet").value(true))
                .andExpect(jsonPath("$.email.configured").value(true))
                .andExpect(jsonPath("$.telegram.configured").value(true))
                .andExpect(jsonPath("$.telegram.tokenLast4").value("x7Qz"))
                .andExpect(jsonPath("$.telegram.botUsername").doesNotExist())
                .andExpect(jsonPath("$.emailRatePerSecond").value(3))
                .andExpect(jsonPath("$.notifyPayments").value(false))
                .andReturn().getResponse().getContentAsString();
        for (String body : List.of(saved, reread)) {
            assertThat(body).doesNotContain(password).doesNotContain(token)
                    .doesNotContain("\"password\"").doesNotContain("botToken");
        }
    }

    @Test
    void anEmptySecretKeepsTheSavedOne() throws Exception {
        save(chairman(), Map.of("email", mailbox("smtp.coop.example", 465, "board@coop.example", password),
                "telegram", Map.of("botToken", token))).andExpect(status().isOk());
        byte[] passwordBefore = sealed("smtp_password_enc");
        byte[] tokenBefore = sealed("bot_token_enc");

        // the forms send the empty secret fields back, or leave them out
        save(chairman(), Map.of("email", mailbox("smtp2.coop.example", 465, "board@coop.example", ""),
                "telegram", Map.of("botToken", ""))).andExpect(status().isOk())
                .andExpect(jsonPath("$.email.host").value("smtp2.coop.example"))
                .andExpect(jsonPath("$.email.passwordSet").value(true))
                .andExpect(jsonPath("$.telegram.tokenLast4").value("x7Qz"));
        save(chairman(), Map.of("email", mailbox("smtp2.coop.example", 465, "board@coop.example", null),
                "telegram", Map.of())).andExpect(status().isOk())
                .andExpect(jsonPath("$.email.passwordSet").value(true));

        assertThat(sealed("smtp_password_enc")).isEqualTo(passwordBefore);
        assertThat(sealed("bot_token_enc")).isEqualTo(tokenBefore);
        assertThat(opened("smtp_password_enc")).isEqualTo(password);
        assertThat(opened("bot_token_enc")).isEqualTo(token);
    }

    @Test
    void clearingTheUserRemovesThePassword() throws Exception {
        save(chairman(), Map.of("email", mailbox("smtp.coop.example", 587, "board@coop.example", password)))
                .andExpect(jsonPath("$.email.passwordSet").value(true));

        save(chairman(), Map.of("email", mailbox("localhost", 1025, "", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email.user").doesNotExist())
                .andExpect(jsonPath("$.email.passwordSet").value(false))
                .andExpect(jsonPath("$.email.configured").value(true));
        assertThat(sealed("smtp_password_enc")).isNull();
    }

    @Test
    void onlyANewTokenRaisesTheSettingsVersion() throws Exception {
        save(chairman(), Map.of("notifyPayments", false)).andExpect(status().isOk());
        assertThat(version()).isEqualTo(1);

        save(chairman(), Map.of("telegram", Map.of("botToken", token))).andExpect(status().isOk());
        assertThat(version()).isEqualTo(2);
        ownerJdbc().update("update delivery_settings set bot_username = 'old_bot' where tenant_id = ?", DEMO_TENANT);

        // the same token again and a mailbox change keep the version (and the bot's @name)
        save(chairman(), Map.of("telegram", Map.of("botToken", token),
                "email", mailbox("smtp.coop.example", 587, "u", null))).andExpect(status().isOk())
                .andExpect(jsonPath("$.telegram.botUsername").value("old_bot"));
        assertThat(version()).isEqualTo(2);

        // a new token: the next version, its own last four characters, the old bot's @name forgotten
        save(chairman(), Map.of("telegram", Map.of("botToken", "987654321:BBnewtoken9876"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.telegram.tokenLast4").value("9876"))
                .andExpect(jsonPath("$.telegram.botUsername").doesNotExist());
        assertThat(version()).isEqualTo(3);
    }

    @Test
    void theAuditNamesAChangedSecretWithoutItsValue() throws Exception {
        int before = auditEntries(DEMO_TENANT).size();
        save(chairman(), Map.of("email", mailbox("smtp.coop.example", 587, "board@coop.example", password),
                "telegram", Map.of("botToken", token), "emailRatePerSecond", 2)).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(auditEntries(DEMO_TENANT)).hasSize(before + 1));

        Map<String, Object> entry = auditEntries(DEMO_TENANT).getLast();
        assertThat(entry.get("action")).isEqualTo("DELIVERY_SETTINGS_CHANGED");
        assertThat(entry.get("entity_label")).isEqualTo("delivery-settings");
        String stored = (String) entry.get("changes");
        assertThat(stored).doesNotContain(password).doesNotContain(token);
        JsonNode changes = json.readTree(stored);
        List<String> fields = new ArrayList<>();
        changes.forEach(change -> fields.add(change.get("field").asString()));
        assertThat(fields).containsExactly("host", "port", "user", "password", "fromAddress", "fromName", "botToken",
                "emailRatePerSecond");
        for (JsonNode change : changes) {
            if (List.of("password", "botToken").contains(change.get("field").asString())) {
                assertThat(change.get("old").isNull()).isTrue();
                assertThat(change.get("new").isNull()).isTrue();
            }
        }
        assertThat(changes.get(7).get("old").asString()).isEqualTo("1");
        assertThat(changes.get(7).get("new").asString()).isEqualTo("2");

        // the change history shows that the password changed and nothing else about it
        String history = mvc.perform(get("/api/v1/audit").param("entityType", "DELIVERY_SETTINGS")
                        .header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("DELIVERY_SETTINGS_CHANGED"))
                .andExpect(jsonPath("$.items[0].changes[3].field").value("password"))
                .andExpect(jsonPath("$.items[0].changes[3].oldValue").doesNotExist())
                .andExpect(jsonPath("$.items[0].changes[3].newValue").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(history).doesNotContain(password).doesNotContain(token);

        // saving the same values (the same password and token included) records nothing
        save(chairman(), Map.of("email", mailbox("smtp.coop.example", 587, "board@coop.example", password),
                "telegram", Map.of("botToken", token), "emailRatePerSecond", 2)).andExpect(status().isOk());
        Thread.sleep(500); // an event, if one were published, would be written within this time
        assertThat(auditEntries(DEMO_TENANT)).hasSize(before + 1);
    }

    @Test
    void theSealedColumnsHoldNoPlaintext() throws Exception {
        save(chairman(), Map.of("email", mailbox("smtp.coop.example", 587, "board@coop.example", password),
                "telegram", Map.of("botToken", token))).andExpect(status().isOk());

        Map<String, Object> row = ownerJdbc().queryForMap("select smtp_password_enc, bot_token_enc, bot_token_last4 "
                + "from delivery_settings where tenant_id = ?", DEMO_TENANT);
        for (String column : List.of("smtp_password_enc", "bot_token_enc")) {
            String hex = HexFormat.of().formatHex((byte[]) row.get(column));
            assertThat(hex).doesNotContain(HexFormat.of().formatHex(password.getBytes(StandardCharsets.UTF_8)))
                    .doesNotContain(HexFormat.of().formatHex(token.getBytes(StandardCharsets.UTF_8)))
                    .doesNotContain(HexFormat.of().formatHex(token.substring(0, 9).getBytes(StandardCharsets.UTF_8)));
        }
        assertThat(row.get("bot_token_last4")).isEqualTo("x7Qz");
        // and they open at their own place only: the password does not open as the token
        assertThat(opened("smtp_password_enc")).isEqualTo(password);
        assertThat(opened("bot_token_enc")).isEqualTo(token);
        assertThat(ownerJdbc().queryForObject("select count(*) from event_publication where cast(serialized_event as "
                + "text) like ? or cast(serialized_event as text) like ?", Integer.class, "%" + password + "%",
                "%" + token + "%")).isZero();
    }

    @Test
    void valuesOutsideTheirLimitsAreRefused() throws Exception {
        save(chairman(), Map.of("emailRatePerSecond", 11)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        save(chairman(), Map.of("emailRatePerSecond", 0)).andExpect(status().isBadRequest());
        save(chairman(), Map.of("telegram", Map.of("botToken", "short"))).andExpect(status().isBadRequest());
        save(chairman(), Map.of("telegram", Map.of("botToken", "123456:AA/../getMe"))).andExpect(status().isBadRequest());
        save(chairman(), Map.of("email", Map.of("port", 70000))).andExpect(status().isBadRequest());
        save(chairman(), Map.of("email", Map.of("fromAddress", "not an address"))).andExpect(status().isBadRequest());
        save(chairman(), Map.of("email", Map.of("security", "TLS13"))).andExpect(status().isBadRequest());
        assertThat(rowCount()).isZero();
    }

    private int rowCount() {
        return ownerJdbc().queryForObject("select count(*) from delivery_settings where tenant_id = ?", Integer.class,
                DEMO_TENANT);
    }

    private int version() {
        return ownerJdbc().queryForObject("select settings_version from delivery_settings where tenant_id = ?",
                Integer.class, DEMO_TENANT);
    }

    private byte[] sealed(String column) {
        return ownerJdbc().queryForObject("select " + column + " from delivery_settings where tenant_id = ?",
                byte[].class, DEMO_TENANT);
    }

    private String opened(String column) {
        byte[] value = sealed(column);
        return inTenant(DEMO_TENANT, sql -> cipher.decrypt(DEMO_TENANT,
                new FieldRef("delivery_settings", column, DEMO_TENANT), value));
    }
}
