package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Archiving a used tariff from an end date ({@code POST /tariffs/{id}/archive}, feature 020) against the ledger's real
 * answer to the port {@code TariffUsage} and the real month run: the end date must be after the start of the last
 * version (400, field {@code endsOn}) and after the last day an unreversed charge covers (409
 * {@code TARIFF_CHARGED_AFTER_END}); a second archive is 409 {@code TARIFF_ARCHIVED}, and so is every version change
 * and scope change of an archived tariff. The list filters by {@code status} and {@code chargeTypeId} and names the
 * plots of each scope; the card shows the end on the tariff and on its last version. The month run charges the months
 * before the end as before and nothing after it. Only {@code TARIFFS_VIEW} with {@code TARIFFS_MANAGE} may archive.
 */
class TariffArchiveTests extends FreshCooperativeSupport {

    private static final String TARIFFS = "/api/v1/tariffs";
    private static final String TARIFF = "/api/v1/tariffs/%s";
    private static final String ARCHIVE = "/api/v1/tariffs/%s/archive";
    private static final String VERSIONS = "/api/v1/tariffs/%s/versions";
    private static final String VERSION = "/api/v1/tariffs/%s/versions/%s";
    private static final String SCOPE = "/api/v1/tariffs/%s/scope";
    private static final String PLOT_TARIFFS = "/api/v1/plots/%s/tariffs?month=%s";

    private UUID plot7;
    private Tariff membership;

    @BeforeEach
    void membershipOfPlot7() {
        plot7 = plot("7", "600.00");
        // 100.00 per plot each month from 1 January 2026
        membership = tariff("MEMBERSHIP", "Членський внесок", "PER_PLOT", "MONTHLY", "100.0000", "2026-01-01");
    }

    // ---- the month run

    @Test
    void aTariffArchivedFromTheFirstOfMarchChargesFebruaryAndNotMarch() throws Exception {
        runMonth("2026-01");
        assertThat(amounts(plot7)).containsExactly("100.00");

        JsonNode archived = body(send(chairman, post(ARCHIVE.formatted(membership.id())), endsOn("2026-03-01"))
                .andExpect(status().isOk()));
        assertThat(archived.get("validUntil").asString()).isEqualTo("2026-02-28");
        assertThat(archived.hasNonNull("archivedAt")).isTrue();

        runMonth("2026-02");
        assertThat(amounts(plot7)).containsExactly("100.00", "100.00");
        Map<String, Object> february = charges(plot7).getLast();
        assertThat(date(february.get("service_from"))).isEqualTo(LocalDate.parse("2026-02-01"));
        assertThat(date(february.get("service_to"))).isEqualTo(LocalDate.parse("2026-02-28"));

        JsonNode march = body(send(chairman, post(RUN.formatted("2026-03", false)), null)
                .andExpect(status().isOk()));
        assertThat(march.get("created").asInt()).isZero();
        assertThat(march.get("notCharged")).isEmpty();
        assertThat(amounts(plot7)).containsExactly("100.00", "100.00");
        assertThat(owner().queryForObject("select count(*) from charge where tariff_id = ? and service_to >= ?",
                Integer.class, membership.id(), LocalDate.parse("2026-03-01"))).isZero();
    }

    @Test
    void anEndInsideAMonthChargesTheDaysUpToIt() throws Exception {
        runMonth("2026-01");
        send(chairman, post(ARCHIVE.formatted(membership.id())), endsOn("2026-02-15")).andExpect(status().isOk());

        runMonth("2026-02");

        // 100.00 x 14 / 28, by days as for a version change
        Map<String, Object> february = charges(plot7).getLast();
        assertThat(february.get("amount")).isEqualTo("50.00");
        assertThat(date(february.get("service_to"))).isEqualTo(LocalDate.parse("2026-02-14"));
    }

    @Test
    void theExpectedAmountsOfAPlotStopAtTheEnd() throws Exception {
        runMonth("2026-01");
        send(chairman, post(ARCHIVE.formatted(membership.id())), endsOn("2026-03-01")).andExpect(status().isOk());

        send(chairman, get(PLOT_TARIFFS.formatted(plot7, "2026-02")), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value("100.00"));
        send(chairman, get(PLOT_TARIFFS.formatted(plot7, "2026-03")), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value("0.00"));
    }

    // ---- validations

    @Test
    void theEndMustBeAfterTheStartOfTheLastVersion() throws Exception {
        version(membership.id(), "PER_PLOT", "MONTHLY", "120.0000", "2026-06-01", null);

        for (String early : List.of("2026-06-01", "2026-05-31", "2026-01-01")) {
            send(chairman, post(ARCHIVE.formatted(membership.id())), endsOn(early))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("endsOn"))
                    .andExpect(jsonPath("$.errors[0].code").value("TARIFF_END_BEFORE_LAST_VERSION"));
        }
        assertNotArchived(membership);

        send(chairman, post(ARCHIVE.formatted(membership.id())), endsOn("2026-06-02")).andExpect(status().isOk())
                .andExpect(jsonPath("$.validUntil").value("2026-06-01"));
    }

    @Test
    void aSoftlyDeletedLastVersionDoesNotCount() throws Exception {
        UUID later = version(membership.id(), "PER_PLOT", "MONTHLY", "120.0000", "2026-06-01", null);
        owner().update("update tariff_version set deleted_at = now() where id = ?", later);

        send(chairman, post(ARCHIVE.formatted(membership.id())), endsOn("2026-04-01")).andExpect(status().isOk())
                .andExpect(jsonPath("$.validUntil").value("2026-03-31"));
    }

    @Test
    void anEndOnOrBeforeTheLastChargedDayIsRefused() throws Exception {
        runMonth("2026-01");
        runMonth("2026-02");

        for (String early : List.of("2026-02-01", "2026-02-15", "2026-02-28")) {
            send(chairman, post(ARCHIVE.formatted(membership.id())), endsOn(early))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TARIFF_CHARGED_AFTER_END"));
        }
        assertNotArchived(membership);

        send(chairman, post(ARCHIVE.formatted(membership.id())), endsOn("2026-03-01")).andExpect(status().isOk());
    }

    @Test
    void aReversedChargeNoLongerHoldsTheEnd() throws Exception {
        runMonth("2026-01");
        runMonth("2026-02");
        UUID february = (UUID) charges(plot7).getLast().get("id");
        send(chairman, post(REVERSE.formatted(february)), Map.of("reason", "Тариф закінчився"))
                .andExpect(status().isCreated());

        send(chairman, post(ARCHIVE.formatted(membership.id())), endsOn("2026-02-01")).andExpect(status().isOk())
                .andExpect(jsonPath("$.validUntil").value("2026-01-31"));
    }

    @Test
    void anArchivedTariffCannotBeArchivedAgain() throws Exception {
        runMonth("2026-01");
        send(chairman, post(ARCHIVE.formatted(membership.id())), endsOn("2026-03-01")).andExpect(status().isOk());

        send(chairman, post(ARCHIVE.formatted(membership.id())), endsOn("2026-04-01"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TARIFF_ARCHIVED"));

        assertThat(owner().queryForObject("select valid_until from tariff where id = ?", LocalDate.class,
                membership.id())).isEqualTo(LocalDate.parse("2026-02-28"));
        await().atMost(Duration.ofSeconds(10)).until(() -> archivedEntries(membership.id()).size() == 1);
    }

    @Test
    void aTariffOfAnotherCooperativeOrAnUnknownIdIsNotFound() throws Exception {
        UUID demoTariff = owner().queryForObject("select id from tariff where tenant_id = ? order by id limit 1",
                UUID.class, DEMO_TENANT);

        send(chairman, post(ARCHIVE.formatted(demoTariff)), endsOn("2099-01-01")).andExpect(status().isNotFound());
        send(chairman, post(ARCHIVE.formatted(UUID.randomUUID())), endsOn("2099-01-01"))
                .andExpect(status().isNotFound());

        assertThat(owner().queryForObject("select archived_at is null from tariff where id = ?", Boolean.class,
                demoTariff)).isTrue();
    }

    @Test
    void aMissingEndIsABadRequest() throws Exception {
        send(chairman, post(ARCHIVE.formatted(membership.id())), Map.of()).andExpect(status().isBadRequest());
        assertNotArchived(membership);
    }

    // ---- frozen

    @Test
    void theVersionsAndTheScopeOfAnArchivedTariffAreFrozen() throws Exception {
        UUID later = version(membership.id(), "PER_PLOT", "MONTHLY", "120.0000", "2026-06-01", null);
        send(chairman, post(ARCHIVE.formatted(membership.id())), endsOn("2026-09-01")).andExpect(status().isOk());

        assertArchivedConflict(send(chairman, post(VERSIONS.formatted(membership.id())),
                versionBody("2026-08-01", "130")));
        assertArchivedConflict(send(chairman, put(VERSION.formatted(membership.id(), later)),
                versionBody("2026-06-01", "125")));
        // a correction of the note alone is refused too: the versions are frozen
        Map<String, Object> noteOnly = versionBody("2026-06-01", "120");
        noteOnly.put("note", "Нова примітка");
        assertArchivedConflict(send(chairman, put(VERSION.formatted(membership.id(), later)), noteOnly));
        assertArchivedConflict(send(chairman, delete(VERSION.formatted(membership.id(), later)), null));
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("kind", "SELECTED_PLOTS");
        scope.put("plotIds", List.of(plot7));
        assertArchivedConflict(send(chairman, put(SCOPE.formatted(membership.id())), scope));

        assertThat(owner().queryForObject("select count(*) from tariff_version where tariff_id = ?", Integer.class,
                membership.id())).isEqualTo(2);
        assertThat(owner().queryForObject("select rate::text from tariff_version where id = ?", String.class, later))
                .isEqualTo("120.0000");
        assertThat(owner().queryForObject("select scope_kind from tariff where id = ?", String.class,
                membership.id())).isEqualTo("ALL_PLOTS");
    }

    // ---- reading

    @Test
    void theCardShowsTheEndOnTheTariffAndOnItsLastVersion() throws Exception {
        version(membership.id(), "PER_PLOT", "MONTHLY", "120.0000", "2026-06-01", null);
        send(chairman, get(TARIFF.formatted(membership.id())), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.validUntil").doesNotExist())
                .andExpect(jsonPath("$.archivedAt").doesNotExist())
                .andExpect(jsonPath("$.versions[0].validTo").doesNotExist());

        send(chairman, post(ARCHIVE.formatted(membership.id())), endsOn("2026-09-01")).andExpect(status().isOk());

        send(chairman, get(TARIFF.formatted(membership.id())), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.validUntil").value("2026-08-31"))
                .andExpect(jsonPath("$.archivedAt").isNotEmpty())
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].validFrom").value("2026-06-01"))
                .andExpect(jsonPath("$.versions[0].validTo").value("2026-08-31"))
                .andExpect(jsonPath("$.versions[1].validTo").value("2026-05-31"));
    }

    @Test
    void theListFiltersByStatusAndChargeType() throws Exception {
        Tariff landTax = tariff("LAND_TAX", "Земельний податок", "PER_AREA_SOTKA", "YEARLY", "35.0000",
                "2026-01-01");
        Tariff road = tariff("MEMBERSHIP", "Дорога", "PER_PLOT", "MONTHLY", "10.0000", "2026-01-01");
        send(chairman, post(ARCHIVE.formatted(road.id())), endsOn("2026-04-01")).andExpect(status().isOk());

        assertThat(ids(TARIFFS)).containsExactlyInAnyOrder(membership.id(), landTax.id());
        assertThat(ids(TARIFFS + "?status=ACTIVE")).containsExactlyInAnyOrder(membership.id(), landTax.id());
        assertThat(ids(TARIFFS + "?status=ARCHIVED")).containsExactly(road.id());
        assertThat(ids(TARIFFS + "?chargeTypeId=" + typeId("MEMBERSHIP"))).containsExactly(membership.id());
        assertThat(ids(TARIFFS + "?status=ARCHIVED&chargeTypeId=" + typeId("MEMBERSHIP"))).containsExactly(road.id());
        assertThat(ids(TARIFFS + "?status=ARCHIVED&chargeTypeId=" + typeId("LAND_TAX"))).isEmpty();
        assertThat(ids(TARIFFS + "?chargeTypeId=" + UUID.randomUUID())).isEmpty();
        send(chairman, get(TARIFFS + "?status=SOMETIMES"), null).andExpect(status().isBadRequest());

        JsonNode archived = body(send(chairman, get(TARIFFS + "?status=ARCHIVED"), null)).get(0);
        assertThat(archived.get("validUntil").asString()).isEqualTo("2026-03-31");
        assertThat(archived.hasNonNull("archivedAt")).isTrue();
        // its last day has passed: the list shows its last version, ending on that day
        assertThat(archived.get("version").get("validTo").asString()).isEqualTo("2026-03-31");
        JsonNode active = body(send(chairman, get(TARIFFS + "?chargeTypeId=" + typeId("MEMBERSHIP")), null)).get(0);
        assertThat(active.hasNonNull("validUntil")).isFalse();
        assertThat(active.hasNonNull("archivedAt")).isFalse();
    }

    @Test
    void theListNamesThePlotsOfEachScopeInNaturalOrder() throws Exception {
        UUID plot12 = plot("12", "600.00");
        UUID plot100 = plot("100", "600.00");
        UUID plot3 = plot("3", "600.00");
        plot("Дорога", "0.00", "COMMON", false);
        plot("5", "600.00", "PLOT", true); // archived
        exclude(membership, plot12);
        Tariff selected = tariff("LAND_TAX", "Обрані", "PER_AREA_SOTKA", "YEARLY", "35.0000", "2026-01-01");
        select(selected, plot100, plot3);
        Tariff metered = tariff("ELECTRICITY", "Світло", "PER_KWH", "MONTHLY", "4.3200", "2026-01-01");

        assertThat(numbers(membership.id())).containsExactly("3", "7", "100");
        assertThat(numbers(selected.id())).containsExactly("3", "100");
        // never narrowed to the plots with a meter
        assertThat(numbers(metered.id())).containsExactly("3", "7", "12", "100");
    }

    @Test
    void aTariffArchivedBeforeItsSuccessorStartsDoesNotConflictWithIt() throws Exception {
        String nextMonth = current().plusMonths(1).atDay(1).toString();
        Tariff successor = tariff("MEMBERSHIP", "Новий членський", "PER_PLOT", "MONTHLY", "110.0000", nextMonth);
        assertThat(listed(membership.id()).get("conflicts")).hasSize(1);

        send(chairman, post(ARCHIVE.formatted(membership.id())), endsOn(nextMonth)).andExpect(status().isOk());

        assertThat(listed(successor.id()).get("conflicts")).isEmpty();
        send(chairman, get(TARIFF.formatted(membership.id())), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts.length()").value(0));
    }

    // ---- audit and who may

    @Test
    void theArchiveIsAuditedWithTheEndDate() throws Exception {
        send(chairman, post(ARCHIVE.formatted(membership.id())), endsOn("2026-03-01")).andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(10)).until(() -> archivedEntries(membership.id()).size() == 1);
        Map<String, Object> entry = archivedEntries(membership.id()).getFirst();
        assertThat(entry.get("entity_type")).isEqualTo("TARIFF");
        assertThat(entry.get("entity_label")).isEqualTo("Членський внесок");
        assertThat(entry.get("actor_member_id")).isNotNull();
        JsonNode changes = json.readTree((String) entry.get("changes"));
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).get("field").asString()).isEqualTo("endsOn");
        assertThat(changes.get(0).get("new").asString()).isEqualTo("2026-03-01");
    }

    @Test
    void onlyTariffsViewWithTariffsManageMayArchive() throws Exception {
        String viewer = customRole("TARIFFS_VIEW");
        String manager = customRole("TARIFFS_VIEW", "TARIFFS_MANAGE");

        for (String denied : List.of(member, treasurer, viewer)) {
            send(denied, post(ARCHIVE.formatted(membership.id())), endsOn("2026-03-01"))
                    .andExpect(status().isForbidden());
        }
        assertNotArchived(membership);
        assertThat(archivedEntries(membership.id())).isEmpty();

        send(manager, post(ARCHIVE.formatted(membership.id())), endsOn("2026-03-01")).andExpect(status().isOk());
        assertThat(owner().queryForObject("select archived_at is not null from tariff where id = ?", Boolean.class,
                membership.id())).isTrue();
    }

    // ---- helpers

    private void runMonth(String month) throws Exception {
        send(chairman, post(RUN.formatted(month, false)), null).andExpect(status().isOk());
    }

    private static Map<String, Object> endsOn(String date) {
        return Map.of("endsOn", date);
    }

    private static Map<String, Object> versionBody(String validFrom, String rate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("validFrom", validFrom);
        body.put("basis", "PER_PLOT");
        body.put("frequency", "MONTHLY");
        body.put("rate", rate);
        return body;
    }

    private static void assertArchivedConflict(ResultActions actions) throws Exception {
        actions.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TARIFF_ARCHIVED"));
    }

    private void assertNotArchived(Tariff tariff) {
        assertThat(owner().queryForObject("select archived_at is null and valid_until is null from tariff "
                + "where id = ?", Boolean.class, tariff.id())).isTrue();
    }

    /** The amounts of the plot's charges in the order they were made. */
    private List<String> amounts(UUID plot) {
        return charges(plot).stream().map(charge -> (String) charge.get("amount")).toList();
    }

    /** The ids in {@code GET} of the list URL, in list order. */
    private List<UUID> ids(String url) throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode summary : body(send(chairman, get(url), null).andExpect(status().isOk()))) {
            ids.add(UUID.fromString(summary.get("id").asString()));
        }
        return ids;
    }

    /** {@code plotNumbers} of the tariff in the active list. */
    private List<String> numbers(UUID tariff) throws Exception {
        List<String> numbers = new ArrayList<>();
        listed(tariff).get("plotNumbers").forEach(number -> numbers.add(number.asString()));
        return numbers;
    }

    /** The tariff in {@code GET /tariffs}. */
    private JsonNode listed(UUID tariff) throws Exception {
        for (JsonNode summary : body(send(chairman, get(TARIFFS), null))) {
            if (summary.get("id").asString().equals(tariff.toString())) {
                return summary;
            }
        }
        throw new AssertionError("the tariff is not in the list");
    }

    private List<Map<String, Object>> archivedEntries(UUID tariff) {
        return owner().queryForList("select entity_type, entity_label, actor_member_id, cast(changes as text) as "
                + "changes from audit_entry where entity_id = ? and action = 'TARIFF_ARCHIVED'", tariff);
    }

    /** A member of the cooperative with one custom role holding exactly these permissions; returns a token. */
    private String customRole(String... permissions) {
        UUID member = UUID.randomUUID();
        String email = "custom-" + member + "@run.test";
        owner().update("insert into member (id, tenant_id, last_name, first_name) values (?, ?, 'Test', 'Custom')",
                member, tenant);
        insertContacts(tenant, member, email);
        UUID role = UUID.randomUUID();
        owner().update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, tenant, "Custom " + role);
        for (String permission : permissions) {
            owner().update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)", tenant,
                    role, permission);
        }
        owner().update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)", tenant, member,
                role);
        return TestTokens.bearer(tenant, "sub-" + email, email);
    }
}
