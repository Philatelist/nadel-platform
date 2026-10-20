package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

/** The import_run table: cooperatives are isolated, history is kept, and the table keeps its own rules. */
class ImportRunTenantIsolationTests extends IntegrationTest {

    private static final String INSERT = "insert into import_run (id, tenant_id, file_name, created_by_subject, "
            + "created_by_name, created_at, expires_at, committed_at, status, plan) values "
            + "(?, ?, ?, 's', 'n', now(), now() + interval '30 minutes', ?::timestamptz, ?, ?::jsonb)";

    private UUID demoRun;
    private UUID otherRun;

    @BeforeEach
    void createOneRunPerCooperative() {
        demoRun = UUID.randomUUID();
        otherRun = UUID.randomUUID();
        ownerJdbc().update(INSERT, demoRun, DEMO_TENANT, "demo.xlsx", null, "PREVIEW", "{\"plots\": []}");
        ownerJdbc().update(INSERT, otherRun, OTHER_TENANT, "other.xlsx", null, "PREVIEW", "{\"plots\": []}");
    }

    @AfterEach
    void removeRuns() {
        ownerJdbc().update("delete from import_run");
    }

    private int insertAs(UUID tenantOfSession, UUID rowTenant, String fileName, String committedAt, String status,
            String plan) {
        return inTenant(tenantOfSession, sql -> sql.update(INSERT, UUID.randomUUID(), rowTenant, fileName,
                committedAt, status, plan));
    }

    @Test
    void eachCooperativeSeesOnlyItsOwnRuns() {
        List<UUID> demoRuns = inTenant(DEMO_TENANT, sql -> sql.queryForList("select id from import_run", UUID.class));
        List<UUID> otherRuns = inTenant(OTHER_TENANT, sql -> sql.queryForList("select id from import_run", UUID.class));

        assertThat(demoRuns).containsExactly(demoRun);
        assertThat(otherRuns).containsExactly(otherRun);
    }

    @Test
    void withoutACooperativeNothingIsVisible() {
        Integer rows = inTenant(UUID.randomUUID(), sql -> sql.queryForObject("select count(*) from import_run",
                Integer.class));

        assertThat(rows).isZero();
    }

    @Test
    void cannotInsertARunForAnotherCooperative() {
        assertThatThrownBy(() -> insertAs(DEMO_TENANT, OTHER_TENANT, "x.xlsx", null, "PREVIEW", null))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("violates row-level security policy");
    }

    @Test
    void cannotUpdateTheRunOfAnotherCooperative() {
        Integer updated = inTenant(DEMO_TENANT, sql -> sql.update(
                "update import_run set plan = null where id = ?", otherRun));

        assertThat(updated).isZero();
        assertThat(ownerJdbc().queryForObject("select plan is not null from import_run where id = ?", Boolean.class,
                otherRun)).isTrue();
    }

    @Test
    void cannotMoveARunToAnotherCooperative() {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(
                "update import_run set tenant_id = ? where id = ?", OTHER_TENANT, demoRun)))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("violates row-level security policy");
    }

    @Test
    void theApplicationRoleCannotDeleteOrTruncateTheHistory() {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update("delete from import_run")))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied for table import_run");
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> {
            sql.execute("truncate import_run");
            return null;
        })).isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied for table import_run");

        assertThat(ownerJdbc().queryForObject("select count(*) from import_run", Integer.class)).isEqualTo(2);
    }

    @Test
    void aRunNeedsAnExistingCooperative() {
        assertThatThrownBy(() -> ownerJdbc().update(INSERT, UUID.randomUUID(), UUID.randomUUID(), "x.xlsx", null,
                "PREVIEW", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"));
    }

    @Test
    void theStatusIsOneOfTheFour() {
        for (String status : new String[] {"PREVIEW", "HAS_ERRORS", "STALE"}) {
            assertThat(insertAs(DEMO_TENANT, DEMO_TENANT, "x.xlsx", null, status, null)).isEqualTo(1);
        }
        assertThat(insertAs(DEMO_TENANT, DEMO_TENANT, "x.xlsx", "2026-01-01T00:00:00Z", "COMMITTED", null))
                .isEqualTo(1);
        // EXPIRED is derived, never stored
        assertCheckViolation(() -> insertAs(DEMO_TENANT, DEMO_TENANT, "x.xlsx", null, "EXPIRED", null));
    }

    @Test
    void onlyAPreviewKeepsItsPlan() {
        assertCheckViolation(() -> insertAs(DEMO_TENANT, DEMO_TENANT, "x.xlsx", null, "HAS_ERRORS", "{}"));
        assertCheckViolation(() -> insertAs(DEMO_TENANT, DEMO_TENANT, "x.xlsx", "2026-01-01T00:00:00Z", "COMMITTED",
                "{}"));
        assertCheckViolation(() -> insertAs(DEMO_TENANT, DEMO_TENANT, "x.xlsx", null, "STALE", "{}"));
    }

    @Test
    void aRunIsCommittedExactlyWhenItHasACommitTime() {
        assertCheckViolation(() -> insertAs(DEMO_TENANT, DEMO_TENANT, "x.xlsx", null, "COMMITTED", null));
        assertCheckViolation(() -> insertAs(DEMO_TENANT, DEMO_TENANT, "x.xlsx", "2026-01-01T00:00:00Z", "PREVIEW",
                null));
    }

    @Test
    void theFileNameHasOneToTwoHundredFiftyFiveCharacters() {
        assertThat(insertAs(DEMO_TENANT, DEMO_TENANT, "x".repeat(255), null, "PREVIEW", null)).isEqualTo(1);
        assertCheckViolation(() -> insertAs(DEMO_TENANT, DEMO_TENANT, "x".repeat(256), null, "PREVIEW", null));
        assertCheckViolation(() -> insertAs(DEMO_TENANT, DEMO_TENANT, "", null, "PREVIEW", null));
    }

    @Test
    void theProblemCountIsNotNegativeAndTheRunExpiresAfterItWasCreated() {
        assertCheckViolation(() -> inTenant(DEMO_TENANT, sql -> sql.update(
                "update import_run set problem_count = -1 where id = ?", demoRun)));
        assertCheckViolation(() -> inTenant(DEMO_TENANT, sql -> sql.update(
                "update import_run set expires_at = created_at where id = ?", demoRun)));
    }

    // ---- helpers

    private static void assertCheckViolation(Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
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
