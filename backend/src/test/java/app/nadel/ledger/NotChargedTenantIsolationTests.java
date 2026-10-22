package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The table {@code not_charged_item} (V15): cooperatives are isolated (RLS, no cooperative sees nothing, nothing is
 * written for another one), links never cross cooperatives (composite foreign keys), one open item per key, and the
 * table keeps its own rules (CHECKs, grants). The fixtures are throw-away rows of both cooperatives that the base class
 * removes (an item goes with its plot).
 */
class NotChargedTenantIsolationTests extends LedgerTestSupport {

    private static final String INSERT = "insert into not_charged_item (tenant_id, item_key, kind, plot_id, "
            + "charge_type_id, meter_id, month, from_date, to_date, reason, params, status, resolved_at) "
            + "values (?, ?, ?, ?, ?, ?, ?::date, ?::date, ?::date, ?, cast(? as jsonb), ?, "
            + "case when ? then now() end)";

    private Side demo;
    private Side other;

    @BeforeEach
    void createFixtures() {
        demo = side(DEMO_TENANT);
        other = side(OTHER_TENANT);
        ownerJdbc().update(INSERT, DEMO_TENANT, "ISO:demo", "TARIFF_MONTH", demo.plot(), demo.chargeType(), null,
                MONTH, MONTH, "2031-01-31", "CONFLICTING_TARIFFS", "{\"tariffIds\": []}", "OPEN", false);
        ownerJdbc().update(INSERT, OTHER_TENANT, "ISO:other", "TARIFF_MONTH", other.plot(), other.chargeType(), null,
                MONTH, MONTH, "2031-01-31", "CONFLICTING_TARIFFS", "{}", "OPEN", false);
    }

    private int insert(JdbcTemplate sql, UUID tenant, String key, String kind, UUID plot, UUID type, UUID meter,
            String month, String reason, String status, boolean resolved) {
        return sql.update(INSERT, tenant, key, kind, plot, type, meter, month, month, month == null ? null
                : "2031-01-31", reason, "{}", status, resolved);
    }

    @Test
    void theTableHasRowLevelSecurityAPolicyAndATenantIndex() {
        assertThat(ownerJdbc().queryForObject(
                "select relrowsecurity from pg_class where oid = 'public.not_charged_item'::regclass", Boolean.class))
                .isTrue();
        assertThat(ownerJdbc().queryForObject("select count(*) from pg_policies where tablename = 'not_charged_item' "
                + "and policyname = 'tenant_isolation'", Integer.class)).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select count(*) from pg_indexes where tablename = 'not_charged_item' "
                + "and indexdef like '%(tenant_id)'", Integer.class)).isEqualTo(1);
    }

    @Test
    void eachCooperativeSeesOnlyItsOwnItemsAndNoCooperativeSeesNothing() {
        for (UUID tenant : List.of(DEMO_TENANT, OTHER_TENANT)) {
            assertThat(count(tenant, "where item_key in ('ISO:demo', 'ISO:other')")).isEqualTo(1);
            assertThat(count(tenant, "where tenant_id <> '" + tenant + "'")).isZero();
        }
        assertThat(count(UUID.randomUUID(), "")).isZero();
    }

    @Test
    void cannotInsertUpdateOrHandAnItemToAnotherCooperative() {
        assertRowSecurity(sql -> insert(sql, OTHER_TENANT, "ISO:x", "TARIFF_MONTH", other.plot(), other.chargeType(),
                null, MONTH, "CONFLICTING_TARIFFS", "OPEN", false));
        Integer updated = inTenant(DEMO_TENANT, sql -> sql.update(
                "update not_charged_item set status = 'OBSOLETE', resolved_at = now() where item_key = 'ISO:other'"));
        assertThat(updated).isZero();
        assertRowSecurity(sql -> sql.update("update not_charged_item set tenant_id = ? where item_key = 'ISO:demo'",
                OTHER_TENANT));
    }

    @Test
    void anItemCannotLinkRowsOfAnotherCooperative() {
        assertForeignKey("not_charged_item_plot_fk", sql -> insert(sql, DEMO_TENANT, "FK1", "TARIFF_MONTH",
                other.plot(), demo.chargeType(), null, MONTH, "CONFLICTING_TARIFFS", "OPEN", false));
        assertForeignKey("not_charged_item_charge_type_fk", sql -> insert(sql, DEMO_TENANT, "FK2", "TARIFF_MONTH",
                demo.plot(), other.chargeType(), null, MONTH, "CONFLICTING_TARIFFS", "OPEN", false));
        assertForeignKey("not_charged_item_meter_fk", sql -> insert(sql, DEMO_TENANT, "FK3", "INTERVAL", demo.plot(),
                demo.chargeType(), other.meter(), null, "NO_TARIFF_FOR_ALL_DAYS", "OPEN", false));
    }

    @Test
    void aKeyIsOpenOnceAndClosedItemsStayAsHistory() {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> insert(sql, DEMO_TENANT, "ISO:demo", "TARIFF_MONTH",
                demo.plot(), demo.chargeType(), null, MONTH, "CONFLICTING_TARIFFS", "OPEN", false)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("not_charged_item_open_key_uq");

        inTenant(DEMO_TENANT, sql -> sql.update("update not_charged_item set status = 'RESOLVED', resolved_at = now() "
                + "where item_key = 'ISO:demo'"));
        inTenant(DEMO_TENANT, sql -> insert(sql, DEMO_TENANT, "ISO:demo", "TARIFF_MONTH", demo.plot(),
                demo.chargeType(), null, MONTH, "CONFLICTING_TARIFFS", "OPEN", false));
        // the same key in the other cooperative is no clash
        inTenant(OTHER_TENANT, sql -> insert(sql, OTHER_TENANT, "ISO:demo", "TARIFF_MONTH", other.plot(),
                other.chargeType(), null, MONTH, "CONFLICTING_TARIFFS", "OPEN", false));
        assertThat(count(DEMO_TENANT, "where item_key = 'ISO:demo'")).isEqualTo(2);
    }

    @Test
    void theColumnsKeepTheirRules() {
        assertCheck("not_charged_item_kind_ck", sql -> insert(sql, DEMO_TENANT, "C1", "MAGIC", demo.plot(),
                demo.chargeType(), null, MONTH, "CONFLICTING_TARIFFS", "OPEN", false));
        assertCheck("not_charged_item_reason_ck", sql -> insert(sql, DEMO_TENANT, "C2", "TARIFF_MONTH", demo.plot(),
                demo.chargeType(), null, MONTH, "BECAUSE", "OPEN", false));
        assertCheck("not_charged_item_status_ck", sql -> insert(sql, DEMO_TENANT, "C3", "TARIFF_MONTH", demo.plot(),
                demo.chargeType(), null, MONTH, "CONFLICTING_TARIFFS", "DONE", true));
        assertCheck("not_charged_item_resolved_ck", sql -> insert(sql, DEMO_TENANT, "C4", "TARIFF_MONTH",
                demo.plot(), demo.chargeType(), null, MONTH, "CONFLICTING_TARIFFS", "RESOLVED", false));
        assertCheck("not_charged_item_resolved_ck", sql -> insert(sql, DEMO_TENANT, "C5", "TARIFF_MONTH",
                demo.plot(), demo.chargeType(), null, MONTH, "CONFLICTING_TARIFFS", "OPEN", true));
        assertCheck("not_charged_item_month_ck", sql -> insert(sql, DEMO_TENANT, "C6", "TARIFF_MONTH", demo.plot(),
                demo.chargeType(), null, "2031-01-15", "CONFLICTING_TARIFFS", "OPEN", false));
        assertCheck("not_charged_item_tariff_month_ck", sql -> insert(sql, DEMO_TENANT, "C7", "TARIFF_MONTH", null,
                demo.chargeType(), null, MONTH, "CONFLICTING_TARIFFS", "OPEN", false));
        assertCheck("not_charged_item_key_ck", sql -> insert(sql, DEMO_TENANT, " ", "TARIFF_MONTH", demo.plot(),
                demo.chargeType(), null, MONTH, "CONFLICTING_TARIFFS", "OPEN", false));
        assertCheck("not_charged_item_range_ck", sql -> sql.update(INSERT, DEMO_TENANT, "C8", "INTERVAL", demo.plot(),
                demo.chargeType(), demo.meter(), null, "2031-01-31", "2031-01-01", "NO_TARIFF_FOR_ALL_DAYS", "{}",
                "OPEN", false));
    }

    @Test
    void theApplicationRoleUpdatesButNeverDeletesOrTruncates() {
        assertDenied(sql -> sql.update("delete from not_charged_item where item_key = 'ISO:demo'"));
        assertDenied(sql -> {
            sql.execute("truncate not_charged_item");
            return null;
        });
        assertThat(count(DEMO_TENANT, "where item_key = 'ISO:demo'")).isEqualTo(1);
    }

    @Test
    void anItemGoesWithItsPlot() {
        UUID plot = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 100)",
                plot, DEMO_TENANT, "ISO-" + plot.toString().substring(0, 8));
        inTenant(DEMO_TENANT, sql -> insert(sql, DEMO_TENANT, "ISO:gone", "TARIFF_MONTH", plot, demo.chargeType(), null,
                MONTH, "CONFLICTING_TARIFFS", "OPEN", false));

        inTenant(DEMO_TENANT, sql -> sql.update("delete from plot where id = ?", plot));

        assertThat(count(DEMO_TENANT, "where item_key = 'ISO:gone'")).isZero();
    }

    // ---- helpers

    private int count(UUID tenant, String where) {
        return inTenant(tenant, sql -> sql.queryForObject("select count(*) from not_charged_item " + where,
                Integer.class));
    }

    private void assertRowSecurity(Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("violates row-level security policy");
    }

    private void assertForeignKey(String constraint, Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"))
                .hasMessageContaining(constraint);
    }

    private void assertCheck(String constraint, Function<JdbcTemplate, ?> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"))
                .hasMessageContaining(constraint);
    }

    private void assertDenied(Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied for table not_charged_item");
    }

    private static String sqlState(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
