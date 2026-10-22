package app.nadel.tariffs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import app.nadel.tariffs.TariffVersionUsage;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Correcting and deleting a tariff version ({@code PUT} and {@code DELETE /tariffs/{id}/versions/{versionId}}): what may
 * change, the re-derived end dates of the neighbours, the use check through the {@link TariffVersionUsage} port (here a
 * test bean next to the default one), the refusals, who may do it, other cooperatives, the audit entries and the
 * expected amounts that follow a corrected rate.
 */
class TariffVersionCorrectionTests extends TariffScopeSupport {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Europe/Kyiv"));

    /** A usage that a test switches on for chosen versions, in addition to the ledger's (no charge uses them here). */
    @TestConfiguration(proxyBeanMethods = false)
    static class UsedVersions {

        final Set<UUID> used = ConcurrentHashMap.newKeySet();

        @Bean
        TariffVersionUsage testUsage() {
            return (tenantId, versionId) -> used.contains(versionId);
        }
    }

    @Autowired
    UsedVersions usedVersions;

    @AfterEach
    void forgetUsedVersions() {
        usedVersions.used.clear();
    }

    // ---- correcting

    @Test
    void theRateOfAVersionCanBeCorrected() throws Exception {
        UUID tariff = newTariff("For members");
        UUID version = versionId(tariff, "2026-01-01");

        send(chairman(), put(path(tariff, version)), terms("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "55.5", null,
                null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(version.toString()))
                .andExpect(jsonPath("$.validFrom").value("2026-01-01"))
                .andExpect(jsonPath("$.validTo").doesNotExist())
                .andExpect(jsonPath("$.rate").value("55.5000"))
                .andExpect(jsonPath("$.pastDated").value(true));

        assertThat(storedRate(version)).isEqualByComparingTo("55.5");
        assertThat(versionCount(tariff)).isEqualTo(1);
    }

    @Test
    void theStartDateTheBasisAndTheNoteCanBeCorrected() throws Exception {
        UUID tariff = newTariff("For members");
        UUID version = versionId(tariff, "2026-01-01");

        send(chairman(), put(path(tariff, version)), terms("2027-03-01", "PER_PLOT", "MONTHLY", "10", null,
                "  Decision of 12.04.2026 ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.validFrom").value("2027-03-01"))
                .andExpect(jsonPath("$.basis").value("PER_PLOT"))
                .andExpect(jsonPath("$.frequency").value("MONTHLY"))
                .andExpect(jsonPath("$.rate").value("10.0000"))
                .andExpect(jsonPath("$.note").value("Decision of 12.04.2026"));
        // a blank note clears it
        send(chairman(), put(path(tariff, version)), terms("2027-03-01", "PER_PLOT", "MONTHLY", "10", null, "   "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.note").doesNotExist());

        assertThat(ownerJdbc().queryForObject("select note from tariff_version where id = ?", String.class, version))
                .isNull();
    }

    @Test
    void aVersionCanBecomeAOneTimeAmountAndBackWithTheDueDate() throws Exception {
        UUID tariff = newTariff("For members");
        UUID version = versionId(tariff, "2026-01-01");

        send(chairman(), put(path(tariff, version)), terms("2026-01-01", "FIXED_ONE_TIME", "ONE_OFF", "1200", "2026-12-01",
                null)).andExpect(status().isOk()).andExpect(jsonPath("$.basis").value("FIXED_ONE_TIME"))
                .andExpect(jsonPath("$.frequency").value("ONE_OFF"))
                .andExpect(jsonPath("$.dueDate").value("2026-12-01"));
        send(chairman(), put(path(tariff, version)), terms("2026-01-01", "PER_PLOT", "YEARLY", "1200", null, null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dueDate").doesNotExist());

        assertThat(ownerJdbc().queryForObject("select due_date from tariff_version where id = ?", java.sql.Date.class,
                version)).isNull();
    }

    @Test
    void aRequestThatChangesNothingWritesNothingAndNoAuditEntry() throws Exception {
        UUID tariff = newTariff("For members");
        UUID version = versionId(tariff, "2026-01-01");

        // the stored rate has four decimals; "50" and "50.0000" are the same rate
        send(chairman(), put(path(tariff, version)), terms("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "50.00", null,
                null)).andExpect(status().isOk()).andExpect(jsonPath("$.rate").value("50.0000"));
        send(chairman(), put(path(tariff, version)), terms("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "50", null, null))
                .andExpect(status().isOk());
        // a real change afterwards proves the earlier events would have been written by now
        send(chairman(), put(path(tariff, version)), terms("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "51", null, null))
                .andExpect(status().isOk());

        assertThat(awaitEntries(tariff, "TARIFF_VERSION_UPDATED", 1)).hasSize(1);
        assertThat(changes(entries(tariff, "TARIFF_VERSION_UPDATED").getFirst()))
                .containsExactly(new Change("rate", "50.0000", "51.0000"));
    }

    @Test
    void anUnchangedRequestOnAVersionInUseIsRefusedLikeAnyOther() throws Exception {
        UUID tariff = newTariff("For members");
        UUID version = versionId(tariff, "2026-01-01");
        usedVersions.used.add(version);

        send(chairman(), put(path(tariff, version)), terms("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "50", null, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TARIFF_VERSION_IN_USE"));
    }

    @Test
    void aDateThatAnotherVersionHasIsRefusedAndNothingChanges() throws Exception {
        UUID tariff = newTariff("For members");
        UUID later = addVersion(tariff, "2027-01-01", "60");

        send(chairman(), put(path(tariff, later)), terms("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "99", null, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TARIFF_VERSION_DATE_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("validFrom"))
                .andExpect(jsonPath("$.errors[0].code").value("TARIFF_VERSION_DATE_TAKEN"));

        assertThat(storedRate(later)).isEqualByComparingTo("60");
        assertThat(ownerJdbc().queryForObject("select valid_from::text from tariff_version where id = ?",
                String.class, later)).isEqualTo("2027-01-01");
        // keeping its own date is not a clash
        send(chairman(), put(path(tariff, later)), terms("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "61", null, null))
                .andExpect(status().isOk());
        // a free date is fine
        send(chairman(), put(path(tariff, later)), terms("2026-06-01", "PER_AREA_SOTKA", "YEARLY", "61", null, null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.validFrom").value("2026-06-01"));
    }

    @Test
    void theSameFieldProblemsAsOnCreateAreRefused() throws Exception {
        UUID tariff = newTariff("For members");
        UUID version = versionId(tariff, "2026-01-01");

        send(chairman(), put(path(tariff, version)), terms("2026-01-01", "PER_PLOT", "YEARLY", "4.12345", null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("rate"))
                .andExpect(jsonPath("$.errors[0].code").value("RATE_INVALID"));
        send(chairman(), put(path(tariff, version)), terms("2026-01-01", "PER_PLOT", "YEARLY", "-1", null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("RATE_INVALID"));
        send(chairman(), put(path(tariff, version)), terms("2026-01-01", "FIXED_ONE_TIME", "ONE_OFF", "5", null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("dueDate"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(chairman(), put(path(tariff, version)), terms("2026-01-01", "PER_PLOT", "YEARLY", "5", "2026-12-01", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("dueDate"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        send(chairman(), put(path(tariff, version)), terms("2026-01-01", "FIXED_ONE_TIME", "MONTHLY", "5", "2026-12-01",
                null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("frequency"))
                .andExpect(jsonPath("$.errors[0].code").value("FREQUENCY_BASIS_MISMATCH"));
        send(chairman(), put(path(tariff, version)), terms("2026-01-01", "PER_PLOT", "ONE_OFF", "5", null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("frequency"));
        send(chairman(), put(path(tariff, version)), terms("2026-01-01", "PER_PLOT", "YEARLY", "5", null,
                "x".repeat(501))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field")
                .value("note"));
        send(chairman(), put(path(tariff, version)), Map.of("basis", "PER_PLOT")).andExpect(status().isBadRequest());

        assertThat(storedRate(version)).isEqualByComparingTo("50");
        assertThat(awaitNoEntries(tariff, "TARIFF_VERSION_UPDATED")).isEmpty();
    }

    @Test
    void aDateInThePastIsAcceptedAndFlagged() throws Exception {
        UUID tariff = newTariff("For members");
        UUID version = addVersion(tariff, TODAY.plusDays(30).toString(), "60");

        send(chairman(), put(path(tariff, version)), terms(TODAY.minusDays(1).toString(), "PER_AREA_SOTKA", "YEARLY",
                "60", null, null)).andExpect(status().isOk()).andExpect(jsonPath("$.pastDated").value(true));
        send(chairman(), put(path(tariff, version)), terms(TODAY.toString(), "PER_AREA_SOTKA", "YEARLY", "60", null,
                null)).andExpect(status().isOk()).andExpect(jsonPath("$.pastDated").value(false));
        send(chairman(), put(path(tariff, version)), terms(TODAY.plusDays(2).toString(), "PER_AREA_SOTKA", "YEARLY",
                "60", null, null)).andExpect(status().isOk()).andExpect(jsonPath("$.pastDated").value(false));
    }

    @Test
    void theEndDatesOfTheNeighboursFollowACorrectedStartDate() throws Exception {
        UUID tariff = newTariff("For members"); // from 2026-01-01
        UUID middle = addVersion(tariff, "2027-07-01", "60");
        addVersion(tariff, "2028-01-01", "70");

        send(chairman(), put(path(tariff, middle)), terms("2027-09-01", "PER_AREA_SOTKA", "YEARLY", "60", null, null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.validFrom").value("2027-09-01"))
                .andExpect(jsonPath("$.validTo").value("2027-12-31"));

        send(chairman(), get(API + "/" + tariff), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.versions", hasSize(3)))
                .andExpect(jsonPath("$.versions[0].validFrom").value("2028-01-01"))
                .andExpect(jsonPath("$.versions[0].validTo").doesNotExist())
                .andExpect(jsonPath("$.versions[1].validFrom").value("2027-09-01"))
                .andExpect(jsonPath("$.versions[1].validTo").value("2027-12-31"))
                .andExpect(jsonPath("$.versions[2].validFrom").value("2026-01-01"))
                .andExpect(jsonPath("$.versions[2].validTo").value("2027-08-31"));
    }

    @Test
    void aVersionMovedPastItsNeighbourChangesTheOrder() throws Exception {
        UUID tariff = newTariff("For members"); // from 2026-01-01
        UUID first = versionId(tariff, "2026-01-01");
        addVersion(tariff, "2027-01-01", "60");

        send(chairman(), put(path(tariff, first)), terms("2028-01-01", "PER_AREA_SOTKA", "YEARLY", "50", null, null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.validTo").doesNotExist());

        send(chairman(), get(API + "/" + tariff), null)
                .andExpect(jsonPath("$.versions[0].validFrom").value("2028-01-01"))
                .andExpect(jsonPath("$.versions[1].validFrom").value("2027-01-01"))
                .andExpect(jsonPath("$.versions[1].validTo").value("2027-12-31"));
    }

    @Test
    void theExpectedAmountOfAPlotFollowsACorrectedRate() throws Exception {
        UUID tariff = newTariff("For members"); // 50 per sotka yearly from 2026-01-01
        UUID plot = newPlot("VC-1", "PLOT", false); // 600 m2: 50 x 6 / 12 = 25.00
        send(chairman(), get("/api/v1/plots/" + plot + "/tariffs?month=2026-11"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].amount").value("25.00"));

        send(chairman(), put(path(tariff, versionId(tariff, "2026-01-01"))),
                terms("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "60", null, null)).andExpect(status().isOk());

        send(chairman(), get("/api/v1/plots/" + plot + "/tariffs?month=2026-11"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].rate").value("60.0000"))
                .andExpect(jsonPath("$.items[0].amount").value("30.00"))
                .andExpect(jsonPath("$.total").value("30.00"));
    }

    // ---- deleting

    @Test
    void aMiddleVersionCanBeDeletedAndItsNeighbourLastsLonger() throws Exception {
        UUID tariff = newTariff("For members"); // from 2026-01-01
        UUID middle = addVersion(tariff, "2027-07-01", "60");
        addVersion(tariff, "2028-01-01", "70");

        send(chairman(), delete(path(tariff, middle)), null).andExpect(status().isNoContent());

        assertThat(versionCount(tariff)).isEqualTo(2);
        assertThat(ownerJdbc().queryForObject("select count(*) from tariff_version where id = ?", Integer.class,
                middle)).isZero();
        send(chairman(), get(API + "/" + tariff), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.versions", hasSize(2)))
                .andExpect(jsonPath("$.versions[0].validFrom").value("2028-01-01"))
                .andExpect(jsonPath("$.versions[1].validFrom").value("2026-01-01"))
                .andExpect(jsonPath("$.versions[1].validTo").value("2027-12-31"));
        // the deleted version is gone for good
        send(chairman(), delete(path(tariff, middle)), null).andExpect(status().isNotFound());
    }

    @Test
    void theLatestVersionCanBeDeletedAndItsNeighbourThenHasNoEnd() throws Exception {
        UUID tariff = newTariff("For members");
        UUID latest = addVersion(tariff, "2027-07-01", "60");

        send(chairman(), delete(path(tariff, latest)), null).andExpect(status().isNoContent());

        send(chairman(), get(API + "/" + tariff), null).andExpect(jsonPath("$.versions", hasSize(1)))
                .andExpect(jsonPath("$.versions[0].validTo").doesNotExist());
    }

    @Test
    void theOnlyVersionOfATariffCannotBeDeleted() throws Exception {
        UUID tariff = newTariff("For members");
        UUID only = versionId(tariff, "2026-01-01");

        send(chairman(), delete(path(tariff, only)), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TARIFF_LAST_VERSION"));

        assertThat(versionCount(tariff)).isEqualTo(1);
        assertThat(awaitNoEntries(tariff, "TARIFF_VERSION_DELETED")).isEmpty();
    }

    @Test
    void afterDeletingAllButOneTheRemainingVersionIsProtected() throws Exception {
        UUID tariff = newTariff("For members");
        UUID first = versionId(tariff, "2026-01-01");
        UUID second = addVersion(tariff, "2027-01-01", "60");

        send(chairman(), delete(path(tariff, second)), null).andExpect(status().isNoContent());
        send(chairman(), delete(path(tariff, first)), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TARIFF_LAST_VERSION"));
    }

    // ---- a version that a charge uses

    @Test
    void aVersionInUseCanBeNeitherCorrectedNorDeleted() throws Exception {
        UUID tariff = newTariff("For members");
        UUID used = addVersion(tariff, "2027-01-01", "60");
        UUID free = versionId(tariff, "2026-01-01");
        usedVersions.used.add(used);

        send(chairman(), put(path(tariff, used)), terms("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "99", null, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TARIFF_VERSION_IN_USE"));
        send(chairman(), delete(path(tariff, used)), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TARIFF_VERSION_IN_USE"));

        assertThat(storedRate(used)).isEqualByComparingTo("60");
        assertThat(versionCount(tariff)).isEqualTo(2);
        // the other version of the same tariff is still free
        send(chairman(), put(path(tariff, free)), terms("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "51", null, null))
                .andExpect(status().isOk());
        // and a new version can always be added: it is a new one, not a correction
        addVersion(tariff, "2028-01-01", "70");
        assertThat(awaitNoEntries(tariff, "TARIFF_VERSION_DELETED")).isEmpty();
    }

    @Test
    void aVersionInUseIsRefusedAlsoWhenItIsTheOnlyOne() throws Exception {
        UUID tariff = newTariff("For members");
        UUID only = versionId(tariff, "2026-01-01");
        usedVersions.used.add(only);

        send(chairman(), delete(path(tariff, only)), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TARIFF_VERSION_IN_USE"));
    }

    // ---- charge types

    @Test
    void aVersionOfATariffUnderAnArchivedChargeTypeCannotBeChanged() throws Exception {
        UUID type = createType("Fence");
        UUID tariff = newTariff(type, "Fence", "PER_PLOT", "MONTHLY");
        UUID second = addVersion(tariff, "2027-01-01", "60");
        send(chairman(), post("/api/v1/charge-types/" + type + "/archive"), null).andExpect(status().isNoContent());

        send(chairman(), put(path(tariff, second)), terms("2027-01-01", "PER_PLOT", "MONTHLY", "99", null, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHARGE_TYPE_ARCHIVED"));
        send(chairman(), delete(path(tariff, second)), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_TYPE_ARCHIVED"));

        assertThat(versionCount(tariff)).isEqualTo(2);
        assertThat(storedRate(second)).isEqualByComparingTo("60");
    }

    // ---- who may do it

    @Test
    void theChairmanMayAndEveryoneElseIsRefused() throws Exception {
        UUID tariff = newTariff("For members");
        UUID version = addVersion(tariff, "2027-01-01", "60");
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();
        List<String> refused = List.of(demo("treasurer@demo.local"), demo("member@demo.local"),
                demo("electrician@demo.local"), operator, customRoleToken("TARIFFS_VIEW"),
                customRoleToken("TARIFFS_MANAGE"), customRoleToken("AUDIT_VIEW"));

        for (String token : refused) {
            send(token, put(path(tariff, version)), terms("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "99", null, null))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            send(token, delete(path(tariff, version)), null).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        assertThat(storedRate(version)).isEqualByComparingTo("60");
        assertThat(versionCount(tariff)).isEqualTo(2);

        // an anonymous caller is not signed in
        mvc.perform(put(path(tariff, version)).contentType("application/json")
                .content(json.writeValueAsString(terms("2027-01-01", "PER_PLOT", "YEARLY", "1", null, null))))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(path(tariff, version))).andExpect(status().isUnauthorized());

        String both = customRoleToken("TARIFFS_VIEW", "TARIFFS_MANAGE");
        send(both, put(path(tariff, version)), terms("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "61", null, null))
                .andExpect(status().isOk());
        send(both, delete(path(tariff, version)), null).andExpect(status().isNoContent());
        assertThat(versionCount(tariff)).isEqualTo(1);
    }

    @Test
    void aTariffOrVersionOfAnotherCooperativeIsNotFound() throws Exception {
        UUID tariff = newTariff("For members");
        UUID version = addVersion(tariff, "2027-01-01", "60");
        String foreign = otherChairman();

        send(foreign, put(path(tariff, version)), terms("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "99", null, null))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(foreign, delete(path(tariff, version)), null).andExpect(status().isNotFound());

        assertThat(storedRate(version)).isEqualByComparingTo("60");
        assertThat(versionCount(tariff)).isEqualTo(2);
    }

    @Test
    void aVersionOfAnotherTariffOrAnUnknownIdIsNotFound() throws Exception {
        UUID tariff = newTariff("For members");
        UUID elsewhere = newTariff("Non-members");
        UUID version = addVersion(tariff, "2027-01-01", "60");
        UUID foreignVersion = versionId(elsewhere, "2026-01-01");

        send(chairman(), put(path(elsewhere, version)), terms("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "99", null,
                null)).andExpect(status().isNotFound());
        send(chairman(), delete(path(elsewhere, version)), null).andExpect(status().isNotFound());
        send(chairman(), put(path(tariff, foreignVersion)), terms("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "99", null,
                null)).andExpect(status().isNotFound());
        send(chairman(), put(path(tariff, UUID.randomUUID())), terms("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "99",
                null, null)).andExpect(status().isNotFound());
        send(chairman(), put(path(UUID.randomUUID(), version)), terms("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "99",
                null, null)).andExpect(status().isNotFound());
        send(chairman(), delete(path(tariff, UUID.randomUUID())), null).andExpect(status().isNotFound());
        send(chairman(), delete(path(UUID.randomUUID(), version)), null).andExpect(status().isNotFound());

        assertThat(storedRate(version)).isEqualByComparingTo("60");
        assertThat(storedRate(foreignVersion)).isEqualByComparingTo("50");
        assertThat(versionCount(tariff)).isEqualTo(2);
        assertThat(versionCount(elsewhere)).isEqualTo(1);
    }

    @Test
    void theCooperativeComesFromTheTokenNotTheBody() throws Exception {
        UUID tariff = newTariff("For members");
        UUID version = versionId(tariff, "2026-01-01");
        Map<String, Object> request = terms("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "52", null, null);
        request.put("tenantId", OTHER_TENANT.toString());

        send(chairman(), put(path(tariff, version)), request).andExpect(status().isOk());

        assertThat(ownerJdbc().queryForObject("select tenant_id from tariff_version where id = ?", UUID.class,
                version)).isEqualTo(DEMO_TENANT);
    }

    // ---- audit

    @Test
    void aCorrectionIsAuditedWithTheChangedFieldsOnly() throws Exception {
        UUID tariff = newTariff("For members");
        UUID version = versionId(tariff, "2026-01-01");

        send(chairman(), put(path(tariff, version)), terms("2026-02-01", "PER_AREA_SOTKA", "YEARLY", "55", null,
                "Corrected")).andExpect(status().isOk());

        Map<String, Object> entry = awaitEntries(tariff, "TARIFF_VERSION_UPDATED", 1).getFirst();
        assertThat(entry.get("entity_type")).isEqualTo("TARIFF");
        assertThat(entry.get("entity_label")).isEqualTo("For members");
        assertThat(entry.get("actor_member_id")).isNotNull();
        assertThat(entry.get("tenant_id")).isEqualTo(DEMO_TENANT);
        assertThat(changes(entry)).containsExactly(new Change("validFrom", "2026-01-01", "2026-02-01"),
                new Change("rate", "50.0000", "55.0000"), new Change("note", null, "Corrected"));
    }

    @Test
    void aCorrectionAuditsABasisChangeWithItsFrequencyAndDueDate() throws Exception {
        UUID tariff = newTariff("For members");
        UUID version = versionId(tariff, "2026-01-01");

        send(chairman(), put(path(tariff, version)), terms("2026-01-01", "FIXED_ONE_TIME", "ONE_OFF", "50", "2026-12-01",
                null)).andExpect(status().isOk());
        send(chairman(), put(path(tariff, version)), terms("2026-01-01", "PER_PLOT", "YEARLY", "50", null, null))
                .andExpect(status().isOk());

        List<Map<String, Object>> updated = awaitEntries(tariff, "TARIFF_VERSION_UPDATED", 2);
        assertThat(changes(updated.get(0))).containsExactly(new Change("basis", "PER_AREA_SOTKA", "FIXED_ONE_TIME"),
                new Change("frequency", "YEARLY", "ONE_OFF"), new Change("dueDate", null, "2026-12-01"));
        assertThat(changes(updated.get(1))).containsExactly(new Change("basis", "FIXED_ONE_TIME", "PER_PLOT"),
                new Change("frequency", "ONE_OFF", "YEARLY"), new Change("dueDate", "2026-12-01", null));
    }

    @Test
    void aDeletionIsAuditedWithTheOldValues() throws Exception {
        UUID tariff = newTariff("For members");
        UUID version = addVersion(tariff, "2027-01-01", "60", "General meeting");

        send(chairman(), delete(path(tariff, version)), null).andExpect(status().isNoContent());

        Map<String, Object> entry = awaitEntries(tariff, "TARIFF_VERSION_DELETED", 1).getFirst();
        assertThat(entry.get("entity_type")).isEqualTo("TARIFF");
        assertThat(entry.get("entity_label")).isEqualTo("For members");
        assertThat(entry.get("actor_member_id")).isNotNull();
        assertThat(changes(entry)).containsExactly(new Change("validFrom", "2027-01-01", null),
                new Change("basis", "PER_AREA_SOTKA", null), new Change("frequency", "YEARLY", null),
                new Change("rate", "60.0000", null), new Change("note", "General meeting", null));
    }

    @Test
    void aRefusedChangeWritesNoAuditEntry() throws Exception {
        UUID tariff = newTariff("For members");
        UUID used = addVersion(tariff, "2027-01-01", "60");
        usedVersions.used.add(used);

        send(chairman(), put(path(tariff, used)), terms("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "1", null, null))
                .andExpect(status().isConflict());
        send(chairman(), delete(path(tariff, used)), null).andExpect(status().isConflict());
        send(treasurerToken(), delete(path(tariff, used)), null).andExpect(status().isForbidden());
        usedVersions.used.clear();
        // a real deletion afterwards proves the earlier events would have been written by now
        send(chairman(), delete(path(tariff, used)), null).andExpect(status().isNoContent());

        assertThat(awaitEntries(tariff, "TARIFF_VERSION_DELETED", 1)).hasSize(1);
        assertThat(entries(tariff, "TARIFF_VERSION_UPDATED")).isEmpty();
    }

    // ---- helpers

    private static String path(UUID tariff, UUID version) {
        return API + "/" + tariff + "/versions/" + version;
    }

    private String treasurerToken() {
        return demo("treasurer@demo.local");
    }

    private static Map<String, Object> terms(String validFrom, String basis, String frequency, String rate,
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

    private UUID addVersion(UUID tariff, String validFrom, String rate) throws Exception {
        return addVersion(tariff, validFrom, rate, null);
    }

    /** Adds a per-sotka yearly version through the API; returns its id. */
    private UUID addVersion(UUID tariff, String validFrom, String rate, String note) throws Exception {
        MockHttpServletRequestBuilder request = post(API + "/" + tariff + "/versions");
        JsonNode created = body(send(chairman(), request, terms(validFrom, "PER_AREA_SOTKA", "YEARLY", rate, null,
                note)).andExpect(status().isCreated()));
        return UUID.fromString(created.get("id").asString());
    }

    private UUID versionId(UUID tariff, String validFrom) {
        return ownerJdbc().queryForObject("select id from tariff_version where tariff_id = ? and valid_from = "
                + "?::date", UUID.class, tariff, validFrom);
    }

    private BigDecimal storedRate(UUID version) {
        return ownerJdbc().queryForObject("select rate from tariff_version where id = ?", BigDecimal.class, version);
    }

    private int versionCount(UUID tariff) {
        return ownerJdbc().queryForObject("select count(*) from tariff_version where tariff_id = ?", Integer.class,
                tariff);
    }

    private UUID createType(String name) throws Exception {
        JsonNode created = body(send(chairman(), post("/api/v1/charge-types"), Map.of("name", name))
                .andExpect(status().isCreated()));
        return UUID.fromString(created.get("id").asString());
    }

    /** Waits for the asynchronous audit write of {@code expected} entries of that action for the tariff. */
    private List<Map<String, Object>> awaitEntries(UUID tariff, String action, int expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertThat(entries(tariff, action)).hasSize(expected));
        return entries(tariff, action);
    }

    /**
     * The entries of that action once the creation of the tariff has been written (the audit write is asynchronous and
     * in order, so a refused change has had time to show up by then); expected to stay empty.
     */
    private List<Map<String, Object>> awaitNoEntries(UUID tariff, String action) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertThat(entries(tariff, "TARIFF_CREATED")).hasSize(1));
        return entries(tariff, action);
    }

    private List<Map<String, Object>> entries(UUID tariff, String action) {
        return ownerJdbc().queryForList("select tenant_id, action, entity_type, entity_label, actor_member_id, "
                + "cast(changes as text) as changes from audit_entry where entity_id = ? and action = ? "
                + "order by occurred_at, id", tariff, action);
    }

    private List<Change> changes(Map<String, Object> entry) {
        JsonNode node = json.readTree((String) entry.get("changes"));
        List<Change> result = new ArrayList<>();
        node.forEach(change -> result.add(new Change(change.get("field").asString(),
                change.has("old") && !change.get("old").isNull() ? change.get("old").asString() : null,
                change.has("new") && !change.get("new").isNull() ? change.get("new").asString() : null)));
        return result;
    }

    private record Change(String field, String oldValue, String newValue) {
    }
}
