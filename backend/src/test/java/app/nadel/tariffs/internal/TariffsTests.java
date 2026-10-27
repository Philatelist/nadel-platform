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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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

/** Tariffs and their versions: create, add, derived end dates, rate and one-off rules, the list, who may do what. */
class TariffsTests extends IntegrationTest {

    private static final String API = "/api/v1/tariffs";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Europe/Kyiv"));

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private final List<UUID> roles = new ArrayList<>();
    private final List<UUID> plots = new ArrayList<>();

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
        JdbcTemplate owner = ownerJdbc();
        for (UUID plot : plots) {
            owner.update("delete from meter where plot_id = ?", plot); // a plot with a meter is not deleted
            owner.update("delete from plot where id = ?", plot); // scope rows cascade
        }
        plots.clear();
        DemoTariffData.clear(owner); // no demo tariffs during the test; they are restored afterwards
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
    }

    // ---- create

    @Test
    void theChairmanCreatesATariffWithItsFirstVersion() throws Exception {
        UUID membership = typeId(DEMO_TENANT, "MEMBERSHIP");

        JsonNode created = body(send(chairman(), post(API), create(membership, "  For members ",
                version("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "50", null, "  General meeting 12.04.2026 ")))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.name").value("For members"))
                .andExpect(jsonPath("$.scopeKind").value("ALL_PLOTS"))
                .andExpect(jsonPath("$.chargeType.id").value(membership.toString()))
                .andExpect(jsonPath("$.chargeType.code").value("MEMBERSHIP"))
                .andExpect(jsonPath("$.chargeType.name").value("Membership fee"))
                .andExpect(jsonPath("$.chargeType.nameCustomized").value(false))
                .andExpect(jsonPath("$.chargeType.active").value(true))
                .andExpect(jsonPath("$.versions", hasSize(1)))
                .andExpect(jsonPath("$.versions[0].validFrom").value("2027-01-01"))
                .andExpect(jsonPath("$.versions[0].validTo").doesNotExist())
                .andExpect(jsonPath("$.versions[0].basis").value("PER_AREA_SOTKA"))
                .andExpect(jsonPath("$.versions[0].frequency").value("YEARLY"))
                .andExpect(jsonPath("$.versions[0].rate").value("50.0000"))
                .andExpect(jsonPath("$.versions[0].dueDate").doesNotExist())
                .andExpect(jsonPath("$.versions[0].note").value("General meeting 12.04.2026"))
                .andExpect(jsonPath("$.versions[0].pastDated").value(TODAY.isAfter(LocalDate.of(2027, 1, 1)))));

        String id = created.get("id").asString();
        assertThat(ownerJdbc().queryForObject("select tenant_id from tariff where id = ?::uuid", UUID.class, id))
                .isEqualTo(DEMO_TENANT);
        assertThat(ownerJdbc().queryForObject("select count(*) from tariff_version where tariff_id = ?::uuid",
                Integer.class, id)).isEqualTo(1);
        send(chairman(), get(API + "/" + id), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("For members"))
                .andExpect(jsonPath("$.versions", hasSize(1)));
    }

    @Test
    void theCooperativeComesFromTheTokenNotTheBody() throws Exception {
        Map<String, Object> request = create(typeId(DEMO_TENANT, "MEMBERSHIP"), "Members",
                version("2027-01-01", "PER_PLOT", "MONTHLY", "10", null, null));
        request.put("tenantId", OTHER_TENANT.toString());

        JsonNode created = body(send(chairman(), post(API), request).andExpect(status().isCreated()));

        assertThat(ownerJdbc().queryForObject("select tenant_id from tariff where id = ?::uuid", UUID.class,
                created.get("id").asString())).isEqualTo(DEMO_TENANT);
    }

    @Test
    void aBlankNoteIsNotStoredAndAnEmptyNameIsRefused() throws Exception {
        UUID membership = typeId(DEMO_TENANT, "MEMBERSHIP");

        send(chairman(), post(API), create(membership, "Members",
                version("2027-01-01", "PER_PLOT", "MONTHLY", "10", null, "   ")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.versions[0].note").doesNotExist());

        send(chairman(), post(API), create(membership, "   ",
                version("2027-01-01", "PER_PLOT", "MONTHLY", "10", null, null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(chairman(), post(API), create(membership, "x".repeat(101),
                version("2027-01-01", "PER_PLOT", "MONTHLY", "10", null, null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        assertThat(count("tariff")).isEqualTo(1);
    }

    @Test
    void aMissingFirstVersionOrChargeTypeIsRefused() throws Exception {
        send(chairman(), post(API), Map.of("name", "Members")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "chargeTypeId", "firstVersion")));
    }

    // ---- versions

    @Test
    void versionsAreListedNewestFirstWithTheirDerivedEnd() throws Exception {
        String tariff = newTariff("Members", "2027-01-01", "PER_AREA_SOTKA", "YEARLY", "50");
        send(chairman(), post(API + "/" + tariff + "/versions"),
                version("2028-01-01", "PER_AREA_SOTKA", "YEARLY", "70", null, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.validFrom").value("2028-01-01"))
                .andExpect(jsonPath("$.validTo").doesNotExist());
        // inserted last, but dated between the two: its end is the day before the 2028 version
        send(chairman(), post(API + "/" + tariff + "/versions"),
                version("2027-07-01", "PER_AREA_SOTKA", "YEARLY", "60", null, "Raised"))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.rate").value("60.0000"))
                .andExpect(jsonPath("$.validTo").value("2027-12-31"))
                .andExpect(jsonPath("$.note").value("Raised"));

        send(chairman(), get(API + "/" + tariff), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.versions", hasSize(3)))
                .andExpect(jsonPath("$.versions[0].validFrom").value("2028-01-01"))
                .andExpect(jsonPath("$.versions[0].validTo").doesNotExist())
                .andExpect(jsonPath("$.versions[1].validFrom").value("2027-07-01"))
                .andExpect(jsonPath("$.versions[1].validTo").value("2027-12-31"))
                .andExpect(jsonPath("$.versions[2].validFrom").value("2027-01-01"))
                .andExpect(jsonPath("$.versions[2].validTo").value("2027-06-30"))
                .andExpect(jsonPath("$.versions[2].rate").value("50.0000"));
    }

    @Test
    void twoVersionsCannotStartOnTheSameDate() throws Exception {
        String tariff = newTariff("Members", "2027-01-01", "PER_AREA_SOTKA", "YEARLY", "50");

        send(chairman(), post(API + "/" + tariff + "/versions"),
                version("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "99", null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TARIFF_VERSION_DATE_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("validFrom"))
                .andExpect(jsonPath("$.errors[0].code").value("TARIFF_VERSION_DATE_TAKEN"));

        assertThat(count("tariff_version")).isEqualTo(1);
        // the same date on another tariff is fine
        newTariff("Non-members", "2027-01-01", "PER_PLOT", "MONTHLY", "10");
    }

    @Test
    void aRateKeepsFourDecimalsWherever() throws Exception {
        String tariff = newTariff("Electricity", "2027-01-01", "PER_KWH", "MONTHLY", "4.32");

        assertThat(ownerJdbc().queryForObject("select rate::text from tariff_version where tariff_id = ?::uuid",
                String.class, tariff)).isEqualTo("4.3200");
        send(chairman(), get(API + "/" + tariff), null).andExpect(jsonPath("$.versions[0].rate").value("4.3200"));
        send(chairman(), get(API), null).andExpect(jsonPath("$[0].version.rate").value("4.3200"));
        send(chairman(), post(API + "/" + tariff + "/versions"),
                version("2027-02-01", "PER_KWH", "MONTHLY", "33.3333", null, null))
                .andExpect(jsonPath("$.rate").value("33.3333"));
        send(chairman(), post(API + "/" + tariff + "/versions"),
                version("2027-03-01", "PER_KWH", "MONTHLY", "0", null, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.rate").value("0.0000"));
        send(chairman(), post(API + "/" + tariff + "/versions"),
                version("2027-04-01", "PER_KWH", "MONTHLY", "9999999999.9999", null, null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.rate").value("9999999999.9999"));
    }

    @Test
    void anInvalidRateIsRefusedAndNothingIsStored() throws Exception {
        String tariff = newTariff("Members", "2027-01-01", "PER_PLOT", "MONTHLY", "10");

        for (String rate : List.of("1.23456", "-1", "-0.0001", "abc", "", " ", "1,5", "1e3", "12345678901", ".5",
                "1.", "+1")) {
            send(chairman(), post(API + "/" + tariff + "/versions"),
                    version("2028-01-01", "PER_PLOT", "MONTHLY", rate, null, null))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("rate"))
                    .andExpect(jsonPath("$.errors[0].code").value("RATE_INVALID"));
        }
        send(chairman(), post(API), create(typeId(DEMO_TENANT, "MEMBERSHIP"), "Another",
                version("2028-01-01", "PER_PLOT", "MONTHLY", "1.23456", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("firstVersion.rate"))
                .andExpect(jsonPath("$.errors[0].code").value("RATE_INVALID"));
        Map<String, Object> noRate = version("2028-01-01", "PER_PLOT", "MONTHLY", "1", null, null);
        noRate.remove("rate");
        send(chairman(), post(API + "/" + tariff + "/versions"), noRate).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("rate"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));

        assertThat(count("tariff_version")).isEqualTo(1);
        assertThat(count("tariff")).isEqualTo(1);
    }

    @Test
    void aFixedOneTimeAmountIsOneOffWithADueDate() throws Exception {
        UUID type = createType("Road repair");

        send(chairman(), post(API), create(type, "Road", version("2026-12-01", "FIXED_ONE_TIME", "ONE_OFF", "1200",
                "2026-12-31", null))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.versions[0].basis").value("FIXED_ONE_TIME"))
                .andExpect(jsonPath("$.versions[0].frequency").value("ONE_OFF"))
                .andExpect(jsonPath("$.versions[0].dueDate").value("2026-12-31"))
                .andExpect(jsonPath("$.versions[0].rate").value("1200.0000"));
    }

    @Test
    void theOneOffRulesAreEnforced() throws Exception {
        String tariff = newTariff("Members", "2027-01-01", "PER_PLOT", "MONTHLY", "10");
        String path = API + "/" + tariff + "/versions";

        // a fixed amount must be one-off
        send(chairman(), post(path), version("2028-01-01", "FIXED_ONE_TIME", "MONTHLY", "1200", "2028-02-01", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("frequency"))
                .andExpect(jsonPath("$.errors[0].code").value("FREQUENCY_BASIS_MISMATCH"));
        // one-off is only for a fixed amount
        send(chairman(), post(path), version("2028-01-01", "PER_PLOT", "ONE_OFF", "10", "2028-02-01", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("frequency"))
                .andExpect(jsonPath("$.errors[0].code").value("FREQUENCY_BASIS_MISMATCH"));
        // a fixed amount needs its due date
        send(chairman(), post(path), version("2028-01-01", "FIXED_ONE_TIME", "ONE_OFF", "1200", null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("dueDate"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        // a due date belongs to a fixed amount only
        send(chairman(), post(path), version("2028-01-01", "PER_PLOT", "MONTHLY", "10", "2028-02-01", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("dueDate"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        // every broken part is reported at once
        send(chairman(), post(path), version("2028-01-01", "FIXED_ONE_TIME", "YEARLY", "5", null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "FREQUENCY_BASIS_MISMATCH", "REQUIRED")));
        // on a new tariff the fields carry the prefix
        send(chairman(), post(API), create(typeId(DEMO_TENANT, "MEMBERSHIP"), "Other",
                version("2028-01-01", "FIXED_ONE_TIME", "MONTHLY", "10", "2028-02-01", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("firstVersion.frequency"));

        assertThat(count("tariff_version")).isEqualTo(1);
    }

    @Test
    void anUnknownEnumValueIsAMalformedRequest() throws Exception {
        String tariff = newTariff("Members", "2027-01-01", "PER_PLOT", "MONTHLY", "10");

        send(chairman(), post(API + "/" + tariff + "/versions"),
                version("2028-01-01", "PER_HOUR", "MONTHLY", "10", null, null)).andExpect(status().isBadRequest());
        send(chairman(), post(API + "/" + tariff + "/versions"),
                version("not-a-date", "PER_PLOT", "MONTHLY", "10", null, null)).andExpect(status().isBadRequest());
        send(chairman(), post(API + "/" + tariff + "/versions"),
                version("2028-01-01", "PER_PLOT", "MONTHLY", "10", null, "x".repeat(501)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("note"));
    }

    @Test
    void aVersionDatedInThePastIsSavedAndFlagged() throws Exception {
        String tariff = newTariff("Members", TODAY.plusDays(10).toString(), "PER_PLOT", "MONTHLY", "10");

        send(chairman(), post(API + "/" + tariff + "/versions"),
                version(TODAY.minusDays(1).toString(), "PER_PLOT", "MONTHLY", "9", null, null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.pastDated").value(true))
                .andExpect(jsonPath("$.validTo").value(TODAY.plusDays(9).toString()));
        send(chairman(), post(API + "/" + tariff + "/versions"),
                version(TODAY.toString(), "PER_PLOT", "MONTHLY", "11", null, null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.pastDated").value(false));

        send(chairman(), get(API + "/" + tariff), null)
                .andExpect(jsonPath("$.versions[0].validFrom").value(TODAY.plusDays(10).toString()))
                .andExpect(jsonPath("$.versions[0].pastDated").value(false))
                .andExpect(jsonPath("$.versions[1].validFrom").value(TODAY.toString()))
                .andExpect(jsonPath("$.versions[1].pastDated").value(false))
                .andExpect(jsonPath("$.versions[2].validFrom").value(TODAY.minusDays(1).toString()))
                .andExpect(jsonPath("$.versions[2].pastDated").value(true));
    }

    // ---- charge types

    @Test
    void anArchivedChargeTypeGetsNoTariffsAndNoVersions() throws Exception {
        UUID type = createType("Fence");
        String tariff = newTariff(type, "Fence", "2027-01-01", "PER_PLOT", "MONTHLY", "10");
        send(chairman(), post("/api/v1/charge-types/" + type + "/archive"), null).andExpect(status().isNoContent());

        send(chairman(), post(API), create(type, "Gate", version("2027-01-01", "PER_PLOT", "MONTHLY", "10", null,
                null))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHARGE_TYPE_ARCHIVED"));
        send(chairman(), post(API + "/" + tariff + "/versions"),
                version("2028-01-01", "PER_PLOT", "MONTHLY", "11", null, null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_TYPE_ARCHIVED"));

        assertThat(count("tariff")).isEqualTo(1);
        assertThat(count("tariff_version")).isEqualTo(1);
        // the tariff stays in history: its card still opens, with the archived charge type
        send(chairman(), get(API + "/" + tariff), null).andExpect(status().isOk());
    }

    @Test
    void anUnknownOrForeignChargeTypeIsNotFound() throws Exception {
        Map<String, Object> first = version("2027-01-01", "PER_PLOT", "MONTHLY", "10", null, null);

        send(chairman(), post(API), create(UUID.randomUUID(), "Members", first)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(chairman(), post(API), create(typeId(OTHER_TENANT, "MEMBERSHIP"), "Members", first))
                .andExpect(status().isNotFound());
        assertThat(count("tariff")).isZero();
    }

    // ---- other cooperatives and unknown ids

    @Test
    void aTariffOfAnotherCooperativeOrAnUnknownIdIsNotFound() throws Exception {
        String tariff = newTariff("Members", "2027-01-01", "PER_PLOT", "MONTHLY", "10");
        String foreign = otherChairman();

        send(foreign, get(API + "/" + tariff), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(foreign, post(API + "/" + tariff + "/versions"),
                version("2028-01-01", "PER_PLOT", "MONTHLY", "11", null, null)).andExpect(status().isNotFound());
        send(chairman(), get(API + "/" + UUID.randomUUID()), null).andExpect(status().isNotFound());
        send(chairman(), post(API + "/" + UUID.randomUUID() + "/versions"),
                version("2028-01-01", "PER_PLOT", "MONTHLY", "11", null, null)).andExpect(status().isNotFound());
        send(foreign, get(API), null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        assertThat(count("tariff_version")).isEqualTo(1);
    }

    // ---- the list

    @Test
    void theListShowsTheVersionValidTodayOrElseTheFirstFutureOne() throws Exception {
        String current = newTariff("Current", TODAY.minusDays(100).toString(), "PER_AREA_SOTKA", "YEARLY", "50");
        addVersion(current, TODAY.minusDays(10).toString(), "PER_AREA_SOTKA", "YEARLY", "60");
        addVersion(current, TODAY.plusDays(30).toString(), "PER_AREA_SOTKA", "YEARLY", "70");
        String future = newTariff("Future", TODAY.plusDays(50).toString(), "PER_PLOT", "MONTHLY", "5");
        addVersion(future, TODAY.plusDays(20).toString(), "PER_PLOT", "MONTHLY", "4");
        addVersion(future, TODAY.plusDays(90).toString(), "PER_PLOT", "MONTHLY", "6");
        String past = newTariff("Past", TODAY.minusDays(100).toString(), "PER_PLOT", "MONTHLY", "3");
        addVersion(past, TODAY.minusDays(40).toString(), "PER_PLOT", "MONTHLY", "2");
        String startsToday = newTariff("Today", TODAY.toString(), "PER_PLOT", "MONTHLY", "1");

        JsonNode list = body(send(chairman(), get(API), null).andExpect(status().isOk()));

        assertThat(list).hasSize(4);
        JsonNode byName = byName(list);
        assertThat(byName.get("Current").get("version").get("rate").asString()).isEqualTo("60.0000");
        assertThat(byName.get("Current").get("version").get("validTo").asString())
                .isEqualTo(TODAY.plusDays(29).toString());
        assertThat(byName.get("Current").get("version").get("pastDated").asBoolean()).isTrue();
        assertThat(byName.get("Future").get("version").get("rate").asString()).isEqualTo("4.0000");
        assertThat(byName.get("Future").get("version").get("validFrom").asString())
                .isEqualTo(TODAY.plusDays(20).toString());
        assertThat(byName.get("Past").get("version").get("rate").asString()).isEqualTo("2.0000");
        assertThat(byName.get("Past").get("version").has("validTo")).isFalse();
        assertThat(byName.get("Today").get("version").get("rate").asString()).isEqualTo("1.0000");
        assertThat(byName.get("Today").get("id").asString()).isEqualTo(startsToday);
        assertThat(byName.get("Current").get("id").asString()).isEqualTo(current);
        assertThat(byName.get("Current").get("chargeTypeId").asString())
                .isEqualTo(typeId(DEMO_TENANT, "MEMBERSHIP").toString());
        assertThat(byName.get("Current").get("scopeKind").asString()).isEqualTo("ALL_PLOTS");
        assertThat(byName.get("Past").get("id").asString()).isEqualTo(past);
        assertThat(byName.get("Future").get("id").asString()).isEqualTo(future);
    }

    @Test
    void theListFollowsTheChargeTypeOrderThenTheTariffNameAndSkipsArchivedTypes() throws Exception {
        UUID electricity = typeId(DEMO_TENANT, "ELECTRICITY");
        UUID fence = createType("Fence");
        newTariff(fence, "Zeta", "2027-01-01", "PER_PLOT", "MONTHLY", "1");
        newTariff(electricity, "Beta", "2027-01-01", "PER_KWH", "MONTHLY", "4.32");
        newTariff(typeId(DEMO_TENANT, "MEMBERSHIP"), "members B", "2027-01-01", "PER_PLOT", "MONTHLY", "1");
        newTariff(typeId(DEMO_TENANT, "MEMBERSHIP"), "Alpha", "2027-01-01", "PER_PLOT", "MONTHLY", "1");
        newTariff(typeId(DEMO_TENANT, "MEMBERSHIP"), "Members A", "2027-01-01", "PER_PLOT", "MONTHLY", "1");

        assertThat(names(body(send(chairman(), get(API), null)))).containsExactly("Alpha", "Members A", "members B",
                "Beta", "Zeta");

        send(chairman(), post("/api/v1/charge-types/" + fence + "/archive"), null).andExpect(status().isNoContent());
        assertThat(names(body(send(chairman(), get(API), null)))).containsExactly("Alpha", "Members A", "members B",
                "Beta");
    }

    @Test
    void thePlotCountOfAllPlotsIsTheActivePlotsWithoutCommonLand() throws Exception {
        newTariff("Members", "2027-01-01", "PER_AREA_SOTKA", "YEARLY", "50");
        newTariff(typeId(DEMO_TENANT, "ELECTRICITY"), "Power", "2027-01-01", "PER_KWH", "MONTHLY", "4.32");
        newTariff(typeId(DEMO_TENANT, "WATER"), "Water", "2027-01-01", "PER_M3", "MONTHLY", "20");
        long chargeable = ownerJdbc().queryForObject("select count(*) from plot where tenant_id = ? and "
                + "archived_at is null and kind <> 'COMMON'", Long.class, DEMO_TENANT);
        long power = metered("ELECTRICITY");
        long water = metered("WATER");
        UUID archived = newPlot("TC-A", "PLOT", true);
        UUID road = newPlot("TC-C", "COMMON", false);
        UUID plot = newPlot("TC-P", "PLOT", false);
        UUID other = newPlot("TC-Q", "PLOT", false);
        // the guards keep meters off archived plots and common land; were one there, it would still not count
        meter(archived, "ELECTRICITY", null);
        meter(road, "ELECTRICITY", null);
        meter(plot, "ELECTRICITY", null);
        meter(other, "ELECTRICITY", "2026-06-30");
        meter(other, "WATER", null);

        JsonNode byName = byName(body(send(chairman(), get(API), null)));

        assertThat(byName.get("Members").get("plotCount").asLong()).isEqualTo(chargeable + 2);
        assertThat(byName.get("Power").get("plotCount").asLong()).as("plots with an active electricity meter")
                .isEqualTo(power + 1);
        assertThat(byName.get("Water").get("plotCount").asLong()).as("plots with an active water meter")
                .isEqualTo(water + 1);
        assertThat(chargeable).isPositive();
    }

    @Test
    void thePlotCountOfSelectedPlotsIsTheActiveSelectedOnesWithoutCommonLand() throws Exception {
        String tariff = newTariff("Chosen", "2027-01-01", "PER_PLOT", "MONTHLY", "10");
        UUID a = newPlot("TS-1", "PLOT", false);
        UUID b = newPlot("TS-2", "PLOT", false);
        UUID gone = newPlot("TS-3", "PLOT", true);
        UUID road = newPlot("TS-4", "COMMON", false);
        UUID excluded = newPlot("TS-5", "PLOT", false);
        JdbcTemplate owner = ownerJdbc();
        owner.update("update tariff set scope_kind = 'SELECTED_PLOTS' where id = ?::uuid", tariff);
        for (UUID plot : List.of(a, b, gone, road)) {
            owner.update("insert into tariff_scope_plot (tenant_id, tariff_id, plot_id, kind) "
                    + "values (?, ?::uuid, ?, 'SELECTED')", DEMO_TENANT, tariff, plot);
        }
        owner.update("insert into tariff_scope_plot (tenant_id, tariff_id, plot_id, kind, reason) "
                + "values (?, ?::uuid, ?, 'EXCLUDED', 'Exempted')", DEMO_TENANT, tariff, excluded);
        newTariff("Empty", "2027-01-01", "PER_PLOT", "MONTHLY", "10");
        owner.update("update tariff set scope_kind = 'SELECTED_PLOTS' where name = 'Empty' and tenant_id = ?",
                DEMO_TENANT);

        JsonNode byName = byName(body(send(chairman(), get(API), null)));

        assertThat(byName.get("Chosen").get("scopeKind").asString()).isEqualTo("SELECTED_PLOTS");
        assertThat(byName.get("Chosen").get("plotCount").asLong()).isEqualTo(2);
        assertThat(byName.get("Empty").get("plotCount").asLong()).isZero();
    }

    @Test
    void theListIsEmptyWithoutTariffs() throws Exception {
        send(chairman(), get(API), null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    // ---- who may do what

    @Test
    void theTreasurerReadsButCannotWrite() throws Exception {
        String tariff = newTariff("Members", "2027-01-01", "PER_PLOT", "MONTHLY", "10");
        String treasurer = demo("treasurer@demo.local");

        send(treasurer, get(API), null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        send(treasurer, get(API + "/" + tariff), null).andExpect(status().isOk());
        denied(treasurer, tariff);
    }

    @Test
    void aMemberAndTheElectricianHaveNoAccessAtAll() throws Exception {
        String tariff = newTariff("Members", "2027-01-01", "PER_PLOT", "MONTHLY", "10");

        for (String email : List.of("member@demo.local", "electrician@demo.local")) {
            String token = demo(email);
            send(token, get(API), null).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            send(token, get(API + "/" + tariff), null).andExpect(status().isForbidden());
            denied(token, tariff);
        }
    }

    @Test
    void thePlatformOperatorHasNoAccess() throws Exception {
        String tariff = newTariff("Members", "2027-01-01", "PER_PLOT", "MONTHLY", "10");
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        send(operator, get(API), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(operator, get(API + "/" + tariff), null).andExpect(status().isForbidden());
        denied(operator, tariff);
    }

    @Test
    void withoutATokenEveryEndpointIsUnauthorised() throws Exception {
        String tariff = newTariff("Members", "2027-01-01", "PER_PLOT", "MONTHLY", "10");
        String first = json.writeValueAsString(create(typeId(DEMO_TENANT, "MEMBERSHIP"), "X",
                version("2027-01-01", "PER_PLOT", "MONTHLY", "1", null, null)));
        String next = json.writeValueAsString(version("2028-01-01", "PER_PLOT", "MONTHLY", "1", null, null));

        mvc.perform(get(API)).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/" + tariff)).andExpect(status().isUnauthorized());
        mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(API + "/" + tariff + "/versions").contentType(MediaType.APPLICATION_JSON).content(next))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aWriterNeedsViewToo() throws Exception {
        String tariff = newTariff("Members", "2027-01-01", "PER_PLOT", "MONTHLY", "10");

        String manageOnly = customRoleToken("TARIFFS_MANAGE");
        send(manageOnly, get(API), null).andExpect(status().isForbidden());
        denied(manageOnly, tariff);

        String viewOnly = customRoleToken("TARIFFS_VIEW");
        send(viewOnly, get(API), null).andExpect(status().isOk());
        denied(viewOnly, tariff);

        String both = customRoleToken("TARIFFS_VIEW", "TARIFFS_MANAGE");
        send(both, post(API + "/" + tariff + "/versions"),
                version("2028-01-01", "PER_PLOT", "MONTHLY", "11", null, null)).andExpect(status().isCreated());
    }

    // ---- helpers

    /** Every write endpoint answers 403 and nothing is written. */
    private void denied(String bearer, String tariff) throws Exception {
        int tariffs = count("tariff");
        int versions = count("tariff_version");
        send(bearer, post(API), create(typeId(DEMO_TENANT, "MEMBERSHIP"), "Denied",
                version("2027-01-01", "PER_PLOT", "MONTHLY", "10", null, null))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(bearer, post(API + "/" + tariff + "/versions"),
                version("2030-01-01", "PER_PLOT", "MONTHLY", "10", null, null)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(count("tariff")).isEqualTo(tariffs);
        assertThat(count("tariff_version")).isEqualTo(versions);
    }

    private String chairman() {
        return demo("chairman@demo.local");
    }

    private static String demo(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }

    /** A token of a chairman of the other cooperative (a fresh member; removed after the test). */
    private String otherChairman() {
        String email = "tariffs-other-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(OTHER_TENANT, email);
        giveBaseRole(OTHER_TENANT, member, "CHAIRMAN");
        return TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), email);
    }

    private ResultActions send(String bearer, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(body instanceof String text ? text : json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static Map<String, Object> create(UUID chargeType, String name, Map<String, Object> firstVersion) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("chargeTypeId", chargeType.toString());
        request.put("name", name);
        request.put("firstVersion", firstVersion);
        return request;
    }

    private static Map<String, Object> version(String validFrom, String basis, String frequency, String rate,
            String dueDate, String note) {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("validFrom", validFrom);
        version.put("basis", basis);
        version.put("frequency", frequency);
        version.put("rate", rate);
        if (dueDate != null) {
            version.put("dueDate", dueDate);
        }
        if (note != null) {
            version.put("note", note);
        }
        return version;
    }

    /** A tariff of the membership fee; returns its id. */
    private String newTariff(String name, String validFrom, String basis, String frequency, String rate)
            throws Exception {
        return newTariff(typeId(DEMO_TENANT, "MEMBERSHIP"), name, validFrom, basis, frequency, rate);
    }

    private String newTariff(UUID type, String name, String validFrom, String basis, String frequency, String rate)
            throws Exception {
        return body(send(chairman(), post(API), create(type, name, version(validFrom, basis, frequency, rate, null,
                null))).andExpect(status().isCreated())).get("id").asString();
    }

    private void addVersion(String tariff, String validFrom, String basis, String frequency, String rate)
            throws Exception {
        send(chairman(), post(API + "/" + tariff + "/versions"), version(validFrom, basis, frequency, rate, null,
                null)).andExpect(status().isCreated());
    }

    private UUID createType(String name) throws Exception {
        String response = send(chairman(), post("/api/v1/charge-types"), Map.of("name", name))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asString());
    }

    private UUID typeId(UUID tenant, String code) {
        return ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? and code = ?", UUID.class,
                tenant, code);
    }

    private int count(String table) {
        return ownerJdbc().queryForObject("select count(*) from " + table + " where tenant_id = ?", Integer.class,
                DEMO_TENANT);
    }

    /** A plot of the demo cooperative (the owner role creates it); removed after the test. */
    private UUID newPlot(String number, String kind, boolean archived) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2, archived_at) "
                + "values (?, ?, ?, ?, ?, case when ? then now() end)", id, DEMO_TENANT, number, kind,
                new BigDecimal("600.00"), archived);
        plots.add(id);
        return id;
    }

    /** A plot meter installed at the start of 2026, active or removed on {@code removedOn}; removed with the plot. */
    private void meter(UUID plot, String resource, String removedOn) {
        ownerJdbc().update("insert into meter (tenant_id, kind, resource, serial, plot_id, installed_on, initial_value, "
                + "removed_on, final_value) select tenant_id, 'PLOT', ?, 'TM-' || number, id, date '2026-01-15', 0, "
                + "?::date, case when ?::date is null then null else 0 end from plot where id = ?", resource,
                removedOn, removedOn, plot);
    }

    /** The active, non-common plots of the demo cooperative with an active meter of the resource. */
    private long metered(String resource) {
        return ownerJdbc().queryForObject("select count(distinct p.id) from plot p join meter m on m.plot_id = p.id "
                + "where p.tenant_id = ? and p.archived_at is null and p.kind <> 'COMMON' and m.resource = ? "
                + "and m.removed_on is null", Long.class, DEMO_TENANT, resource);
    }

    /** The list as an object keyed by tariff name. */
    private JsonNode byName(JsonNode list) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        list.forEach(node -> map.put(node.get("name").asString(), node));
        return json.valueToTree(map);
    }

    private static List<String> names(JsonNode list) {
        List<String> names = new ArrayList<>();
        list.forEach(node -> names.add(node.get("name").asString()));
        return names;
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
}
