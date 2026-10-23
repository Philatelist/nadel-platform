package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The ledger tables charge and charge_part are append-only. nadel_app is refused by privilege; the owner is refused by
 * the trigger unless it is nadel_owner with SET LOCAL app.ledger_maintenance = 'on'.
 */
class LedgerAppendOnlyTests extends LedgerTestSupport {

    private Side demo;
    private UUID charge;
    private UUID part;

    @BeforeEach
    void createFixtures() {
        demo = side(DEMO_TENANT);
        charge = insertCharge(demo, "APPEND:1");
        part = insertPart(demo, charge);
    }

    // ---- the application role: privileges

    @Test
    void theApplicationRoleCannotUpdateDeleteOrTruncateTheLedger() {
        for (String table : new String[] {"charge", "charge_part"}) {
            assertDenied(table, sql -> sql.update("update " + table + " set tenant_id = tenant_id"));
            assertDenied(table, sql -> sql.update("delete from " + table));
            assertDenied(table, sql -> {
                sql.execute("truncate " + table);
                return null;
            });
        }
        assertRowsUnchanged();
    }

    @Test
    void theApplicationRoleMayInsertAndReadCharges() {
        inTenant(DEMO_TENANT, sql -> sql.update("insert into charge (tenant_id, plot_id, charge_type_id, "
                + "billing_month_id, kind, source, amount, service_from, service_to, accrued_on, source_key) values "
                + "(?, ?, ?, ?, 'REGULAR', 'TARIFF', 1.00, date '2031-01-01', date '2031-01-31', date '2031-01-31', "
                + "'APPEND:2')", DEMO_TENANT, demo.plot(), demo.chargeType(), demo.month()));

        Integer rows = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from charge where plot_id = ?", Integer.class, demo.plot()));
        assertThat(rows).isEqualTo(2);
    }

    // ---- the owner: trigger

    @Test
    void theOwnerIsRefusedByTheTrigger() {
        for (String table : new String[] {"charge", "charge_part"}) {
            assertTrigger(table, "UPDATE", () -> ownerJdbc().update("update " + table + " set tenant_id = tenant_id"));
            assertTrigger(table, "DELETE", () -> ownerJdbc().update("delete from " + table));
        }
        // a table that others reference is truncated together with them (the foreign key check comes first otherwise);
        // the trigger of either ledger table refuses
        assertThatThrownBy(() -> ownerJdbc().execute(
                "truncate charge, charge_part, meter_value_use, recalculation_line, recalculation"))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("is append-only: TRUNCATE");
        assertTrigger("charge_part", "TRUNCATE", () -> ownerJdbc().execute("truncate charge_part"));
        assertRowsUnchanged();
    }

    @Test
    void nadelOwnerWithoutTheSettingIsRefused() {
        assertThatThrownBy(() -> asNadelOwner(false, sql -> sql.update("delete from charge_part where id = ?", part)))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> asNadelOwner(false, sql -> sql.update("update charge set amount = 1 where id = ?",
                charge))).hasStackTraceContaining("append-only");
        assertRowsUnchanged();
    }

    @Test
    void theSettingAloneDoesNotHelpTheSuperuserWhoIsNotNadelOwner() {
        assertThatThrownBy(() -> {
            JdbcTemplate sql = ownerJdbc();
            sql.execute("begin; set local app.ledger_maintenance = 'on'; delete from charge_part; commit;");
        }).hasStackTraceContaining("append-only");
        assertRowsUnchanged();
    }

    // ---- the bypass

    @Test
    void nadelOwnerWithTheSettingMayChangeAndDeleteLedgerRows() {
        asLedgerOwner(sql -> {
            assertThat(sql.update("update charge set reason = null, calculation = '{\"fixed\": true}'::jsonb "
                    + "where id = ?", charge)).isEqualTo(1);
            assertThat(sql.update("delete from charge_part where id = ?", part)).isEqualTo(1);
            assertThat(sql.update("delete from charge where id = ?", charge)).isEqualTo(1);
        });

        assertThat(ownerJdbc().queryForObject("select count(*) from charge where id = ?", Integer.class, charge))
                .isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from charge_part where id = ?", Integer.class, part))
                .isZero();
    }

    @Test
    void theSettingIsLocalToOneTransaction() {
        asLedgerOwner(sql -> sql.update("update charge set calculation = '{}'::jsonb where id = ?", charge));

        assertTrigger("charge", "UPDATE", () -> ownerJdbc().update("update charge set amount = amount"));
    }

    @Test
    void theApplicationRoleIsRefusedEvenWithTheSetting() {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> {
            sql.execute("set local app.ledger_maintenance = 'on'");
            return sql.update("delete from charge where id = ?", charge);
        })).isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied for table charge");
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> {
            sql.execute("set local app.ledger_maintenance = 'on'");
            return sql.update("update charge_part set days = 1 where id = ?", part);
        })).isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied for table charge_part");
        assertRowsUnchanged();
    }

    // ---- helpers

    private void assertRowsUnchanged() {
        assertThat(ownerJdbc().queryForObject("select count(*) from charge where id = ? and amount = 10.00",
                Integer.class, charge)).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select count(*) from charge_part where id = ?", Integer.class, part))
                .isEqualTo(1);
    }

    private void assertTrigger(String table, String operation, Runnable statement) {
        assertThatThrownBy(statement::run)
                .as("%s on %s", operation, table)
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ledger table " + table + " is append-only: " + operation);
    }

    private void assertDenied(String table, Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied for table " + table);
    }
}
