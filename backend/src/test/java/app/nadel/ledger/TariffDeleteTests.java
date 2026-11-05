package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Deleting a tariff entered by mistake ({@code DELETE /tariffs/{id}}, feature 020) against the ledger's real answer to
 * the port {@code TariffUsage}: a tariff no charge was ever made from goes with its versions (softly deleted ones too)
 * and its scope rows and leaves a {@code TARIFF_DELETED} audit entry; a tariff the month run charged, whose charges were
 * reversed since, or one of whose softly deleted versions prices a charge part, is refused with 409
 * {@code TARIFF_IN_USE} and nothing changes. {@code deletable} in the list and on the card says the same. Only
 * {@code TARIFFS_VIEW} with {@code TARIFFS_MANAGE} may delete; a tariff of another cooperative is 404.
 */
class TariffDeleteTests extends FreshCooperativeSupport {

    private static final String TARIFFS = "/api/v1/tariffs";
    private static final String TARIFF = "/api/v1/tariffs/%s";

    private UUID plot7;
    private Tariff membership;

    @BeforeEach
    void membershipOfPlot7() {
        plot7 = plot("7", "600.00");
        membership = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "50.0000", "2026-01-01");
    }

    // ---- an unused tariff

    @Test
    void anUnusedTariffIsDeletedWithEveryVersionAndItsScope() throws Exception {
        version(membership.id(), "PER_AREA_SOTKA", "YEARLY", "60.0000", "2099-01-01", null);
        UUID softlyDeleted = version(membership.id(), "PER_AREA_SOTKA", "YEARLY", "55.0000", "2098-01-01", null);
        owner().update("update tariff_version set deleted_at = now() where id = ?", softlyDeleted);
        exclude(membership, plot7);

        send(chairman, get(TARIFF.formatted(membership.id())), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.deletable").value(true));
        assertThat(listed(membership.id()).get("deletable").asBoolean()).isTrue();

        send(chairman, delete(TARIFF.formatted(membership.id())), null).andExpect(status().isNoContent());

        assertThat(rows("tariff", "id", membership.id())).isZero();
        assertThat(rows("tariff_version", "tariff_id", membership.id())).isZero();
        assertThat(rows("tariff_scope_plot", "tariff_id", membership.id())).isZero();
        send(chairman, get(TARIFF.formatted(membership.id())), null).andExpect(status().isNotFound());
        assertThat(body(send(chairman, get(TARIFFS), null))).isEmpty();

        await().atMost(Duration.ofSeconds(10)).until(() -> deletedEntries(membership.id()).size() == 1);
        Map<String, Object> entry = deletedEntries(membership.id()).getFirst();
        assertThat(entry.get("entity_type")).isEqualTo("TARIFF");
        assertThat(entry.get("entity_label")).isEqualTo("Членський внесок");
        assertThat(entry.get("actor_member_id")).isNotNull();
        JsonNode changes = json.readTree((String) entry.get("changes"));
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).get("field").asString()).isEqualTo("name");
        assertThat(changes.get(0).get("old").asString()).isEqualTo("Членський внесок");
    }

    // ---- used tariffs

    @Test
    void aTariffTheMonthRunChargedFromIsRefused() throws Exception {
        runMonth();
        assertThat(charges(plot7)).isNotEmpty();
        // a tariff added after the run is charged from by nothing: the batched answer of the list tells them apart
        Tariff later = tariff("LAND_TAX", "Земельний податок", "PER_AREA_SOTKA", "YEARLY", "10.0000", "2099-01-01");

        send(chairman, get(TARIFF.formatted(membership.id())), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.deletable").value(false));
        assertThat(listed(membership.id()).get("deletable").asBoolean()).isFalse();
        assertThat(listed(later.id()).get("deletable").asBoolean()).isTrue();

        assertRefused(membership);
    }

    @Test
    void aTariffWhoseChargesWereReversedIsStillRefused() throws Exception {
        runMonth();
        UUID charge = (UUID) charges(plot7).getFirst().get("id");
        send(chairman, post(REVERSE.formatted(charge)), Map.of("reason", "Помилковий тариф"))
                .andExpect(status().isCreated());

        send(chairman, get(TARIFF.formatted(membership.id())), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.deletable").value(false));
        assertThat(listed(membership.id()).get("deletable").asBoolean()).isFalse();

        assertRefused(membership);
    }

    @Test
    void aChargePartOfASoftlyDeletedVersionRefusesTheDelete() throws Exception {
        Tariff losses = tariff("ELECTRICITY", "Втрати", "PER_KWH", "MONTHLY", "4.3200", "2026-01-01");
        UUID used = version(losses.id(), "PER_KWH", "MONTHLY", "4.5000", "2026-06-01", null);
        // a charge that does not name the tariff (a losses share), priced by the later version only
        UUID charge = UUID.randomUUID();
        owner().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, 'REGULAR', "
                + "'LOSSES', 45.00, date '2026-06-01', date '2026-06-30', date '2026-06-30', ?)", charge, tenant,
                plot7, typeId("ELECTRICITY"), monthId("2026-06"), "TEST:" + charge);
        owner().update("insert into charge_part (tenant_id, charge_id, tariff_version_id, days, units_num, units_den, "
                + "rate) values (?, ?, ?, 30, 10, 1, 4.5000)", tenant, charge, used);
        // a recalculation took the version away softly
        owner().update("update tariff_version set deleted_at = now() where id = ?", used);

        send(chairman, get(TARIFF.formatted(losses.id())), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.deletable").value(false))
                .andExpect(jsonPath("$.versions.length()").value(1));
        assertThat(listed(losses.id()).get("deletable").asBoolean()).isFalse();
        // the tariff the charge does not touch stays deletable
        assertThat(listed(membership.id()).get("deletable").asBoolean()).isTrue();

        assertRefused(losses);
        assertThat(rows("tariff_version", "id", used)).isOne();
    }

    // ---- who may delete, and whose tariff

    @Test
    void onlyTariffsViewWithTariffsManageMayDelete() throws Exception {
        String viewer = customRole("TARIFFS_VIEW");
        String manager = customRole("TARIFFS_VIEW", "TARIFFS_MANAGE");

        for (String denied : List.of(member, treasurer, viewer)) {
            send(denied, delete(TARIFF.formatted(membership.id())), null).andExpect(status().isForbidden());
        }
        assertThat(rows("tariff", "id", membership.id())).isOne();

        send(manager, delete(TARIFF.formatted(membership.id())), null).andExpect(status().isNoContent());
        assertThat(rows("tariff", "id", membership.id())).isZero();
    }

    @Test
    void aTariffOfAnotherCooperativeOrAnUnknownIdIsNotFound() throws Exception {
        UUID demoTariff = owner().queryForObject("select id from tariff where tenant_id = ? order by id limit 1",
                UUID.class, DEMO_TENANT);

        send(chairman, delete(TARIFF.formatted(demoTariff)), null).andExpect(status().isNotFound());
        send(chairman, delete(TARIFF.formatted(UUID.randomUUID())), null).andExpect(status().isNotFound());

        assertThat(rows("tariff", "id", demoTariff)).isOne();
    }

    // ---- helpers

    private void runMonth() throws Exception {
        send(chairman, post(RUN.formatted(ym(0), false)), null).andExpect(status().isOk());
    }

    /** 409 {@code TARIFF_IN_USE}; the tariff and its versions stay and no {@code TARIFF_DELETED} entry is written. */
    private void assertRefused(Tariff tariff) throws Exception {
        int versions = rows("tariff_version", "tariff_id", tariff.id());

        send(chairman, delete(TARIFF.formatted(tariff.id())), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TARIFF_IN_USE"));

        assertThat(rows("tariff", "id", tariff.id())).isOne();
        assertThat(rows("tariff_version", "tariff_id", tariff.id())).isEqualTo(versions);
        assertThat(deletedEntries(tariff.id())).isEmpty();
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

    private int rows(String table, String column, UUID id) {
        return owner().queryForObject("select count(*) from " + table + " where " + column + " = ?", Integer.class,
                id);
    }

    private List<Map<String, Object>> deletedEntries(UUID tariff) {
        return owner().queryForList("select entity_type, entity_label, actor_member_id, cast(changes as text) as "
                + "changes from audit_entry where entity_id = ? and action = 'TARIFF_DELETED'", tariff);
    }

    /** The id of the billing month (YYYY-MM) of the cooperative, opening it as the database owner. */
    private UUID monthId(String month) {
        owner().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month + "-01");
        return owner().queryForObject("select id from billing_month where tenant_id = ? and month = ?::date",
                UUID.class, tenant, month + "-01");
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
