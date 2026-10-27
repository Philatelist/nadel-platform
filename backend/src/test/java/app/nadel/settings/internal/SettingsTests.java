package app.nadel.settings.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import app.nadel.money.Money;
import app.nadel.settings.CommonConsumerSplit;
import app.nadel.settings.CooperativeSettings;
import app.nadel.settings.LossesMode;
import app.nadel.settings.PlotStatusVisibility;
import app.nadel.settings.Requisites;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cooperative settings: defaults, permissions, validation, persistence and audit. */
class SettingsTests extends IntegrationTest {

    private static final String SETTINGS = "/api/v1/settings";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    CooperativeSettings cooperativeSettings;

    private final List<UUID> roles = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID role : roles) {
            inTenant(DEMO_TENANT, sql -> {
                sql.update("delete from member_role where role_id = ?", role);
                sql.update("delete from role_permission where role_id = ?", role);
                return sql.update("delete from role where id = ?", role);
            });
        }
        roles.clear();
        // a first sign-in links the demo members to a subject; other tests expect them unlinked (their own subject)
        unlinkByEmail(DEMO_TENANT, "chairman@demo.local", "treasurer@demo.local", "member@demo.local",
                "electrician@demo.local");
        unlinkByEmail(OTHER_TENANT, "member@other.local");
        // the demo cooperative goes back to "never saved", the default other tests rely on
        ownerJdbc().update("delete from cooperative_settings where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
        ownerJdbc().update("update tenant set edrpou = null where id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
    }

    // ---- defaults

    @Test
    void anExistingCooperativeWithoutARowIsOwnOnly() throws Exception {
        assertThat(rowCount(DEMO_TENANT)).isZero();

        read(demo("chairman@demo.local")).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberPlotStatusVisibility").value("OWN_ONLY"));
        assertThat(TenantContext.runAs(DEMO_TENANT, cooperativeSettings::memberPlotStatusVisibility))
                .isEqualTo(PlotStatusVisibility.OWN_ONLY);
    }

    @Test
    void aNewCooperativeWithoutARowIsOwnOnly() throws Exception {
        JdbcTemplate owner = ownerJdbc();
        UUID tenant = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        String email = "settings-new-" + tenant + "@example.com";
        owner.update("insert into tenant (id, name) values (?, 'Settings Test Cooperative')", tenant);
        try {
            owner.queryForList("select seed_base_roles(?)", tenant);
            owner.update("insert into member (id, tenant_id, last_name, first_name) values (?, ?, 'Test', 'New')",
                    member, tenant);
            insertContacts(tenant, member, email);
            owner.update("insert into member_role (tenant_id, member_id, role_id) "
                    + "select ?, ?, id from role where tenant_id = ? and base_code = 'CHAIRMAN'", tenant, member, tenant);

            read(TestTokens.bearer(tenant, UUID.randomUUID().toString(), email)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberPlotStatusVisibility").value("OWN_ONLY"));
            assertThat(TenantContext.runAs(tenant, cooperativeSettings::memberPlotStatusVisibility))
                    .isEqualTo(PlotStatusVisibility.OWN_ONLY);
            assertThat(owner.queryForObject("select count(*) from cooperative_settings where tenant_id = ?",
                    Integer.class, tenant)).isZero();
        } finally {
            owner.update("delete from member_role where tenant_id = ?", tenant);
            owner.update("delete from member where tenant_id = ?", tenant);
            owner.update("delete from charge_type where tenant_id = ?", tenant);
            owner.update("delete from role_permission where tenant_id = ?", tenant);
            owner.update("delete from role where tenant_id = ?", tenant);
            owner.update("delete from cooperative_settings where tenant_id = ?", tenant);
            deleteDataKeys(tenant);
            owner.update("delete from tenant where id = ?", tenant);
        }
    }

    // ---- read

    @Test
    void everyMemberOfTheCooperativeMayReadTheSettings() throws Exception {
        for (String email : List.of("member@demo.local", "treasurer@demo.local", "chairman@demo.local",
                "electrician@demo.local")) {
            read(demo(email)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberPlotStatusVisibility").value("OWN_ONLY"));
        }
    }

    @Test
    void readingNeedsASignedInUser() throws Exception {
        mvc.perform(get(SETTINGS))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void thePlatformOperatorCannotReadOrChangeTheSettings() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        read(operator).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        save(operator, Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(rowCount(DEMO_TENANT)).isZero();
    }

    // ---- write

    @Test
    void theChairmanChangesTheSettingAndItIsPersisted() throws Exception {
        String chairman = demo("chairman@demo.local");

        save(chairman, Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberPlotStatusVisibility").value("ALL_PLOTS"));

        assertThat(storedValue(DEMO_TENANT)).isEqualTo("ALL_PLOTS");
        read(demo("member@demo.local")).andExpect(jsonPath("$.memberPlotStatusVisibility").value("ALL_PLOTS"));
        assertThat(TenantContext.runAs(DEMO_TENANT, cooperativeSettings::memberPlotStatusVisibility))
                .isEqualTo(PlotStatusVisibility.ALL_PLOTS);

        save(chairman, Map.of("memberPlotStatusVisibility", "OWN_ONLY")).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberPlotStatusVisibility").value("OWN_ONLY"));
        assertThat(storedValue(DEMO_TENANT)).isEqualTo("OWN_ONLY");
        assertThat(rowCount(DEMO_TENANT)).isEqualTo(1);
    }

    @Test
    void aCustomRoleWithSettingsManageMayChangeTheSetting() throws Exception {
        String manager = customRoleToken("SETTINGS_MANAGE");

        save(manager, Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberPlotStatusVisibility").value("ALL_PLOTS"));
        assertThat(storedValue(DEMO_TENANT)).isEqualTo("ALL_PLOTS");
    }

    @Test
    void aCustomRoleWithoutSettingsManageMayNot() throws Exception {
        String other = customRoleToken("PLOTS_EDIT");

        save(other, Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(rowCount(DEMO_TENANT)).isZero();
    }

    @Test
    void theTreasurerAndAMemberCannotChangeTheSetting() throws Exception {
        for (String email : List.of("treasurer@demo.local", "member@demo.local", "electrician@demo.local")) {
            save(demo(email), Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        assertThat(rowCount(DEMO_TENANT)).isZero();
    }

    @Test
    void anInvalidValueIsRejected() throws Exception {
        String chairman = demo("chairman@demo.local");

        save(chairman, Map.of("memberPlotStatusVisibility", "EVERYONE")).andExpect(status().isBadRequest());
        save(chairman, "not json").andExpect(status().isBadRequest());
        assertThat(rowCount(DEMO_TENANT)).isZero();
    }

    @Test
    void anEmptyChangeChangesNothing() throws Exception {
        save(demo("chairman@demo.local"), Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberPlotStatusVisibility").value("OWN_ONLY"))
                .andExpect(jsonPath("$.redAmount").value("1000.00"));
        assertThat(rowCount(DEMO_TENANT)).isZero();
    }

    // ---- the ledger settings (feature 011): red threshold, losses, common consumers

    @Test
    void theLedgerSettingsHaveTheirDefaultsWithoutARow() throws Exception {
        read(demo("member@demo.local")).andExpect(status().isOk())
                .andExpect(jsonPath("$.lossesMode").value("FIXED_PERCENT"))
                .andExpect(jsonPath("$.lossesPercent").value("0.00"))
                .andExpect(jsonPath("$.commonConsumerDefaultSplit").value("CONNECTED_PLOTS"))
                .andExpect(jsonPath("$.redAmount").value("1000.00"))
                .andExpect(jsonPath("$.redDays").value(90));
        TenantContext.runAs(DEMO_TENANT, () -> {
            assertThat(cooperativeSettings.lossesMode()).isEqualTo(LossesMode.FIXED_PERCENT);
            assertThat(cooperativeSettings.lossesPercent()).isEqualByComparingTo("0");
            assertThat(cooperativeSettings.commonConsumerDefaultSplit()).isEqualTo(CommonConsumerSplit.CONNECTED_PLOTS);
            assertThat(cooperativeSettings.redAmount()).isEqualTo(Money.parse("1000.00"));
            assertThat(cooperativeSettings.redDays()).isEqualTo(90);
            return null;
        });
    }

    @Test
    void theChairmanChangesTheRedThresholdAndTheOtherSettingsKeepTheirValues() throws Exception {
        String chairman = demo("chairman@demo.local");
        save(chairman, Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isOk());

        // §2.10: the chairman changes the red amount to 2 000,00 ₴
        save(chairman, Map.of("redAmount", "2000.00", "redDays", 60)).andExpect(status().isOk())
                .andExpect(jsonPath("$.redAmount").value("2000.00"))
                .andExpect(jsonPath("$.redDays").value(60))
                .andExpect(jsonPath("$.memberPlotStatusVisibility").value("ALL_PLOTS"))
                .andExpect(jsonPath("$.lossesMode").value("FIXED_PERCENT"));
        save(chairman, Map.of("lossesMode", "PROPORTIONAL", "lossesPercent", "5",
                "commonConsumerDefaultSplit", "EQUAL_PER_PLOT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.lossesPercent").value("5.00"))
                .andExpect(jsonPath("$.redAmount").value("2000.00"));

        read(demo("member@demo.local")).andExpect(jsonPath("$.redAmount").value("2000.00"))
                .andExpect(jsonPath("$.redDays").value(60))
                .andExpect(jsonPath("$.lossesMode").value("PROPORTIONAL"))
                .andExpect(jsonPath("$.lossesPercent").value("5.00"))
                .andExpect(jsonPath("$.commonConsumerDefaultSplit").value("EQUAL_PER_PLOT"))
                .andExpect(jsonPath("$.memberPlotStatusVisibility").value("ALL_PLOTS"));
        assertThat(ownerJdbc().queryForMap("select red_amount, red_days, losses_mode, losses_percent, "
                + "common_consumer_default_split from cooperative_settings where tenant_id = ?", DEMO_TENANT))
                .containsEntry("red_amount", new BigDecimal("2000.00"))
                .containsEntry("red_days", 60)
                .containsEntry("losses_mode", "PROPORTIONAL")
                .containsEntry("losses_percent", new BigDecimal("5.00"))
                .containsEntry("common_consumer_default_split", "EQUAL_PER_PLOT");
        TenantContext.runAs(DEMO_TENANT, () -> {
            assertThat(cooperativeSettings.redAmount()).isEqualTo(Money.parse("2000.00"));
            assertThat(cooperativeSettings.redDays()).isEqualTo(60);
            return null;
        });
    }

    @Test
    void aChangeOfTheRedThresholdIsAuditedWithEveryChangedField() throws Exception {
        long before = auditCount();

        save(demo("chairman@demo.local"), Map.of("redAmount", "2000.00", "redDays", 90, "lossesPercent", "0.00"))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(auditCount()).isEqualTo(before + 1));

        Map<String, Object> entry = auditEntries().getLast();
        assertThat(entry.get("action")).isEqualTo("SETTINGS_UPDATED");
        JsonNode changes = json.readTree((String) entry.get("changes"));
        // redDays and lossesPercent keep their values: only the red amount is a change
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).get("field").asString()).isEqualTo("redAmount");
        assertThat(changes.get(0).get("old").asString()).isEqualTo("1000.00");
        assertThat(changes.get(0).get("new").asString()).isEqualTo("2000.00");
    }

    @Test
    void invalidLedgerSettingsAreRejected() throws Exception {
        String chairman = demo("chairman@demo.local");

        save(chairman, Map.of("redAmount", "-1.00")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("redAmount"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        save(chairman, Map.of("redAmount", "1000000000000.00")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("redAmount"));
        save(chairman, Map.of("redAmount", "1000")).andExpect(status().isBadRequest());
        save(chairman, Map.of("lossesPercent", "100.01")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lossesPercent"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        save(chairman, Map.of("lossesPercent", "5.001")).andExpect(status().isBadRequest());
        save(chairman, Map.of("redDays", -1)).andExpect(status().isBadRequest());
        save(chairman, Map.of("lossesMode", "SOMETIMES")).andExpect(status().isBadRequest());
        save(chairman, Map.of("commonConsumerDefaultSplit", "BY_AREA")).andExpect(status().isBadRequest());
        assertThat(rowCount(DEMO_TENANT)).isZero();

        save(chairman, Map.of("lossesPercent", "100.00", "redAmount", "0.00", "redDays", 0))
                .andExpect(status().isOk());
    }

    @Test
    void onlySettingsManageChangesTheLedgerSettings() throws Exception {
        for (String email : List.of("treasurer@demo.local", "member@demo.local")) {
            save(demo(email), Map.of("redAmount", "2000.00")).andExpect(status().isForbidden());
        }
        save(customRoleToken("SETTINGS_MANAGE"), Map.of("redDays", 30)).andExpect(status().isOk());
        assertThat(ownerJdbc().queryForObject("select red_days from cooperative_settings where tenant_id = ?",
                Integer.class, DEMO_TENANT)).isEqualTo(30);
    }

    @Test
    void theCooperativeComesFromTheTokenNotFromTheBody() throws Exception {
        save(demo("chairman@demo.local"), Map.of("memberPlotStatusVisibility", "ALL_PLOTS",
                "tenantId", OTHER_TENANT.toString())).andExpect(status().isOk());

        assertThat(rowCount(DEMO_TENANT)).isEqualTo(1);
        assertThat(rowCount(OTHER_TENANT)).isZero();
    }

    // ---- audit

    @Test
    void aChangeIsAuditedWithTheOldAndTheNewValue() throws Exception {
        long before = auditCount();
        String chairman = demo("chairman@demo.local");

        save(chairman, Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(auditCount()).isEqualTo(before + 1));
        save(chairman, Map.of("memberPlotStatusVisibility", "OWN_ONLY")).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(auditCount()).isEqualTo(before + 2));

        List<Map<String, Object>> entries = auditEntries();
        Map<String, Object> first = entries.get(entries.size() - 2);
        Map<String, Object> second = entries.get(entries.size() - 1);
        assertThat(first.get("action")).isEqualTo("SETTINGS_UPDATED");
        assertThat(first.get("entity_label")).isEqualTo("settings");
        assertThat(first.get("actor_member_id")).isNotNull();
        JsonNode firstChange = json.readTree((String) first.get("changes"));
        assertThat(firstChange).hasSize(1);
        assertThat(firstChange.get(0).get("field").asString()).isEqualTo("memberPlotStatusVisibility");
        assertThat(firstChange.get(0).get("old").asString()).isEqualTo("OWN_ONLY");
        assertThat(firstChange.get(0).get("new").asString()).isEqualTo("ALL_PLOTS");
        JsonNode secondChange = json.readTree((String) second.get("changes"));
        assertThat(secondChange.get(0).get("old").asString()).isEqualTo("ALL_PLOTS");
        assertThat(secondChange.get(0).get("new").asString()).isEqualTo("OWN_ONLY");
    }

    @Test
    void savingTheCurrentValueWritesNoAuditEntryAndNoRow() throws Exception {
        long before = auditCount();
        String chairman = demo("chairman@demo.local");

        // the default, saved explicitly
        save(chairman, Map.of("memberPlotStatusVisibility", "OWN_ONLY")).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberPlotStatusVisibility").value("OWN_ONLY"));
        assertThat(rowCount(DEMO_TENANT)).isZero();

        // a stored value, saved again
        save(chairman, Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(auditCount()).isEqualTo(before + 1));
        save(chairman, Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isOk());

        Thread.sleep(500); // an event, if one were published, would be written within this time
        assertThat(auditCount()).isEqualTo(before + 1);
    }

    // ---- requisites (feature 013, functional spec 2.13)

    private static final String VALID_IBAN = "UA213223130000026007233566001";

    @Test
    void theChairmanSavesValidRequisitesAndTheAuditShowsTheChangedFields() throws Exception {
        long before = auditCount();
        String chairman = demo("chairman@demo.local");

        save(chairman, Map.of("officialName", "  Garden cooperative Nadiia  ", "legalAddress", "1 Garden St, Kyiv",
                "bankName", "Test Bank", "iban", "ua21 3223 1300 0002 6007 2335 6600 1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.officialName").value("Garden cooperative Nadiia"))
                .andExpect(jsonPath("$.legalAddress").value("1 Garden St, Kyiv"))
                .andExpect(jsonPath("$.bankName").value("Test Bank"))
                .andExpect(jsonPath("$.iban").value(VALID_IBAN))
                .andExpect(jsonPath("$.memberPlotStatusVisibility").value("OWN_ONLY"));

        assertThat(ownerJdbc().queryForMap("select official_name, legal_address, bank_name, iban "
                + "from cooperative_settings where tenant_id = ?", DEMO_TENANT))
                .containsEntry("official_name", "Garden cooperative Nadiia")
                .containsEntry("legal_address", "1 Garden St, Kyiv")
                .containsEntry("bank_name", "Test Bank")
                .containsEntry("iban", VALID_IBAN);
        read(demo("member@demo.local")).andExpect(jsonPath("$.iban").value(VALID_IBAN))
                .andExpect(jsonPath("$.officialName").value("Garden cooperative Nadiia"));
        assertThat(TenantContext.runAs(DEMO_TENANT, cooperativeSettings::requisites)).isEqualTo(new Requisites(
                "Garden cooperative Nadiia", "1 Garden St, Kyiv", "Test Bank", VALID_IBAN));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(auditCount()).isEqualTo(before + 1));
        Map<String, Object> entry = auditEntries().getLast();
        assertThat(entry.get("action")).isEqualTo("SETTINGS_UPDATED");
        JsonNode changes = json.readTree((String) entry.get("changes"));
        assertThat(changes).hasSize(4);
        List<String> fields = new ArrayList<>();
        changes.forEach(change -> fields.add(change.get("field").asString()));
        assertThat(fields).containsExactly("officialName", "legalAddress", "bankName", "iban");
        assertThat(changes.get(3).get("old").isNull()).isTrue();
        assertThat(changes.get(3).get("new").asString()).isEqualTo(VALID_IBAN);

        // an empty string clears one requisite; the others and the plain settings keep their values
        save(chairman, Map.of("bankName", "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.bankName").doesNotExist())
                .andExpect(jsonPath("$.iban").value(VALID_IBAN));
        assertThat(ownerJdbc().queryForMap("select official_name, bank_name, iban from cooperative_settings "
                + "where tenant_id = ?", DEMO_TENANT))
                .containsEntry("official_name", "Garden cooperative Nadiia")
                .containsEntry("bank_name", null)
                .containsEntry("iban", VALID_IBAN);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(auditCount()).isEqualTo(before + 2));
        JsonNode cleared = json.readTree((String) auditEntries().getLast().get("changes"));
        assertThat(cleared).hasSize(1);
        assertThat(cleared.get(0).get("field").asString()).isEqualTo("bankName");
        assertThat(cleared.get(0).get("old").asString()).isEqualTo("Test Bank");
        assertThat(cleared.get(0).get("new").isNull()).isTrue();
    }

    @Test
    void anInvalidIbanIsRejectedAndNothingIsSaved() throws Exception {
        long before = auditCount();
        String chairman = demo("chairman@demo.local");

        for (String iban : List.of("UA223223130000026007233566001", "UA21322313000002600723356600",
                "DE89370400440532013000", "UA21-3223-1300-0002-6007-2335-6600-1", "not an iban")) {
            save(chairman, Map.of("officialName", "Garden cooperative Nadiia", "iban", iban))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("IBAN_INVALID"))
                    .andExpect(jsonPath("$.errors.length()").value(1))
                    .andExpect(jsonPath("$.errors[0].field").value("iban"))
                    .andExpect(jsonPath("$.errors[0].code").value("IBAN_INVALID"));
        }
        save(chairman, Map.of("iban", "UA" + "1".repeat(63))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("iban"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        save(chairman, Map.of("officialName", "x".repeat(301))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("officialName"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        assertThat(rowCount(DEMO_TENANT)).isZero();
        Thread.sleep(500); // an event, if one were published, would be written within this time
        assertThat(auditCount()).isEqualTo(before);

        // the longest values the contract allows are saved
        save(chairman, Map.of("officialName", "x".repeat(300), "legalAddress", "y".repeat(500),
                "bankName", "z".repeat(200), "iban", VALID_IBAN)).andExpect(status().isOk());
    }

    @Test
    void theEdrpouIsShownButCannotBeChangedThroughTheSettings() throws Exception {
        String chairman = demo("chairman@demo.local");
        read(chairman).andExpect(status().isOk()).andExpect(jsonPath("$.edrpou").doesNotExist());

        ownerJdbc().update("update tenant set edrpou = '12345678' where id = ?", DEMO_TENANT);
        read(chairman).andExpect(jsonPath("$.edrpou").value("12345678"));
        read(demo("member@demo.local")).andExpect(jsonPath("$.edrpou").value("12345678"));

        save(chairman, Map.of("edrpou", "87654321", "bankName", "Test Bank")).andExpect(status().isOk())
                .andExpect(jsonPath("$.edrpou").value("12345678"))
                .andExpect(jsonPath("$.bankName").value("Test Bank"));
        assertThat(ownerJdbc().queryForObject("select edrpou from tenant where id = ?", String.class, DEMO_TENANT))
                .isEqualTo("12345678");
    }

    @Test
    void withoutSettingsManageTheRequisitesCannotBeChanged() throws Exception {
        Map<String, Object> requisites = Map.of("officialName", "Garden cooperative Nadiia", "iban", VALID_IBAN);
        for (String bearer : List.of(demo("treasurer@demo.local"), demo("member@demo.local"),
                demo("electrician@demo.local"), customRoleToken("PLOTS_EDIT"))) {
            save(bearer, requisites).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        assertThat(rowCount(DEMO_TENANT)).isZero();

        save(customRoleToken("SETTINGS_MANAGE"), requisites).andExpect(status().isOk())
                .andExpect(jsonPath("$.iban").value(VALID_IBAN));
    }

    @Test
    void theRequisitesAndTheEdrpouOfOneCooperativeAreNotSeenByAnother() throws Exception {
        ownerJdbc().update("update tenant set edrpou = '12345678' where id = ?", DEMO_TENANT);
        ownerJdbc().update("update tenant set edrpou = '99999999' where id = ?", OTHER_TENANT);
        save(demo("chairman@demo.local"), Map.of("officialName", "Garden cooperative Nadiia", "iban", VALID_IBAN))
                .andExpect(status().isOk());

        String otherMember = TestTokens.bearer(OTHER_TENANT, "sub-member@other.local", "member@other.local");
        read(otherMember).andExpect(status().isOk())
                .andExpect(jsonPath("$.officialName").doesNotExist())
                .andExpect(jsonPath("$.iban").doesNotExist())
                .andExpect(jsonPath("$.edrpou").value("99999999"));
        assertThat(rowCount(OTHER_TENANT)).isZero();
        assertThat(TenantContext.runAs(OTHER_TENANT, cooperativeSettings::requisites)).isEqualTo(Requisites.NONE);
        assertThat(TenantContext.runAs(DEMO_TENANT, cooperativeSettings::requisites).iban()).isEqualTo(VALID_IBAN);
        read(demo("member@demo.local")).andExpect(jsonPath("$.edrpou").value("12345678"));
    }

    // ---- helpers

    private static String demo(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }

    private ResultActions read(String bearer) throws Exception {
        return mvc.perform(get(SETTINGS)
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions save(String bearer, Object body) throws Exception {
        String content = body instanceof String text ? text : json.writeValueAsString(body);
        return mvc.perform(put(SETTINGS)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(content));
    }

    /** A token of a fresh member of the demo cooperative whose only role is a custom role with one permission. */
    private String customRoleToken(String permission) {
        String email = "settings-custom-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(DEMO_TENANT, email);
        UUID role = UUID.randomUUID();
        roles.add(role);
        inTenant(DEMO_TENANT, sql -> {
            sql.update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, DEMO_TENANT,
                    "Settings " + role);
            sql.update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)",
                    DEMO_TENANT, role, permission);
            return sql.update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)",
                    DEMO_TENANT, member, role);
        });
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    private int rowCount(UUID tenant) {
        return ownerJdbc().queryForObject("select count(*) from cooperative_settings where tenant_id = ?",
                Integer.class, tenant);
    }

    private String storedValue(UUID tenant) {
        return ownerJdbc().queryForObject(
                "select member_plot_status_visibility from cooperative_settings where tenant_id = ?",
                String.class, tenant);
    }

    private long auditCount() {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from audit_entry where entity_type = 'SETTINGS' and entity_id = ?", Long.class,
                DEMO_TENANT));
    }

    private List<Map<String, Object>> auditEntries() {
        return inTenant(DEMO_TENANT, sql -> sql.queryForList(
                "select action, actor_member_id, entity_label, cast(changes as text) as changes from audit_entry "
                        + "where entity_type = 'SETTINGS' and entity_id = ? order by occurred_at, id", DEMO_TENANT));
    }
}
