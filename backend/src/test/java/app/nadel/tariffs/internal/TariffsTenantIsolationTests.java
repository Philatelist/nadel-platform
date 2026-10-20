package app.nadel.tariffs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The four tariff tables: cooperatives are isolated (RLS), links never cross them (composite foreign keys), versions are
 * append-only for the application role, and the tables keep their own rules (checks and unique indexes).
 */
class TariffsTenantIsolationTests extends IntegrationTest {

    private static final UUID DEMO_TARIFF = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
    private static final UUID OTHER_TARIFF = UUID.fromString("dddddddd-0000-0000-0000-000000000002");
    private static final String VERSION_INSERT = "insert into tariff_version (tenant_id, tariff_id, valid_from, basis, "
            + "frequency, rate, due_date, note) values (?, ?, ?::date, ?, ?, ?, ?::date, ?)";
    private static final String SCOPE_INSERT = "insert into tariff_scope_plot (tenant_id, tariff_id, plot_id, kind, "
            + "reason) values (?, ?, ?, ?, ?)";
    private static final String TYPE_INSERT = "insert into charge_type (tenant_id, name, position, code, description, "
            + "archived_at) values (?, ?, ?, ?, ?, ?::timestamptz)";

    private UUID demoPlot;
    private UUID otherPlot;
    private UUID demoType;
    private UUID otherType;
    private final List<UUID> tempPlots = new java.util.ArrayList<>();

    @BeforeEach
    void createFixtures() {
        cleanUp();
        JdbcTemplate owner = ownerJdbc();
        demoPlot = owner.queryForObject("select id from plot where tenant_id = ? and kind = 'PLOT' order by number "
                + "limit 1", UUID.class, DEMO_TENANT);
        otherPlot = owner.queryForObject("select id from plot where tenant_id = ? and kind = 'PLOT' order by number "
                + "limit 1", UUID.class, OTHER_TENANT);
        demoType = typeId(DEMO_TENANT, "MEMBERSHIP");
        otherType = typeId(OTHER_TENANT, "MEMBERSHIP");
        for (Object[] row : new Object[][] {{DEMO_TARIFF, DEMO_TENANT, demoType, demoPlot},
                {OTHER_TARIFF, OTHER_TENANT, otherType, otherPlot}}) {
            owner.update("insert into tariff (id, tenant_id, charge_type_id, name) values (?, ?, ?, 'ISOTEST tariff')",
                    row[0], row[1], row[2]);
            owner.update("insert into tariff_version (tenant_id, tariff_id, valid_from, basis, frequency, rate) "
                    + "values (?, ?, date '2026-01-01', 'PER_AREA_SOTKA', 'YEARLY', 50)", row[1], row[0]);
            owner.update("insert into tariff_scope_plot (tenant_id, tariff_id, plot_id, kind, reason) "
                    + "values (?, ?, ?, 'EXCLUDED', 'Exempted by the general meeting')", row[1], row[0], row[3]);
        }
    }

    @AfterEach
    void cleanUp() {
        JdbcTemplate owner = ownerJdbc();
        for (UUID plot : tempPlots) {
            owner.update("delete from plot where id = ?", plot);
        }
        tempPlots.clear();
        // only this test's rows: the demo cooperative may have tariffs of its own
        String mine = "(select id from tariff where name like 'ISOTEST%')";
        owner.update("delete from tariff_scope_plot where tariff_id in " + mine);
        owner.update("delete from tariff_version where tariff_id in " + mine);
        owner.update("delete from tariff where name like 'ISOTEST%'");
        owner.update("delete from charge_type where code is null and tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
        owner.queryForList("select seed_charge_types(?)", DEMO_TENANT);
        owner.queryForList("select seed_charge_types(?)", OTHER_TENANT);
    }

    // ---- row level security

    @Test
    void eachCooperativeSeesOnlyItsOwnRowsInEveryTable() {
        for (UUID tenant : List.of(DEMO_TENANT, OTHER_TENANT)) {
            UUID tariff = tenant.equals(DEMO_TENANT) ? DEMO_TARIFF : OTHER_TARIFF;
            assertThat(count(tenant, "charge_type", "")).as("charge types of %s", tenant).isEqualTo(4);
            assertThat(count(tenant, "tariff", "")).isEqualTo(1);
            assertThat(count(tenant, "tariff_version", "")).isEqualTo(1);
            assertThat(count(tenant, "tariff_scope_plot", "")).isEqualTo(1);
            for (String table : List.of("charge_type", "tariff", "tariff_version", "tariff_scope_plot")) {
                assertThat(count(tenant, table, "where tenant_id <> '" + tenant + "'"))
                        .as("%s rows of another cooperative visible to %s", table, tenant).isZero();
            }
            assertThat(count(tenant, "tariff", "where id = '" + tariff + "'")).isEqualTo(1);
        }
        assertThat(count(DEMO_TENANT, "tariff", "where id = '" + OTHER_TARIFF + "'")).isZero();
    }

    @Test
    void withoutACooperativeNothingIsVisible() {
        UUID nobody = UUID.randomUUID();

        for (String table : List.of("charge_type", "tariff", "tariff_version", "tariff_scope_plot")) {
            assertThat(count(nobody, table, "")).as(table).isZero();
        }
    }

    @Test
    void cannotInsertRowsForAnotherCooperative() {
        assertRowSecurity(sql -> sql.update(TYPE_INSERT, OTHER_TENANT, "Intruder", 9, null, null, null));
        assertRowSecurity(sql -> sql.update("insert into tariff (tenant_id, charge_type_id, name) values (?, ?, "
                + "'Intruder')", OTHER_TENANT, otherType));
        assertRowSecurity(sql -> sql.update(VERSION_INSERT, OTHER_TENANT, OTHER_TARIFF, "2027-01-01", "PER_PLOT",
                "YEARLY", 1, null, null));
        assertRowSecurity(sql -> sql.update(SCOPE_INSERT, OTHER_TENANT, OTHER_TARIFF, otherPlot, "SELECTED", null));
        assertThat(ownerJdbc().queryForObject("select count(*) from charge_type where name = 'Intruder'",
                Integer.class)).isZero();
    }

    @Test
    void cannotUpdateRowsOfAnotherCooperative() {
        Integer types = inTenant(DEMO_TENANT, sql -> sql.update(
                "update charge_type set name = 'tampered' where tenant_id = ?", OTHER_TENANT));
        Integer tariffs = inTenant(DEMO_TENANT, sql -> sql.update(
                "update tariff set name = 'tampered' where tenant_id = ?", OTHER_TENANT));
        Integer scopes = inTenant(DEMO_TENANT, sql -> sql.update(
                "update tariff_scope_plot set reason = 'tampered' where tenant_id = ?", OTHER_TENANT));

        assertThat(types).isZero();
        assertThat(tariffs).isZero();
        assertThat(scopes).isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from tariff where name = 'tampered'", Integer.class))
                .isZero();
    }

    @Test
    void cannotDeleteScopeRowsOfAnotherCooperative() {
        Integer deleted = inTenant(DEMO_TENANT, sql -> sql.update("delete from tariff_scope_plot where tariff_id = ?",
                OTHER_TARIFF));

        assertThat(deleted).isZero();
        assertThat(count(OTHER_TENANT, "tariff_scope_plot", "")).isEqualTo(1);
    }

    @Test
    void seedingTheTypesOfAnotherCooperativeIsRefusedUnderRowSecurity() {
        ownerJdbc().update("delete from charge_type where tenant_id = ? and code = 'WATER'", OTHER_TENANT);

        assertRowSecurity(sql -> sql.queryForList("select seed_charge_types(?)", OTHER_TENANT));
        assertThat(ownerJdbc().queryForObject("select count(*) from charge_type where tenant_id = ?", Integer.class,
                OTHER_TENANT)).isEqualTo(3);
    }

    // ---- composite foreign keys

    @Test
    void aTariffCannotUseAChargeTypeOfAnotherCooperative() {
        assertForeignKey("tariff_charge_type_fk", sql -> sql.update(
                "insert into tariff (tenant_id, charge_type_id, name) values (?, ?, 'Cross')", DEMO_TENANT, otherType));
    }

    @Test
    void aVersionCannotBelongToATariffOfAnotherCooperative() {
        assertForeignKey("tariff_version_tariff_fk", sql -> sql.update(VERSION_INSERT, DEMO_TENANT, OTHER_TARIFF,
                "2027-01-01", "PER_PLOT", "YEARLY", 1, null, null));
    }

    @Test
    void aScopeRowCannotNameATariffOrAPlotOfAnotherCooperative() {
        assertForeignKey("tariff_scope_plot_tariff_fk", sql -> sql.update(SCOPE_INSERT, DEMO_TENANT, OTHER_TARIFF,
                demoPlot, "SELECTED", null));
        assertForeignKey("tariff_scope_plot_plot_fk", sql -> sql.update(SCOPE_INSERT, DEMO_TENANT, DEMO_TARIFF,
                otherPlot, "SELECTED", null));
    }

    @Test
    void aTariffWithVersionsOrAChargeTypeWithTariffsCannotBeRemovedByTheOwnerEither() {
        assertThatThrownBy(() -> ownerJdbc().update("delete from tariff where id = ?", DEMO_TARIFF))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"));
        assertThatThrownBy(() -> ownerJdbc().update("delete from charge_type where id = ?", demoType))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"));
    }

    @Test
    void deletingAPlotRemovesItsScopeRows() {
        UUID plot = tempPlot();
        ownerJdbc().update(SCOPE_INSERT, DEMO_TENANT, DEMO_TARIFF, plot, "SELECTED", null);
        assertThat(count(DEMO_TENANT, "tariff_scope_plot", "where plot_id = '" + plot + "'")).isEqualTo(1);

        // a plot with no history can be deleted by the application role; the scope row goes with it
        inTenant(DEMO_TENANT, sql -> sql.update("delete from plot where id = ?", plot));

        assertThat(ownerJdbc().queryForObject("select count(*) from tariff_scope_plot where plot_id = ?",
                Integer.class, plot)).isZero();
        tempPlots.clear();
    }

    // ---- grants

    @Test
    void theApplicationRoleCannotUpdateDeleteOrTruncateTariffVersions() {
        assertDenied("tariff_version", sql -> sql.update("update tariff_version set rate = 1 where tariff_id = ?",
                DEMO_TARIFF));
        assertDenied("tariff_version", sql -> sql.update("delete from tariff_version where tariff_id = ?",
                DEMO_TARIFF));
        assertDenied("tariff_version", sql -> {
            sql.execute("truncate tariff_version");
            return null;
        });

        assertThat(ownerJdbc().queryForObject("select rate from tariff_version where tariff_id = ?",
                BigDecimal.class, DEMO_TARIFF)).isEqualByComparingTo("50");
        // appending and reading stay allowed
        inTenant(DEMO_TENANT, sql -> sql.update(VERSION_INSERT, DEMO_TENANT, DEMO_TARIFF, "2027-01-01", "PER_PLOT",
                "YEARLY", 1, null, null));
        assertThat(count(DEMO_TENANT, "tariff_version", "")).isEqualTo(2);
    }

    @Test
    void theApplicationRoleCannotDeleteOrTruncateChargeTypesAndTariffsButMayUpdateThem() {
        for (String table : List.of("charge_type", "tariff")) {
            assertDenied(table, sql -> sql.update("delete from " + table));
            assertDenied(table, sql -> {
                sql.execute("truncate " + table);
                return null;
            });
        }
        Integer types = inTenant(DEMO_TENANT, sql -> sql.update("update charge_type set active = active"));
        Integer tariffs = inTenant(DEMO_TENANT, sql -> sql.update("update tariff set scope_kind = 'SELECTED_PLOTS' "
                + "where id = ?", DEMO_TARIFF));

        assertThat(types).isEqualTo(4);
        assertThat(tariffs).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select count(*) from charge_type", Integer.class)).isGreaterThan(7);
    }

    @Test
    void scopeRowsMayBeReplaced() {
        Integer updated = inTenant(DEMO_TENANT, sql -> sql.update(
                "update tariff_scope_plot set reason = 'New reason' where tariff_id = ?", DEMO_TARIFF));
        Integer deleted = inTenant(DEMO_TENANT, sql -> sql.update("delete from tariff_scope_plot where tariff_id = ?",
                DEMO_TARIFF));

        assertThat(updated).isEqualTo(1);
        assertThat(deleted).isEqualTo(1);
    }

    // ---- check constraints

    @Test
    void chargeTypeValuesAreChecked() {
        assertCheck("charge_type_code_ck", sql -> sql.update(TYPE_INSERT, DEMO_TENANT, "Odd code", 9, "ROADS", null,
                null));
        assertCheck("charge_type_name_ck", sql -> sql.update(TYPE_INSERT, DEMO_TENANT, "   ", 9, null, null, null));
        assertCheck("charge_type_name_ck", sql -> sql.update(TYPE_INSERT, DEMO_TENANT, "x".repeat(101), 9, null, null,
                null));
        assertCheck("charge_type_description_ck", sql -> sql.update(TYPE_INSERT, DEMO_TENANT, "Described", 9, null,
                "x".repeat(501), null));
        // the extremes are fine
        inTenant(DEMO_TENANT, sql -> sql.update(TYPE_INSERT, DEMO_TENANT, "x".repeat(100), 9, null, "x".repeat(500),
                null));
    }

    @Test
    void tariffValuesAreChecked() {
        assertCheck("tariff_name_ck", sql -> sql.update("insert into tariff (tenant_id, charge_type_id, name) "
                + "values (?, ?, '  ')", DEMO_TENANT, demoType));
        assertCheck("tariff_name_ck", sql -> sql.update("insert into tariff (tenant_id, charge_type_id, name) "
                + "values (?, ?, ?)", DEMO_TENANT, demoType, "x".repeat(101)));
        assertCheck("tariff_scope_kind_ck", sql -> sql.update("insert into tariff (tenant_id, charge_type_id, name, "
                + "scope_kind) values (?, ?, 'Odd scope', 'SOME_PLOTS')", DEMO_TENANT, demoType));
        inTenant(DEMO_TENANT, sql -> sql.update("insert into tariff (tenant_id, charge_type_id, name, scope_kind) "
                + "values (?, ?, ?, 'SELECTED_PLOTS')", DEMO_TENANT, demoType, "ISOTEST " + "x".repeat(92)));
    }

    @Test
    void newTariffsApplyToAllPlotsByDefault() {
        String scope = inTenant(DEMO_TENANT, sql -> sql.queryForObject("select scope_kind from tariff where id = ?",
                String.class, DEMO_TARIFF));

        assertThat(scope).isEqualTo("ALL_PLOTS");
    }

    @Test
    void versionValuesAreChecked() {
        assertCheck("tariff_version_basis_ck", version("2027-01-01", "PER_HOUSE", "YEARLY", 1, null));
        assertCheck("tariff_version_frequency_ck", version("2027-01-02", "PER_PLOT", "WEEKLY", 1, null));
        assertCheck("tariff_version_rate_ck", version("2027-01-03", "PER_PLOT", "YEARLY", -0.0001, null));
        assertCheck("tariff_version_note_ck", sql -> sql.update(VERSION_INSERT, DEMO_TENANT, DEMO_TARIFF,
                "2027-01-04", "PER_PLOT", "YEARLY", 1, null, "x".repeat(501)));
        inTenant(DEMO_TENANT, sql -> sql.update(VERSION_INSERT, DEMO_TENANT, DEMO_TARIFF, "2027-01-05", "PER_PLOT",
                "YEARLY", 0, null, "x".repeat(500)));
    }

    @Test
    void oneOffBelongsToAFixedAmountAndHasADueDate() {
        // a fixed amount must be one-off, and one-off must be a fixed amount
        assertCheck("tariff_version_one_off_ck", version("2027-02-01", "FIXED_ONE_TIME", "MONTHLY", 1, null));
        assertCheck("tariff_version_one_off_ck", version("2027-02-02", "FIXED_ONE_TIME", "MONTHLY", 1, "2027-03-01"));
        assertCheck("tariff_version_one_off_ck", version("2027-02-03", "PER_PLOT", "ONE_OFF", 1, "2027-03-01"));
        // one-off needs the due date, and a recurring version has none
        assertCheck("tariff_version_one_off_ck", version("2027-02-04", "FIXED_ONE_TIME", "ONE_OFF", 1, null));
        assertCheck("tariff_version_one_off_ck", version("2027-02-05", "PER_PLOT", "YEARLY", 1, "2027-03-01"));

        inTenant(DEMO_TENANT, version("2027-02-06", "FIXED_ONE_TIME", "ONE_OFF", 1200, "2027-12-01"));
        inTenant(DEMO_TENANT, version("2027-02-07", "PER_KWH", "MONTHLY", 4.32, null));
        inTenant(DEMO_TENANT, version("2027-02-08", "PER_M3", "QUARTERLY", 1, null));
        inTenant(DEMO_TENANT, version("2027-02-09", "PER_AREA_SOTKA", "YEARLY", 1, null));
    }

    @Test
    void aScopeRowIsSelectedWithoutAReasonOrExcludedWithOne() {
        UUID plot = tempPlot();

        assertCheck("tariff_scope_plot_kind_ck", sql -> sql.update(SCOPE_INSERT, DEMO_TENANT, DEMO_TARIFF, plot,
                "PICKED", null));
        assertCheck("tariff_scope_plot_reason_ck", sql -> sql.update(SCOPE_INSERT, DEMO_TENANT, DEMO_TARIFF, plot,
                "EXCLUDED", null));
        assertCheck("tariff_scope_plot_reason_ck", sql -> sql.update(SCOPE_INSERT, DEMO_TENANT, DEMO_TARIFF, plot,
                "SELECTED", "Why"));
        assertCheck("tariff_scope_plot_reason_ck", sql -> sql.update(SCOPE_INSERT, DEMO_TENANT, DEMO_TARIFF, plot,
                "EXCLUDED", "   "));
        assertCheck("tariff_scope_plot_reason_ck", sql -> sql.update(SCOPE_INSERT, DEMO_TENANT, DEMO_TARIFF, plot,
                "EXCLUDED", " padded "));
        assertCheck("tariff_scope_plot_reason_ck", sql -> sql.update(SCOPE_INSERT, DEMO_TENANT, DEMO_TARIFF, plot,
                "EXCLUDED", "x".repeat(501)));

        inTenant(DEMO_TENANT, sql -> sql.update(SCOPE_INSERT, DEMO_TENANT, DEMO_TARIFF, plot, "EXCLUDED",
                "x".repeat(500)));
    }

    @Test
    void aPlotIsNamedOnceInATariff() {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(SCOPE_INSERT, DEMO_TENANT, DEMO_TARIFF,
                demoPlot, "EXCLUDED", "Again")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"));
    }

    // ---- unique indexes

    @Test
    void twoVersionsOfOneTariffNeverStartOnTheSameDate() {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, version("2026-01-01", "PER_PLOT", "YEARLY", 2, null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("tariff_version_valid_from_uq");

        // another date of the same tariff, and the same date of another tariff, are fine
        inTenant(DEMO_TENANT, version("2026-07-01", "PER_AREA_SOTKA", "YEARLY", 60, null));
        UUID second = UUID.randomUUID();
        try {
            ownerJdbc().update("insert into tariff (id, tenant_id, charge_type_id, name) values (?, ?, ?, "
                    + "'ISOTEST second')", second, DEMO_TENANT, demoType);
            inTenant(DEMO_TENANT, sql -> sql.update(VERSION_INSERT, DEMO_TENANT, second, "2026-01-01", "PER_PLOT",
                    "YEARLY", 2, null, null));
        } finally {
            ownerJdbc().update("delete from tariff_version where tariff_id = ?", second);
            ownerJdbc().update("delete from tariff where id = ?", second);
        }
    }

    @Test
    void aChargeTypeNameIsUniqueAmongTheNonArchivedTypesIgnoringCase() {
        inTenant(DEMO_TENANT, sql -> sql.update(TYPE_INSERT, DEMO_TENANT, "Road repair", 9, null, null, null));

        for (String clash : List.of("Road repair", "ROAD REPAIR", "membership fee")) {
            assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(TYPE_INSERT, DEMO_TENANT, clash, 10, null,
                    null, null)))
                    .as(clash)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                    .hasMessageContaining("charge_type_tenant_name_uq");
        }
        // another cooperative may use the same name, and an archived type frees its name
        inTenant(OTHER_TENANT, sql -> sql.update(TYPE_INSERT, OTHER_TENANT, "Road repair", 9, null, null, null));
        inTenant(DEMO_TENANT, sql -> sql.update("update charge_type set archived_at = now() where name = 'Road repair'"));
        inTenant(DEMO_TENANT, sql -> sql.update(TYPE_INSERT, DEMO_TENANT, "Road repair", 10, null, null, null));
        assertThat(count(DEMO_TENANT, "charge_type", "where lower(name) = 'road repair'")).isEqualTo(2);
    }

    @Test
    void aSeededCodeAppearsOncePerCooperativeEvenWhenArchived() {
        inTenant(DEMO_TENANT, sql -> sql.update("update charge_type set archived_at = now() where code = 'WATER'"));

        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(TYPE_INSERT, DEMO_TENANT, "Another water", 9,
                "WATER", null, null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("charge_type_tenant_code_uq");
        // seeding again does not bring a second water back
        ownerJdbc().queryForList("select seed_charge_types(?)", DEMO_TENANT);
        assertThat(count(DEMO_TENANT, "charge_type", "where code = 'WATER'")).isEqualTo(1);
    }

    // ---- values

    @Test
    void aRateKeepsFourDecimalsAndIsReadBackUnrounded() {
        inTenant(DEMO_TENANT, version("2027-05-01", "PER_KWH", "MONTHLY", 4.32, null));
        inTenant(DEMO_TENANT, sql -> sql.update(VERSION_INSERT, DEMO_TENANT, DEMO_TARIFF, "2027-05-02", "PER_KWH",
                "MONTHLY", new BigDecimal("33.3333"), null, null));

        List<String> rates = inTenant(DEMO_TENANT, sql -> sql.queryForList(
                "select rate::text from tariff_version where tariff_id = ? and valid_from >= '2027-05-01' "
                        + "order by valid_from", String.class, DEMO_TARIFF));

        assertThat(rates).containsExactly("4.3200", "33.3333");
    }

    @Test
    void seededTypesHaveDeterministicIdsAndAreSeededOnce() {
        ownerJdbc().queryForList("select seed_charge_types(?)", DEMO_TENANT);

        assertThat(ownerJdbc().queryForObject("select count(*) from charge_type where tenant_id = ? and "
                + "id = md5(tenant_id::text || ':CHARGE_TYPE:' || code)::uuid", Integer.class, DEMO_TENANT))
                .isEqualTo(4);
        assertThat(count(DEMO_TENANT, "charge_type", "")).isEqualTo(4);
    }

    // ---- helpers

    private int count(UUID tenant, String table, String where) {
        return inTenant(tenant, sql -> sql.queryForObject("select count(*) from " + table + " " + where,
                Integer.class));
    }

    private UUID typeId(UUID tenant, String code) {
        return ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? and code = ?", UUID.class,
                tenant, code);
    }

    /** A throw-away plot of the demo cooperative (the owner role can create it); removed after the test. */
    private UUID tempPlot() {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 100)", id,
                DEMO_TENANT, "T-" + id.toString().substring(0, 8));
        tempPlots.add(id);
        return id;
    }

    /** An insert of a version of the demo tariff, run as the demo cooperative by {@code inTenant}. */
    private java.util.function.Function<JdbcTemplate, Object> version(String validFrom, String basis, String frequency,
            double rate, String dueDate) {
        return sql -> sql.update(VERSION_INSERT, DEMO_TENANT, DEMO_TARIFF, validFrom, basis, frequency,
                BigDecimal.valueOf(rate), dueDate, null);
    }

    private void assertRowSecurity(java.util.function.Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("violates row-level security policy");
    }

    private void assertForeignKey(String constraint, java.util.function.Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"))
                .hasMessageContaining(constraint);
    }

    private void assertCheck(String constraint, java.util.function.Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"))
                .hasMessageContaining(constraint);
    }

    private void assertDenied(String table, java.util.function.Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied for table " + table);
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
