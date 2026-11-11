package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V38: the {@code reading_photo} table. Cooperatives are isolated (RLS with USING and WITH CHECK, a composite foreign key
 * keeps the photo inside the cooperative of its reading), and the photo is write-once: the application role cannot
 * UPDATE or DELETE it, the guard trigger refuses both to the owner too while the reading exists, and only the cascade of
 * a deleted reading (the path of {@code ReadingCommands.delete}, run as the application role) removes it.
 */
class ReadingPhotoTests extends IntegrationTest {

    private static final String PRIVILEGE = "42501";
    private static final String CHECK = "23514";
    private static final String PHOTO_INSERT = "insert into reading_photo (tenant_id, reading_id, content_type, "
            + "size_bytes, sha256, content) values (?, ?, 'image/png', 4, decode(repeat('ab', 32), 'hex'), "
            + "'\\x89504e47'::bytea)";

    private UUID demoPlot;
    private UUID otherPlot;
    private UUID demoReading;
    private UUID otherReading;

    @BeforeEach
    void createFixtures() {
        demoPlot = plot(DEMO_TENANT);
        otherPlot = plot(OTHER_TENANT);
        demoReading = reading(DEMO_TENANT, demoPlot);
        otherReading = reading(OTHER_TENANT, otherPlot);
        ownerJdbc().update(PHOTO_INSERT, DEMO_TENANT, demoReading);
        ownerJdbc().update(PHOTO_INSERT, OTHER_TENANT, otherReading);
    }

    @AfterEach
    void cleanUp() {
        for (UUID plot : new UUID[] {demoPlot, otherPlot}) {
            // the photo goes with its reading (cascade, as the owner)
            ownerJdbc().update("delete from meter_reading where meter_id in (select id from meter where plot_id = ?)",
                    plot);
            ownerJdbc().update("delete from meter where plot_id = ?", plot);
            ownerJdbc().update("delete from plot where id = ?", plot);
        }
    }

    @Test
    void theTableHasRowLevelSecurityWithAPolicyAndATenantIndex() {
        assertThat(ownerJdbc().queryForObject(
                "select relrowsecurity from pg_class where oid = 'public.reading_photo'::regclass", Boolean.class))
                .isTrue();
        assertThat(ownerJdbc().queryForObject("select count(*) from pg_policies where tablename = 'reading_photo' "
                + "and policyname = 'tenant_isolation' and qual like '%current_tenant_id%' "
                + "and with_check like '%current_tenant_id%'", Integer.class)).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select count(*) from pg_indexes where tablename = 'reading_photo' "
                + "and indexdef like '%(tenant_id)'", Integer.class)).isEqualTo(1);
    }

    @Test
    void eachCooperativeSeesOnlyItsOwnPhotos() {
        assertThat(count(DEMO_TENANT, "where reading_id = '" + demoReading + "'")).isEqualTo(1);
        assertThat(count(DEMO_TENANT, "where reading_id = '" + otherReading + "'")).isZero();
        assertThat(count(OTHER_TENANT, "where reading_id = '" + otherReading + "'")).isEqualTo(1);
        assertThat(count(OTHER_TENANT, "where tenant_id <> '" + OTHER_TENANT + "'")).isZero();
        assertThat(count(UUID.randomUUID(), "")).isZero();
    }

    @Test
    void cannotInsertAPhotoForAnotherCooperative() {
        UUID freshOther = reading(OTHER_TENANT, otherPlot);

        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(PHOTO_INSERT, OTHER_TENANT, freshOther)))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("violates row-level security policy");
    }

    @Test
    void aPhotoCannotLinkAReadingOfAnotherCooperative() {
        assertThatThrownBy(() -> ownerJdbc().update(PHOTO_INSERT, DEMO_TENANT, otherReading))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"))
                .hasMessageContaining("reading_photo_reading_fk");
    }

    @Test
    void aReadingHasAtMostOnePhoto() {
        assertThatThrownBy(() -> ownerJdbc().update(PHOTO_INSERT, DEMO_TENANT, demoReading))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"));
    }

    @Test
    void theApplicationRoleCanInsertAndReadButNotUpdateDeleteOrTruncate() {
        UUID fresh = reading(DEMO_TENANT, demoPlot);
        Integer inserted = inTenant(DEMO_TENANT, sql -> sql.update(PHOTO_INSERT, DEMO_TENANT, fresh));
        assertThat(inserted).isEqualTo(1);

        assertState(PRIVILEGE, sql -> sql.update("update reading_photo set created_by = null"));
        assertState(PRIVILEGE, sql -> sql.update("delete from reading_photo"));
        assertState(PRIVILEGE, sql -> {
            sql.execute("truncate reading_photo");
            return null;
        });
    }

    @Test
    void theGuardRefusesAnUpdateAndADeleteWhileTheReadingExistsEvenToTheOwner() {
        assertThatThrownBy(() -> ownerJdbc().update("update reading_photo set created_by = null where reading_id = ?",
                demoReading)).satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK));
        assertThatThrownBy(() -> ownerJdbc().update("delete from reading_photo where reading_id = ?", demoReading))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK));
        assertThat(ownerJdbc().queryForObject("select count(*) from reading_photo where reading_id = ?", Integer.class,
                demoReading)).isEqualTo(1);
    }

    @Test
    void deletingTheReadingAsTheApplicationRoleRemovesItsPhoto() {
        Integer deleted = inTenant(DEMO_TENANT, sql -> sql.update("delete from meter_reading where id = ?",
                demoReading));
        assertThat(deleted).isEqualTo(1);

        assertThat(ownerJdbc().queryForObject("select count(*) from reading_photo where reading_id = ?", Integer.class,
                demoReading)).isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from reading_photo where reading_id = ?", Integer.class,
                otherReading)).isEqualTo(1);
    }

    @Test
    void theCooperativeSettingDefaultsToNoPhotoRequired() {
        assertThat(ownerJdbc().queryForObject("select column_default from information_schema.columns where "
                + "table_name = 'cooperative_settings' and column_name = 'reading_photo_required'", String.class))
                .isEqualTo("false");
        assertThat(ownerJdbc().queryForObject("select count(*) from cooperative_settings where reading_photo_required",
                Integer.class)).isZero();
    }

    private int count(UUID tenant, String where) {
        return inTenant(tenant, sql -> sql.queryForObject("select count(*) from reading_photo " + where,
                Integer.class));
    }

    private void assertState(String sqlState, Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(sqlState));
    }

    private static UUID plot(UUID tenant) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 100)", id,
                tenant, "RP-" + id.toString().substring(0, 8));
        return id;
    }

    /** An approved reading on a new plot meter of the plot, as the owner. */
    private static UUID reading(UUID tenant, UUID plot) {
        UUID meter = ownerJdbc().query("select id from meter where plot_id = ?", (rs, i) -> (UUID) rs.getObject(1), plot)
                .stream().findFirst().orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    ownerJdbc().update("insert into meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, "
                            + "initial_value) values (?, ?, 'PLOT', 'ELECTRICITY', ?, ?, date '2026-01-01', 0)", id,
                            tenant, "RP-" + id.toString().substring(0, 8), plot);
                    return id;
                });
        UUID id = UUID.randomUUID();
        int day = ownerJdbc().queryForObject("select count(*) from meter_reading where meter_id = ?", Integer.class,
                meter) + 1;
        ownerJdbc().update("insert into meter_reading (id, tenant_id, meter_id, taken_on, value, source, status) "
                + "values (?, ?, ?, date '2026-03-01' + ?, 100, 'ENTERED', 'APPROVED')", id, tenant, meter, day);
        return id;
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
