package app.nadel.tariffs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.DemoTariffData;
import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Charge types: the seeded four, add, change, reorder, archive, name rules and who may do what. */
class ChargeTypesTests extends IntegrationTest {

    private static final String API = "/api/v1/charge-types";
    private static final List<String> CODES = List.of("MEMBERSHIP", "ELECTRICITY", "LAND_TAX", "WATER");
    private static final List<String> ENGLISH = List.of("Membership fee", "Electricity", "Land tax", "Water");

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private final List<UUID> roles = new ArrayList<>();

    @BeforeEach
    void startFromTheSeededState() {
        restoreTheSeededState();
    }

    @AfterEach
    void putTheDemoTariffsBack() {
        restoreTheSeededState();
        DemoTariffData.restore(ownerJdbc());
    }

    private void restoreTheSeededState() {
        // Types are archived, never deleted, so the owner role puts the two cooperatives back to "just seeded" (no
        // tariffs, so the demo tariffs and the Road repair type are removed here and restored after the test).
        JdbcTemplate owner = ownerJdbc();
        DemoTariffData.clear(owner);
        for (UUID role : roles) {
            inTenant(DEMO_TENANT, sql -> {
                sql.update("delete from member_role where role_id = ?", role);
                sql.update("delete from role_permission where role_id = ?", role);
                return sql.update("delete from role where id = ?", role);
            });
        }
        roles.clear();
        // a first sign-in links the demo members to a subject; other tests expect them unlinked (their own subject)
        inTenant(DEMO_TENANT, sql -> sql.update("update member set keycloak_subject = null where email in "
                + "('chairman@demo.local', 'treasurer@demo.local', 'member@demo.local', 'electrician@demo.local')"));
    }

    // ---- the four seeded types

    @Test
    void theDemoCooperativeStartsWithTheFourSeededTypesWaterInactive() throws Exception {
        JsonNode list = list(demo("chairman@demo.local"));

        assertThat(list).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(list.get(i).get("code").asString()).isEqualTo(CODES.get(i));
            assertThat(list.get(i).get("name").asString()).isEqualTo(ENGLISH.get(i));
            assertThat(list.get(i).get("nameCustomized").asBoolean()).isFalse();
            assertThat(list.get(i).get("active").asBoolean()).isEqualTo(i < 3);
            assertThat(list.get(i).get("position").asInt()).isEqualTo(i + 1);
            assertThat(list.get(i).has("description")).isFalse();
        }
    }

    @Test
    void theStoredEnglishNamesAreTheDefaultsOfTheNamesFile() {
        for (int i = 0; i < 4; i++) {
            assertThat(DefaultChargeTypeNames.of(CODES.get(i)).getFirst()).isEqualTo(ENGLISH.get(i));
        }
    }

    @Test
    void theOtherCooperativeHasItsOwnFourAndTheListsNeverMix() throws Exception {
        List<Map<String, Object>> other = ownerJdbc().queryForList(
                "select code, name, active, position from charge_type where tenant_id = ? order by position, id",
                OTHER_TENANT);
        assertThat(other).extracting(row -> row.get("code")).containsExactlyElementsOf(CODES);
        assertThat(other).extracting(row -> row.get("name")).containsExactlyElementsOf(ENGLISH);
        assertThat(other).extracting(row -> row.get("active")).containsExactly(true, true, true, false);

        List<String> demoIds = new ArrayList<>();
        list(demo("chairman@demo.local")).forEach(node -> demoIds.add(node.get("id").asString()));
        List<String> otherIds = ownerJdbc().queryForList("select id::text from charge_type where tenant_id = ?",
                String.class, OTHER_TENANT);
        assertThat(demoIds).doesNotContainAnyElementsOf(otherIds);
    }

    @Test
    void aBrandNewCooperativeGetsTheTypesAndThePermissionsFromSeedBaseRoles() {
        JdbcTemplate owner = ownerJdbc();
        UUID tenant = UUID.randomUUID();
        owner.update("insert into tenant (id, name) values (?, 'Charge Types Test Cooperative')", tenant);
        try {
            owner.queryForList("select seed_base_roles(?)", tenant);
            owner.queryForList("select seed_base_roles(?)", tenant); // idempotent

            List<Map<String, Object>> types = owner.queryForList(
                    "select code, name, active, position, name_customized, md5(? || ':CHARGE_TYPE:' || code)::uuid = id "
                            + "as deterministic from charge_type where tenant_id = ? order by position",
                    tenant.toString(), tenant);
            assertThat(types).extracting(row -> row.get("code")).containsExactlyElementsOf(CODES);
            assertThat(types).extracting(row -> row.get("name")).containsExactlyElementsOf(ENGLISH);
            assertThat(types).extracting(row -> row.get("position")).containsExactly(1, 2, 3, 4);
            assertThat(types).extracting(row -> row.get("active")).containsExactly(true, true, true, false);
            assertThat(types).extracting(row -> row.get("name_customized")).containsOnly(false);
            assertThat(types).extracting(row -> row.get("deterministic")).containsOnly(true);
            assertThat(owner.queryForList("select permission from role_permission where role_id = "
                    + "md5(?::text || ':CHAIRMAN')::uuid and permission like 'TARIFFS%'", String.class, tenant))
                    .containsExactlyInAnyOrder("TARIFFS_VIEW", "TARIFFS_MANAGE");
            assertThat(owner.queryForList("select permission from role_permission where role_id = "
                    + "md5(?::text || ':TREASURER')::uuid and permission like 'TARIFFS%'", String.class, tenant))
                    .containsExactly("TARIFFS_VIEW");
            assertThat(owner.queryForList("select permission from role_permission where role_id = "
                    + "md5(?::text || ':MEMBER')::uuid", String.class, tenant)).isEmpty();
        } finally {
            owner.update("delete from charge_type where tenant_id = ?", tenant);
            owner.update("delete from role_permission where tenant_id = ?", tenant);
            owner.update("delete from role where tenant_id = ?", tenant);
            owner.update("delete from tenant where id = ?", tenant);
        }
    }

    @Test
    void seedingAgainNeverRestoresWhatTheChairmanChanged() throws Exception {
        String chairman = demo("chairman@demo.local");
        UUID membership = idOf(DEMO_TENANT, "MEMBERSHIP");
        update(chairman, membership, "Fees 2026", "Yearly fees", false).andExpect(status().isOk());
        send(chairman, post(API + "/" + idOf(DEMO_TENANT, "WATER") + "/archive"), null)
                .andExpect(status().isNoContent());

        ownerJdbc().queryForList("select seed_base_roles(?)", DEMO_TENANT);

        assertThat(ownerJdbc().queryForObject("select name from charge_type where id = ?", String.class, membership))
                .isEqualTo("Fees 2026");
        assertThat(list(chairman)).hasSize(3);
    }

    // ---- add

    @Test
    void theChairmanAddsATypeAtTheEndOfTheListActiveAndCustomised() throws Exception {
        String chairman = demo("chairman@demo.local");

        MockResult created = MockResult.of(send(chairman, post(API),
                Map.of("name", "  Road repair ", "description", "  Fund for the road  ",
                        "tenantId", OTHER_TENANT.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Road repair"))
                .andExpect(jsonPath("$.description").value("Fund for the road"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.nameCustomized").value(true))
                .andExpect(jsonPath("$.position").value(5))
                .andExpect(jsonPath("$.code").doesNotExist()), json);

        String id = created.node().get("id").asString();
        JsonNode list = list(chairman);
        assertThat(list).hasSize(5);
        assertThat(list.get(4).get("id").asString()).isEqualTo(id);
        assertThat(ownerJdbc().queryForObject("select tenant_id from charge_type where id = ?::uuid", UUID.class, id))
                .as("the cooperative comes from the token, never from the body").isEqualTo(DEMO_TENANT);
        assertThat(ownerJdbc().queryForObject("select position from charge_type where id = ?::uuid", Integer.class, id))
                .isEqualTo(5);
    }

    @Test
    void theLocationHeaderPointsAtTheNewType() throws Exception {
        MockResult created = MockResult.of(send(demo("chairman@demo.local"), post(API), Map.of("name", "Fence"))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION)), json);

        assertThat(created.location()).isEqualTo(API + "/" + created.node().get("id").asString());
    }

    @Test
    void aBlankDescriptionIsNotStored() throws Exception {
        send(demo("chairman@demo.local"), post(API), Map.of("name", "Fence", "description", "   "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").doesNotExist());
    }

    // ---- change

    @Test
    void renamingASeededTypeMakesItCustomisedForGood() throws Exception {
        String chairman = demo("chairman@demo.local");
        UUID membership = idOf(DEMO_TENANT, "MEMBERSHIP");

        update(chairman, membership, "Fees 2026", null, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Fees 2026"))
                .andExpect(jsonPath("$.nameCustomized").value(true))
                .andExpect(jsonPath("$.code").value("MEMBERSHIP"));
        // changing something else later keeps the custom name
        update(chairman, membership, "Fees 2026", "Per sotka", true).andExpect(status().isOk())
                .andExpect(jsonPath("$.nameCustomized").value(true))
                .andExpect(jsonPath("$.description").value("Per sotka"));

        JsonNode first = list(chairman).get(0);
        assertThat(first.get("name").asString()).isEqualTo("Fees 2026");
        assertThat(first.get("nameCustomized").asBoolean()).isTrue();
        assertThat(first.get("position").asInt()).isEqualTo(1);
    }

    @Test
    void descriptionAndActiveCanBeChangedAndTheDescriptionCleared() throws Exception {
        String chairman = demo("chairman@demo.local");
        UUID water = idOf(DEMO_TENANT, "WATER");

        update(chairman, water, "Water", "For the water meters", true).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.description").value("For the water meters"))
                .andExpect(jsonPath("$.name").value("Water"))
                .andExpect(jsonPath("$.nameCustomized").value(false));
        update(chairman, water, "Water", "  ", false).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.description").doesNotExist());
    }

    @Test
    void sendingTheDisplayedDefaultNameBackIsNotARename() throws Exception {
        String chairman = demo("chairman@demo.local");
        UUID membership = idOf(DEMO_TENANT, "MEMBERSHIP");

        // the web client shows the Ukrainian default and sends it back when only the description changes
        update(chairman, membership, "Членський внесок", "Yearly", true).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Membership fee"))
                .andExpect(jsonPath("$.nameCustomized").value(false))
                .andExpect(jsonPath("$.description").value("Yearly"));
        update(chairman, membership, "MEMBERSHIP FEE", "Yearly", true).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Membership fee"))
                .andExpect(jsonPath("$.nameCustomized").value(false));
    }

    @Test
    void aCustomTypeCanChangeTheLetterCaseOfItsOwnName() throws Exception {
        String chairman = demo("chairman@demo.local");
        UUID id = create(chairman, "Road repair");

        update(chairman, id, "ROAD REPAIR", null, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ROAD REPAIR"));
    }

    @Test
    void savingTheCurrentValuesIsAcceptedAndChangesNothing() throws Exception {
        String chairman = demo("chairman@demo.local");
        UUID id = create(chairman, "Road repair");
        String before = ownerJdbc().queryForObject("select updated_at::text from charge_type where id = ?",
                String.class, id);

        update(chairman, id, "Road repair", null, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Road repair"));

        assertThat(ownerJdbc().queryForObject("select updated_at::text from charge_type where id = ?", String.class,
                id)).isEqualTo(before);
    }

    // ---- name rules

    @Test
    void aNameEqualToAnotherTypeIsRefusedIgnoringLetterCase() throws Exception {
        String chairman = demo("chairman@demo.local");
        create(chairman, "Road repair");

        for (String clash : List.of("Road repair", "ROAD REPAIR", "  road repair  ")) {
            send(chairman, post(API), Map.of("name", clash)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CHARGE_TYPE_NAME_TAKEN"))
                    .andExpect(jsonPath("$.errors[0].field").value("name"))
                    .andExpect(jsonPath("$.errors[0].code").value("CHARGE_TYPE_NAME_TAKEN"));
        }
        assertThat(list(chairman)).hasSize(5);
    }

    @Test
    void theEnglishAndUkrainianDefaultsOfTheSeededTypesAreTaken() throws Exception {
        String chairman = demo("chairman@demo.local");

        for (String clash : List.of("Membership fee", "membership FEE", "Членський внесок", "електроенергія",
                "ЗЕМЕЛЬНИЙ ПОДАТОК", "Water", "Вода")) {
            send(chairman, post(API), Map.of("name", clash)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CHARGE_TYPE_NAME_TAKEN"))
                    .andExpect(jsonPath("$.errors[0].field").value("name"));
        }
        assertThat(list(chairman)).hasSize(4);
    }

    @Test
    void renamingToTheDefaultOfAnotherUnrenamedTypeIsRefused() throws Exception {
        String chairman = demo("chairman@demo.local");
        UUID id = create(chairman, "Road repair");

        update(chairman, id, "Вода", null, true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_TYPE_NAME_TAKEN"));
        update(chairman, id, "Electricity", null, true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_TYPE_NAME_TAKEN"));
        // a seeded type cannot take the default of another seeded type either
        update(chairman, idOf(DEMO_TENANT, "WATER"), "Земельний податок", null, false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_TYPE_NAME_TAKEN"));
        // nor the name of a custom type
        update(chairman, idOf(DEMO_TENANT, "WATER"), "road repair", null, false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_TYPE_NAME_TAKEN"));
    }

    @Test
    void theDefaultsOfARenamedSeededTypeAreFreeAgain() throws Exception {
        String chairman = demo("chairman@demo.local");
        update(chairman, idOf(DEMO_TENANT, "MEMBERSHIP"), "Fees 2026", null, true).andExpect(status().isOk());

        send(chairman, post(API), Map.of("name", "Членський внесок")).andExpect(status().isCreated());
        send(chairman, post(API), Map.of("name", "Membership fee")).andExpect(status().isCreated());
        assertThat(list(chairman)).hasSize(6);
    }

    @Test
    void anArchivedTypeFreesItsNameAndItsDefaults() throws Exception {
        String chairman = demo("chairman@demo.local");
        UUID road = create(chairman, "Road repair");
        send(chairman, post(API + "/" + road + "/archive"), null).andExpect(status().isNoContent());
        send(chairman, post(API + "/" + idOf(DEMO_TENANT, "WATER") + "/archive"), null)
                .andExpect(status().isNoContent());

        send(chairman, post(API), Map.of("name", "Road repair")).andExpect(status().isCreated());
        send(chairman, post(API), Map.of("name", "Вода")).andExpect(status().isCreated());
        send(chairman, post(API), Map.of("name", "Water")).andExpect(status().isCreated());
    }

    @Test
    void theSameNameIsFineInAnotherCooperative() throws Exception {
        create(demo("chairman@demo.local"), "Road repair");

        assertThat(ownerJdbc().queryForObject(
                "select count(*) from charge_type where tenant_id = ? and lower(name) = 'road repair'", Integer.class,
                OTHER_TENANT)).isZero();
        ownerJdbc().update("insert into charge_type (tenant_id, name, position) values (?, 'Road repair', 5)",
                OTHER_TENANT);
    }

    @Test
    void invalidInputIsRejectedWithFieldCodes() throws Exception {
        String chairman = demo("chairman@demo.local");
        UUID id = create(chairman, "Road repair");

        send(chairman, post(API), Map.of("name", "   ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(chairman, post(API), Map.of("name", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(chairman, post(API), Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(chairman, post(API), Map.of("name", "x".repeat(101))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        send(chairman, post(API), Map.of("name", "x".repeat(100))).andExpect(status().isCreated());
        send(chairman, post(API), Map.of("name", "Fence", "description", "x".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("description"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        send(chairman, post(API), "not json").andExpect(status().isBadRequest());

        send(chairman, put(API + "/" + id), Map.of("name", "Road repair")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("active"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        update(chairman, id, "   ", null, true).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        update(chairman, id, "y".repeat(101), null, true).andExpect(status().isBadRequest());
        update(chairman, id, "y".repeat(100), null, true).andExpect(status().isOk());
    }

    // ---- not found, archive

    @Test
    void anUnknownOrForeignTypeIsNotFound() throws Exception {
        String chairman = demo("chairman@demo.local");
        UUID foreign = idOf(OTHER_TENANT, "MEMBERSHIP");

        for (UUID id : List.of(UUID.randomUUID(), foreign)) {
            update(chairman, id, "Fees", null, true).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            send(chairman, post(API + "/" + id + "/archive"), null).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        assertThat(ownerJdbc().queryForObject("select archived_at is null and name = 'Membership fee' "
                + "from charge_type where id = ?", Boolean.class, foreign)).isTrue();
    }

    @Test
    void archivingRemovesTheTypeFromTheListAndItCannotBeChangedOrArchivedAgain() throws Exception {
        String chairman = demo("chairman@demo.local");
        UUID electricity = idOf(DEMO_TENANT, "ELECTRICITY");

        send(chairman, post(API + "/" + electricity + "/archive"), null).andExpect(status().isNoContent());

        JsonNode list = list(chairman);
        assertThat(list).hasSize(3);
        assertThat(list).extracting(node -> node.get("code").asString())
                .containsExactly("MEMBERSHIP", "LAND_TAX", "WATER");
        assertThat(ownerJdbc().queryForObject("select archived_at is not null from charge_type where id = ?",
                Boolean.class, electricity)).isTrue();
        send(chairman, post(API + "/" + electricity + "/archive"), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_TYPE_ARCHIVED"));
        update(chairman, electricity, "Power", null, true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_TYPE_ARCHIVED"));
    }

    // ---- order

    @Test
    void theOrderIsChangedAndKeptAfterReloading() throws Exception {
        String chairman = demo("chairman@demo.local");
        List<String> ids = idsOf(list(chairman));

        // land tax moves up one place
        List<String> reordered = List.of(ids.get(0), ids.get(2), ids.get(1), ids.get(3));
        send(chairman, put(API + "/order"), Map.of("ids", reordered)).andExpect(status().isNoContent());

        JsonNode list = list(chairman);
        assertThat(idsOf(list)).containsExactlyElementsOf(reordered);
        assertThat(list).extracting(node -> node.get("position").asInt()).containsExactly(1, 2, 3, 4);
        assertThat(list.get(1).get("code").asString()).isEqualTo("LAND_TAX");
    }

    @Test
    void theOrderOfTheCurrentListChangesNothing() throws Exception {
        String chairman = demo("chairman@demo.local");
        List<String> ids = idsOf(list(chairman));
        archiveType(chairman, ids.get(1)); // positions are now 1, 3, 4
        List<String> current = idsOf(list(chairman));

        send(chairman, put(API + "/order"), Map.of("ids", current)).andExpect(status().isNoContent());

        assertThat(ownerJdbc().queryForList("select position from charge_type where archived_at is null "
                + "and tenant_id = ? order by position", Integer.class, DEMO_TENANT)).containsExactly(1, 3, 4);
        assertThat(idsOf(list(chairman))).containsExactlyElementsOf(current);
    }

    @Test
    void reorderingAfterAnArchiveRenumbersTheRemainingTypes() throws Exception {
        String chairman = demo("chairman@demo.local");
        List<String> ids = idsOf(list(chairman));
        archiveType(chairman, ids.get(1));

        send(chairman, put(API + "/order"), Map.of("ids", List.of(ids.get(3), ids.get(2), ids.get(0))))
                .andExpect(status().isNoContent());

        assertThat(idsOf(list(chairman))).containsExactly(ids.get(3), ids.get(2), ids.get(0));
        assertThat(ownerJdbc().queryForList("select position from charge_type where archived_at is null "
                + "and tenant_id = ? order by position", Integer.class, DEMO_TENANT)).containsExactly(1, 2, 3);
    }

    @Test
    void aStaleOrderIsRefusedAndChangesNothing() throws Exception {
        String chairman = demo("chairman@demo.local");
        List<String> ids = idsOf(list(chairman));
        String unknown = UUID.randomUUID().toString();
        String foreign = idOf(OTHER_TENANT, "MEMBERSHIP").toString();
        archiveType(chairman, ids.get(3));

        List<List<String>> stale = List.of(
                List.of(ids.get(2), ids.get(1), ids.get(0), ids.get(3)), // contains an archived type
                List.of(ids.get(2), ids.get(1)), // misses one
                List.of(ids.get(2), ids.get(1), ids.get(0), unknown), // an unknown id
                List.of(ids.get(2), ids.get(1), ids.get(0), foreign), // another cooperative's id
                List.of(ids.get(2), ids.get(1), ids.get(1)), // a repeated id
                List.of(ids.get(2), ids.get(1), ids.get(0), ids.get(0)), // longer, with a repeat
                List.of());
        for (List<String> candidate : stale) {
            send(chairman, put(API + "/order"), Map.of("ids", candidate)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CHARGE_TYPE_ORDER_STALE"));
        }
        assertThat(idsOf(list(chairman))).containsExactly(ids.get(0), ids.get(1), ids.get(2));
    }

    @Test
    void anInvalidOrderBodyIsRejected() throws Exception {
        String chairman = demo("chairman@demo.local");

        send(chairman, put(API + "/order"), Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("ids"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(chairman, put(API + "/order"), Map.of("ids", List.of("not-a-uuid"))).andExpect(status().isBadRequest());
    }

    @Test
    void aNewTypeIsAddedAfterAnArchivedOne() throws Exception {
        String chairman = demo("chairman@demo.local");
        UUID last = create(chairman, "Fence");
        send(chairman, post(API + "/" + last + "/archive"), null).andExpect(status().isNoContent());

        send(chairman, post(API), Map.of("name", "Gate")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(6));
    }

    // ---- who may do what

    @Test
    void theTreasurerReadsButCannotWrite() throws Exception {
        String treasurer = demo("treasurer@demo.local");
        UUID membership = idOf(DEMO_TENANT, "MEMBERSHIP");

        send(treasurer, get(API), null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(4)));
        denied(treasurer, membership);
    }

    @Test
    void aMemberAndTheElectricianHaveNoAccessAtAll() throws Exception {
        UUID membership = idOf(DEMO_TENANT, "MEMBERSHIP");

        for (String email : List.of("member@demo.local", "electrician@demo.local")) {
            String token = demo(email);
            send(token, get(API), null).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            denied(token, membership);
        }
    }

    @Test
    void thePlatformOperatorHasNoAccess() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();
        UUID membership = idOf(DEMO_TENANT, "MEMBERSHIP");

        send(operator, get(API), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        denied(operator, membership);
    }

    @Test
    void withoutATokenEveryEndpointIsUnauthorised() throws Exception {
        UUID membership = idOf(DEMO_TENANT, "MEMBERSHIP");

        mvc.perform(get(API)).andExpect(status().isUnauthorized());
        mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Fence\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(API + "/" + membership).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Fence\",\"active\":true}")).andExpect(status().isUnauthorized());
        mvc.perform(post(API + "/" + membership + "/archive")).andExpect(status().isUnauthorized());
        mvc.perform(put(API + "/order").contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aWriterNeedsViewToo() throws Exception {
        UUID membership = idOf(DEMO_TENANT, "MEMBERSHIP");

        String manageOnly = customRoleToken("TARIFFS_MANAGE");
        send(manageOnly, get(API), null).andExpect(status().isForbidden());
        denied(manageOnly, membership);

        String viewOnly = customRoleToken("TARIFFS_VIEW");
        send(viewOnly, get(API), null).andExpect(status().isOk());
        denied(viewOnly, membership);

        String both = customRoleToken("TARIFFS_VIEW", "TARIFFS_MANAGE");
        send(both, get(API), null).andExpect(status().isOk());
        send(both, post(API), Map.of("name", "Fence")).andExpect(status().isCreated());
        update(both, membership, "Fees", null, true).andExpect(status().isOk());
    }

    @Test
    void theTreasurerGetsWriteAccessOnlyThroughARoleChange() throws Exception {
        String chairman = demo("chairman@demo.local");
        UUID treasurerRole = ownerJdbc().queryForObject("select id from role where tenant_id = ? and base_code = "
                + "'TREASURER'", UUID.class, DEMO_TENANT);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Treasurer");
        body.put("description", "Read access to members, plots and audit");
        body.put("permissions", List.of("MEMBERS_VIEW", "PLOTS_VIEW", "AUDIT_VIEW", "TARIFFS_VIEW", "TARIFFS_MANAGE"));
        try {
            send(chairman, put("/api/v1/roles/" + treasurerRole), body).andExpect(status().isOk());

            send(demo("treasurer@demo.local"), post(API), Map.of("name", "Fence")).andExpect(status().isCreated());
        } finally {
            ownerJdbc().update("delete from role_permission where role_id = ? and permission = 'TARIFFS_MANAGE'",
                    treasurerRole);
        }
    }

    // ---- helpers

    /** Every write endpoint answers 403 for the caller. */
    private void denied(String bearer, UUID type) throws Exception {
        send(bearer, post(API), Map.of("name", "Fence")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        update(bearer, type, "Fees", null, true).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(bearer, post(API + "/" + type + "/archive"), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(bearer, put(API + "/order"), Map.of("ids", List.of(type.toString()))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(ownerJdbc().queryForObject("select count(*) from charge_type where tenant_id = ? and "
                + "(code is null or name_customized or archived_at is not null)", Integer.class, DEMO_TENANT))
                .as("nothing was written").isZero();
    }

    private static String demo(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }

    private ResultActions send(String bearer, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(body instanceof String text ? text : json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private ResultActions update(String bearer, UUID id, String name, String description, boolean active)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (description != null) {
            body.put("description", description);
        }
        body.put("active", active);
        return send(bearer, put(API + "/" + id), body);
    }

    private UUID create(String bearer, String name) throws Exception {
        String body = send(bearer, post(API), Map.of("name", name)).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    private void archiveType(String bearer, String id) throws Exception {
        send(bearer, post(API + "/" + id + "/archive"), null).andExpect(status().isNoContent());
    }

    private JsonNode list(String bearer) throws Exception {
        String body = send(bearer, get(API), null).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        return json.readTree(body);
    }

    private static List<String> idsOf(JsonNode list) {
        List<String> ids = new ArrayList<>();
        list.forEach(node -> ids.add(node.get("id").asString()));
        return ids;
    }

    private UUID idOf(UUID tenant, String code) {
        return ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? and code = ?", UUID.class,
                tenant, code);
    }

    /** A token of a fresh member of the demo cooperative whose only role is a custom role with the permissions. */
    private String customRoleToken(String... permissions) {
        String email = "tariffs-custom-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(DEMO_TENANT, email);
        UUID role = UUID.randomUUID();
        roles.add(role);
        inTenant(DEMO_TENANT, sql -> {
            sql.update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, DEMO_TENANT, "Tariffs " + role);
            for (String permission : permissions) {
                sql.update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)",
                        DEMO_TENANT, role, permission);
            }
            return sql.update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)",
                    DEMO_TENANT, member, role);
        });
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    /** The body and the Location header of a response, read once. */
    private record MockResult(JsonNode node, String location) {

        static MockResult of(ResultActions actions, JsonMapper json) throws Exception {
            var response = actions.andReturn().getResponse();
            return new MockResult(json.readTree(response.getContentAsString()),
                    response.getHeader(HttpHeaders.LOCATION));
        }
    }
}
