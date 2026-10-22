package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The three meter tables: cooperatives are isolated (RLS, with no cooperative nothing is visible, nothing can be written
 * for another one), links never cross them (composite foreign keys), and the tables keep their own rules: the partial
 * unique indexes (one active plot meter per plot and resource, one approved reading per meter and date), the exclusion
 * constraint on the parent periods, the checks, and the grants (no TRUNCATE).
 * <p>
 * The fixtures are rows on throw-away plots of both cooperatives that this class creates and removes, so the demo meters
 * (when they exist) are neither used nor touched.
 */
class MetersTenantIsolationTests extends IntegrationTest {

    private static final String METER_INSERT = "insert into meter (id, tenant_id, kind, resource, serial, name, plot_id, "
            + "installed_on, initial_value, removed_on, final_value, note, replaces_meter_id) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?::date, ?, ?::date, ?, ?, ?)";
    private static final String PARENT_INSERT = "insert into meter_parent (tenant_id, meter_id, parent_meter_id, "
            + "valid_from, valid_to) values (?, ?, ?, ?::date, ?::date)";
    private static final String READING_INSERT = "insert into meter_reading (id, tenant_id, meter_id, taken_on, value, "
            + "source, status, entered_by_member_id, reject_reason, superseded_by_id, decided_by_member_id, decided_at) "
            + "values (?, ?, ?, ?::date, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)";
    private static final List<String> TABLES = List.of("meter", "meter_parent", "meter_reading");

    private final List<UUID> meters = new ArrayList<>();
    private final List<UUID> plots = new ArrayList<>();

    private UUID demoPlot;
    private UUID otherPlot;
    private UUID demoPlotMeter;
    private UUID otherPlotMeter;
    private UUID demoSubstation;
    private UUID otherSubstation;
    private UUID demoReading;
    private UUID otherReading;
    private UUID demoMember;
    private UUID otherMember;

    @BeforeEach
    void createFixtures() {
        demoPlot = tempPlot(DEMO_TENANT);
        otherPlot = tempPlot(OTHER_TENANT);
        demoMember = newMember(DEMO_TENANT, "isotest-demo@meters.local");
        otherMember = newMember(OTHER_TENANT, "isotest-other@meters.local");
        demoPlotMeter = insertMeter(plotMeter(DEMO_TENANT, demoPlot, "ISOTEST-1"));
        otherPlotMeter = insertMeter(plotMeter(OTHER_TENANT, otherPlot, "ISOTEST-2"));
        demoSubstation = insertMeter(namedMeter(DEMO_TENANT, "SUBSTATION", "ISOTEST substation"));
        otherSubstation = insertMeter(namedMeter(OTHER_TENANT, "SUBSTATION", "ISOTEST substation"));
        ownerJdbc().update(PARENT_INSERT, DEMO_TENANT, demoPlotMeter, demoSubstation, "2026-01-01", null);
        ownerJdbc().update(PARENT_INSERT, OTHER_TENANT, otherPlotMeter, otherSubstation, "2026-01-01", null);
        demoReading = insertReading(DEMO_TENANT, demoPlotMeter, "2026-03-01", "100", "APPROVED");
        otherReading = insertReading(OTHER_TENANT, otherPlotMeter, "2026-03-01", "100", "APPROVED");
    }

    @AfterEach
    void cleanUp() {
        JdbcTemplate owner = ownerJdbc();
        for (UUID meter : meters.reversed()) {
            owner.update("delete from meter_reading where meter_id = ?", meter);
            owner.update("delete from meter_parent where meter_id = ? or parent_meter_id = ?", meter, meter);
        }
        for (UUID meter : meters.reversed()) {
            owner.update("delete from meter where id = ?", meter);
        }
        for (UUID plot : plots) {
            owner.update("delete from plot where id = ?", plot);
        }
        meters.clear();
        plots.clear();
    }

    // ---- row level security

    @Test
    void eachCooperativeSeesOnlyItsOwnRowsInEveryTable() {
        for (UUID tenant : List.of(DEMO_TENANT, OTHER_TENANT)) {
            boolean demo = tenant.equals(DEMO_TENANT);
            UUID ownMeter = demo ? demoPlotMeter : otherPlotMeter;
            UUID foreignMeter = demo ? otherPlotMeter : demoPlotMeter;
            assertThat(count(tenant, "meter", "where id in ('" + demoPlotMeter + "', '" + otherPlotMeter + "', '"
                    + demoSubstation + "', '" + otherSubstation + "')")).as("meters visible to %s", tenant).isEqualTo(2);
            assertThat(count(tenant, "meter_parent", "where meter_id = '" + ownMeter + "'")).isEqualTo(1);
            assertThat(count(tenant, "meter_reading", "where meter_id = '" + ownMeter + "'")).isEqualTo(1);
            assertThat(count(tenant, "meter", "where id = '" + foreignMeter + "'")).isZero();
            assertThat(count(tenant, "meter_parent", "where meter_id = '" + foreignMeter + "'")).isZero();
            assertThat(count(tenant, "meter_reading", "where meter_id = '" + foreignMeter + "'")).isZero();
            for (String table : TABLES) {
                assertThat(count(tenant, table, "where tenant_id <> '" + tenant + "'"))
                        .as("%s rows of another cooperative visible to %s", table, tenant).isZero();
            }
        }
    }

    @Test
    void withoutACooperativeNothingIsVisible() {
        UUID nobody = UUID.randomUUID();

        for (String table : TABLES) {
            assertThat(count(nobody, table, "")).as(table).isZero();
        }
    }

    @Test
    void cannotInsertRowsForAnotherCooperative() {
        assertRowSecurity(sql -> sql.update(METER_INSERT, UUID.randomUUID(), OTHER_TENANT, "SUBSTATION", "ELECTRICITY",
                "ISOTEST-X", "Intruder", null, "2026-01-01", 0, null, null, null, null));
        assertRowSecurity(sql -> sql.update(PARENT_INSERT, OTHER_TENANT, otherPlotMeter, otherSubstation, "2025-01-01",
                "2025-12-31"));
        assertRowSecurity(sql -> sql.update(READING_INSERT, UUID.randomUUID(), OTHER_TENANT, otherPlotMeter,
                "2026-03-02", 101, "ENTERED", "APPROVED", null, null, null, null, null));
        assertThat(ownerJdbc().queryForObject("select count(*) from meter where name = 'Intruder'", Integer.class))
                .isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from meter_reading where meter_id = ?", Integer.class,
                otherPlotMeter)).isEqualTo(1);
    }

    @Test
    void cannotUpdateRowsOfAnotherCooperative() {
        Integer meters = inTenant(DEMO_TENANT, sql -> sql.update("update meter set note = 'tampered' where tenant_id = ?",
                OTHER_TENANT));
        Integer parents = inTenant(DEMO_TENANT, sql -> sql.update(
                "update meter_parent set valid_to = date '2026-02-01' where tenant_id = ?", OTHER_TENANT));
        Integer readings = inTenant(DEMO_TENANT, sql -> sql.update(
                "update meter_reading set value = 999 where tenant_id = ?", OTHER_TENANT));

        assertThat(meters).isZero();
        assertThat(parents).isZero();
        assertThat(readings).isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from meter where note = 'tampered'", Integer.class))
                .isZero();
        assertThat(ownerJdbc().queryForObject("select value from meter_reading where id = ?", BigDecimal.class,
                otherReading)).isEqualByComparingTo("100");
        assertThat(ownerJdbc().queryForObject("select valid_to from meter_parent where meter_id = ?", java.sql.Date.class,
                otherPlotMeter)).isNull();
    }

    @Test
    void cannotDeleteRowsOfAnotherCooperative() {
        Integer readings = inTenant(DEMO_TENANT, sql -> sql.update("delete from meter_reading where tenant_id = ?",
                OTHER_TENANT));
        Integer parents = inTenant(DEMO_TENANT, sql -> sql.update("delete from meter_parent where tenant_id = ?",
                OTHER_TENANT));
        Integer meters = inTenant(DEMO_TENANT, sql -> sql.update("delete from meter where tenant_id = ?", OTHER_TENANT));

        assertThat(readings).isZero();
        assertThat(parents).isZero();
        assertThat(meters).isZero();
        assertThat(count(OTHER_TENANT, "meter", "where id = '" + otherPlotMeter + "'")).isEqualTo(1);
        assertThat(count(OTHER_TENANT, "meter_reading", "where id = '" + otherReading + "'")).isEqualTo(1);
    }

    @Test
    void cannotHandARowToAnotherCooperative() {
        // row level security checks the new row too (WITH CHECK)
        assertRowSecurity(sql -> sql.update("update meter set tenant_id = ? where id = ?", OTHER_TENANT, demoPlotMeter));
        assertRowSecurity(sql -> sql.update("update meter_parent set tenant_id = ? where meter_id = ?", OTHER_TENANT,
                demoPlotMeter));
        assertRowSecurity(sql -> sql.update("update meter_reading set tenant_id = ? where id = ?", OTHER_TENANT,
                demoReading));

        assertThat(count(DEMO_TENANT, "meter", "where id = '" + demoPlotMeter + "'")).isEqualTo(1);
        assertThat(count(DEMO_TENANT, "meter_reading", "where id = '" + demoReading + "'")).isEqualTo(1);
    }

    // ---- composite foreign keys

    @Test
    void aMeterCannotBelongToAPlotOfAnotherCooperative() {
        assertForeignKey("meter_plot_fk", sql -> sql.update(METER_INSERT, UUID.randomUUID(), DEMO_TENANT, "PLOT",
                "ELECTRICITY", "ISOTEST-X", null, otherPlot, "2026-01-01", 0, null, null, null, null));
    }

    @Test
    void aMeterCannotReplaceAMeterOfAnotherCooperative() {
        assertForeignKey("meter_replaces_fk", sql -> sql.update(METER_INSERT, UUID.randomUUID(), DEMO_TENANT, "SUBSTATION",
                "ELECTRICITY", "ISOTEST-X", "Replacement", null, "2026-01-01", 0, null, null, null, otherSubstation));
    }

    @Test
    void aParentRowCannotLinkMetersOfAnotherCooperative() {
        UUID free = insertMeter(namedMeter(DEMO_TENANT, "QUARTER", "ISOTEST quarter"));

        assertForeignKey("meter_parent_meter_fk", sql -> sql.update(PARENT_INSERT, DEMO_TENANT, otherPlotMeter,
                demoSubstation, "2025-01-01", null));
        assertForeignKey("meter_parent_parent_fk", sql -> sql.update(PARENT_INSERT, DEMO_TENANT, free, otherSubstation,
                "2025-01-01", null));
    }

    @Test
    void aReadingCannotUseAMeterOrAMemberOfAnotherCooperative() {
        assertForeignKey("meter_reading_meter_fk", sql -> sql.update(READING_INSERT, UUID.randomUUID(), DEMO_TENANT,
                otherPlotMeter, "2026-03-02", 101, "ENTERED", "APPROVED", null, null, null, null, null));
        assertForeignKey("meter_reading_entered_by_fk", sql -> sql.update(READING_INSERT, UUID.randomUUID(), DEMO_TENANT,
                demoPlotMeter, "2026-03-02", 101, "ENTERED", "APPROVED", otherMember, null, null, null, null));
        assertForeignKey("meter_reading_decided_by_fk", sql -> sql.update(READING_INSERT, UUID.randomUUID(), DEMO_TENANT,
                demoPlotMeter, "2026-03-02", 101, "MEMBER", "APPROVED", demoMember, null, null, otherMember,
                "2026-03-03T10:00:00Z"));
    }

    @Test
    void aReadingCannotBeSupersededByAReadingOfAnotherCooperative() {
        assertForeignKey("meter_reading_superseded_by_fk", sql -> sql.update(READING_INSERT, UUID.randomUUID(),
                DEMO_TENANT, demoPlotMeter, "2026-03-02", 101, "ENTERED", "SUPERSEDED", null, null, otherReading, null,
                null));
    }

    @Test
    void aPlotWithAMeterAndAMeterWithReadingsCannotBeDeletedEvenByTheOwner() {
        assertThatThrownBy(() -> ownerJdbc().update("delete from plot where id = ?", demoPlot))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"))
                .hasMessageContaining("meter_plot_fk");
        assertThatThrownBy(() -> ownerJdbc().update("delete from meter where id = ?", demoPlotMeter))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"))
                .hasMessageContaining("meter_reading_meter_fk");
    }

    @Test
    void aMeterThatIsAParentCannotBeDeletedButAChildTakesItsPlacementAway() {
        assertThatThrownBy(() -> ownerJdbc().update("delete from meter where id = ?", demoSubstation))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"))
                .hasMessageContaining("meter_parent_parent_fk");

        // a meter without readings is deleted together with its own place in the tree
        UUID quarter = insertMeter(namedMeter(DEMO_TENANT, "QUARTER", "ISOTEST quarter"));
        ownerJdbc().update(PARENT_INSERT, DEMO_TENANT, quarter, demoSubstation, "2026-01-01", null);
        Integer deleted = inTenant(DEMO_TENANT, sql -> sql.update("delete from meter where id = ?", quarter));
        assertThat(deleted).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select count(*) from meter_parent where meter_id = ?", Integer.class,
                quarter)).isZero();
    }

    @Test
    void aMeterThatWasReplacedCannotBeDeleted() {
        ownerJdbc().update("update meter set removed_on = date '2026-06-01', final_value = 120 where id = ?",
                demoPlotMeter);
        UUID replacement = insertMeter(plotMeter(DEMO_TENANT, demoPlot, "ISOTEST-1B").replacing(demoPlotMeter)
                .installedOn("2026-06-01"));

        assertThat(replacement).isNotNull();
        assertThatThrownBy(() -> ownerJdbc().update("delete from meter where id = ?", demoPlotMeter))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"));
    }

    @Test
    void deletingTheNewerReadingOnlyClearsTheLinkOfTheOlderOne() {
        UUID older = insertReading(DEMO_TENANT, demoPlotMeter, "2026-04-01", "150", "SUPERSEDED");
        UUID newer = insertReading(DEMO_TENANT, demoPlotMeter, "2026-04-01", "160", "APPROVED");
        ownerJdbc().update("update meter_reading set superseded_by_id = ? where id = ?", newer, older);
        assertThat(ownerJdbc().queryForObject("select superseded_by_id from meter_reading where id = ?", UUID.class,
                older)).isEqualTo(newer);

        Integer deleted = inTenant(DEMO_TENANT, sql -> sql.update("delete from meter_reading where id = ?", newer));
        assertThat(deleted).isEqualTo(1);

        assertThat(ownerJdbc().queryForObject("select superseded_by_id from meter_reading where id = ?", UUID.class,
                older)).isNull();
        assertThat(ownerJdbc().queryForObject("select tenant_id from meter_reading where id = ?", UUID.class, older))
                .isEqualTo(DEMO_TENANT);
        assertThat(ownerJdbc().queryForObject("select status from meter_reading where id = ?", String.class, older))
                .isEqualTo("SUPERSEDED");
    }

    // ---- grants

    @Test
    void theApplicationRoleMayUpdateAndDeleteButNotTruncate() {
        for (String table : TABLES) {
            assertDenied(table, sql -> {
                sql.execute("truncate " + table);
                return null;
            });
        }
        Integer updatedMeters = inTenant(DEMO_TENANT, sql -> sql.update(
                "update meter set note = 'edited' where id = ?", demoSubstation));
        Integer updatedParents = inTenant(DEMO_TENANT, sql -> sql.update(
                "update meter_parent set valid_to = date '2026-06-30' where meter_id = ?", demoPlotMeter));
        Integer updatedReadings = inTenant(DEMO_TENANT, sql -> sql.update(
                "update meter_reading set value = 101 where id = ?", demoReading));
        Integer deletedParents = inTenant(DEMO_TENANT, sql -> sql.update("delete from meter_parent where meter_id = ?",
                demoPlotMeter));
        Integer deletedReadings = inTenant(DEMO_TENANT, sql -> sql.update("delete from meter_reading where id = ?",
                demoReading));
        Integer deletedMeters = inTenant(DEMO_TENANT, sql -> sql.update("delete from meter where id = ?",
                demoPlotMeter));

        assertThat(updatedMeters).isEqualTo(1);
        assertThat(updatedParents).isEqualTo(1);
        assertThat(updatedReadings).isEqualTo(1);
        assertThat(deletedParents).isEqualTo(1);
        assertThat(deletedReadings).isEqualTo(1);
        assertThat(deletedMeters).isEqualTo(1);
        assertThat(count(DEMO_TENANT, "meter", "where id = '" + demoSubstation + "'")).isEqualTo(1);
    }

    // ---- unique indexes

    @Test
    void aPlotHasOneActiveMeterPerResource() {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, insert(plotMeter(DEMO_TENANT, demoPlot, "ISOTEST-DUP"))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("meter_active_plot_uq");

        // water on the same plot is another resource and another plot is another slot; two active water meters clash
        inTenant(DEMO_TENANT, insert(plotMeter(DEMO_TENANT, demoPlot, "ISOTEST-W").resource("WATER")));
        UUID second = tempPlot(DEMO_TENANT);
        inTenant(DEMO_TENANT, insert(plotMeter(DEMO_TENANT, second, "ISOTEST-DUP")));
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, insert(plotMeter(DEMO_TENANT, demoPlot, "ISOTEST-W2")
                .resource("WATER")))).isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"));
    }

    @Test
    void aRemovedMeterFreesThePlotsSlot() {
        ownerJdbc().update("update meter set removed_on = date '2026-05-01', final_value = 120 where id = ?",
                demoPlotMeter);

        UUID replacement = insertMeter(plotMeter(DEMO_TENANT, demoPlot, "ISOTEST-1B").installedOn("2026-05-01"));

        assertThat(replacement).isNotNull();
        // the removed meter stays, so the plot has two meters and one active
        assertThat(count(DEMO_TENANT, "meter", "where plot_id = '" + demoPlot + "'")).isEqualTo(2);
        assertThat(count(DEMO_TENANT, "meter", "where plot_id = '" + demoPlot + "' and removed_on is null")).isEqualTo(1);
    }

    @Test
    void theOneActiveMeterRuleIsForPlotMetersOnly() {
        // non-plot meters have no plot; several of one kind are fine, even with the same serial
        inTenant(DEMO_TENANT, insert(namedMeter(DEMO_TENANT, "QUARTER", "ISOTEST quarter A")));
        inTenant(DEMO_TENANT, insert(namedMeter(DEMO_TENANT, "QUARTER", "ISOTEST quarter B")));
        inTenant(DEMO_TENANT, insert(namedMeter(DEMO_TENANT, "COMMON_CONSUMER", "ISOTEST pump").serial("ISOTEST-S")));
        inTenant(DEMO_TENANT, insert(namedMeter(DEMO_TENANT, "COMMON_CONSUMER", "ISOTEST lights").serial("ISOTEST-S")));
    }

    @Test
    void aMeterHasOneApprovedReadingPerDate() {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, readingInsert(demoPlotMeter, "2026-03-01", "APPROVED")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("meter_reading_approved_uq");

        // a pending, a superseded and a rejected reading of the same date are fine, and so is another meter
        inTenant(DEMO_TENANT, readingInsert(demoPlotMeter, "2026-03-01", "PENDING"));
        inTenant(DEMO_TENANT, readingInsert(demoPlotMeter, "2026-03-01", "SUPERSEDED"));
        inTenant(DEMO_TENANT, readingInsert(demoPlotMeter, "2026-03-01", "REJECTED", "Wrong meter"));
        inTenant(DEMO_TENANT, readingInsert(demoSubstation, "2026-03-01", "APPROVED"));
        // another date of the same meter too
        inTenant(DEMO_TENANT, readingInsert(demoPlotMeter, "2026-03-02", "APPROVED"));
        assertThat(count(DEMO_TENANT, "meter_reading", "where meter_id = '" + demoPlotMeter + "'")).isEqualTo(5);
    }

    @Test
    void anApprovedReadingCanReplaceTheOldOneOnceTheOldOneIsSuperseded() {
        // the order the service uses: supersede the old row first, then insert the new approved one
        inTenant(DEMO_TENANT, sql -> sql.update("update meter_reading set status = 'SUPERSEDED' where id = ?",
                demoReading));
        UUID replacement = UUID.randomUUID();
        inTenant(DEMO_TENANT, sql -> sql.update(READING_INSERT, replacement, DEMO_TENANT, demoPlotMeter, "2026-03-01", 105,
                "ENTERED", "APPROVED", null, null, null, null, null));
        inTenant(DEMO_TENANT, sql -> sql.update("update meter_reading set superseded_by_id = ? where id = ?", replacement,
                demoReading));

        assertThat(count(DEMO_TENANT, "meter_reading", "where meter_id = '" + demoPlotMeter + "' and status = 'APPROVED'"))
                .isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select superseded_by_id from meter_reading where id = ?", UUID.class,
                demoReading)).isEqualTo(replacement);
    }

    // ---- exclusion constraint on the parent periods

    @Test
    void aMeterHasOneParentAtATime() {
        UUID quarter = insertMeter(namedMeter(DEMO_TENANT, "QUARTER", "ISOTEST quarter"));
        // demoPlotMeter has the substation from 2026-01-01 without end: every overlapping period is refused
        for (String[] period : new String[][] {{"2026-06-01", null}, {"2025-01-01", "2026-01-01"},
                {"2025-01-01", null}, {"2026-03-01", "2026-03-31"}}) {
            assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(PARENT_INSERT, DEMO_TENANT, demoPlotMeter,
                    quarter, period[0], period[1])))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23P01"))
                    .hasMessageContaining("meter_parent_no_overlap_ex");
        }
    }

    @Test
    void aMeterMayMoveToAnotherParentOnTheNextDayAndTheHistoryIsKept() {
        UUID quarter = insertMeter(namedMeter(DEMO_TENANT, "QUARTER", "ISOTEST quarter"));

        // the move on 15.10: the old period ends on the 14th, the new one starts on the 15th
        inTenant(DEMO_TENANT, sql -> sql.update(
                "update meter_parent set valid_to = date '2026-10-14' where meter_id = ?", demoPlotMeter));
        inTenant(DEMO_TENANT, sql -> sql.update(PARENT_INSERT, DEMO_TENANT, demoPlotMeter, quarter, "2026-10-15", null));
        // a period before the first one is history, too
        inTenant(DEMO_TENANT, sql -> sql.update(PARENT_INSERT, DEMO_TENANT, demoPlotMeter, quarter, "2025-01-01",
                "2025-12-31"));

        assertThat(count(DEMO_TENANT, "meter_parent", "where meter_id = '" + demoPlotMeter + "'")).isEqualTo(3);
        // another meter may have the same period
        inTenant(DEMO_TENANT, sql -> sql.update(PARENT_INSERT, DEMO_TENANT, quarter, demoSubstation, "2026-01-01", null));
    }

    // ---- check constraints: meter

    @Test
    void meterKindAndResourceAreChecked() {
        assertCheck("meter_kind_ck", insert(namedMeter(DEMO_TENANT, "TRANSFORMER", "ISOTEST x")));
        assertCheck("meter_resource_ck", insert(plotMeter(DEMO_TENANT, tempPlot(DEMO_TENANT), "ISOTEST-X")
                .resource("GAS")));
        // water belongs to plot meters only
        assertCheck("meter_resource_kind_ck", insert(namedMeter(DEMO_TENANT, "QUARTER", "ISOTEST x").resource("WATER")));
        assertCheck("meter_resource_kind_ck", insert(namedMeter(DEMO_TENANT, "SUBSTATION", "ISOTEST x")
                .resource("WATER")));
        assertCheck("meter_resource_kind_ck", insert(namedMeter(DEMO_TENANT, "COMMON_CONSUMER", "ISOTEST x")
                .resource("WATER")));
        inTenant(DEMO_TENANT, insert(plotMeter(DEMO_TENANT, tempPlot(DEMO_TENANT), "ISOTEST-OK").resource("WATER")));
    }

    @Test
    void aPlotMeterHasAPlotAndOtherMetersDoNot() {
        assertCheck("meter_plot_ck", insert(plotMeter(DEMO_TENANT, null, "ISOTEST-X")));
        assertCheck("meter_plot_ck", insert(namedMeter(DEMO_TENANT, "QUARTER", "ISOTEST x").onPlot(tempPlot(DEMO_TENANT))));
        assertCheck("meter_plot_ck", insert(namedMeter(DEMO_TENANT, "SUBSTATION", "ISOTEST x").onPlot(demoPlot)));
    }

    @Test
    void theSerialIsRequiredAndShort() {
        UUID plot = tempPlot(DEMO_TENANT);
        assertCheck("meter_serial_ck", insert(plotMeter(DEMO_TENANT, plot, "")));
        assertCheck("meter_serial_ck", insert(plotMeter(DEMO_TENANT, plot, "   ")));
        assertCheck("meter_serial_ck", insert(plotMeter(DEMO_TENANT, plot, "x".repeat(51))));
        inTenant(DEMO_TENANT, insert(plotMeter(DEMO_TENANT, plot, "x".repeat(50))));
    }

    @Test
    void aNonPlotMeterNeedsANameOfAtMostOneHundredCharacters() {
        assertCheck("meter_name_ck", insert(namedMeter(DEMO_TENANT, "COMMON_CONSUMER", null)));
        assertCheck("meter_name_ck", insert(namedMeter(DEMO_TENANT, "QUARTER", "   ")));
        assertCheck("meter_name_ck", insert(namedMeter(DEMO_TENANT, "SUBSTATION", "x".repeat(101))));
        // a plot meter needs none, but when it has one the limit applies
        assertCheck("meter_name_ck", insert(plotMeter(DEMO_TENANT, tempPlot(DEMO_TENANT), "ISOTEST-X")
                .named("x".repeat(101))));
        inTenant(DEMO_TENANT, insert(namedMeter(DEMO_TENANT, "SUBSTATION", "x".repeat(100))));
        inTenant(DEMO_TENANT, insert(plotMeter(DEMO_TENANT, tempPlot(DEMO_TENANT), "ISOTEST-NN").named("Back plot")));
    }

    @Test
    void theInitialValueIsNotNegativeAndKeepsThreeDecimals() {
        UUID plot = tempPlot(DEMO_TENANT);
        assertCheck("meter_initial_value_ck", insert(plotMeter(DEMO_TENANT, plot, "ISOTEST-X").initial("-0.001")));
        UUID id = inTenant(DEMO_TENANT, insert(plotMeter(DEMO_TENANT, plot, "ISOTEST-OK").initial("99999999999.999")));
        assertThat(ownerJdbc().queryForObject("select initial_value::text from meter where id = ?", String.class, id))
                .isEqualTo("99999999999.999");
    }

    @Test
    void theRemovalDateAndTheFinalValueComeTogetherAndNotBeforeInstallation() {
        UUID plot = tempPlot(DEMO_TENANT);
        assertCheck("meter_removal_ck", insert(plotMeter(DEMO_TENANT, plot, "ISOTEST-X").removed("2026-05-01", null)));
        assertCheck("meter_removal_ck", insert(plotMeter(DEMO_TENANT, plot, "ISOTEST-X").removed(null, "100")));
        assertCheck("meter_removal_ck", insert(plotMeter(DEMO_TENANT, plot, "ISOTEST-X").installedOn("2026-05-01")
                .removed("2026-04-30", "100")));
        assertCheck("meter_removal_ck", insert(plotMeter(DEMO_TENANT, plot, "ISOTEST-X").removed("2026-05-01", "-1")));
        // removal on the day of installation is allowed (a meter entered on the wrong day)
        inTenant(DEMO_TENANT, insert(plotMeter(DEMO_TENANT, plot, "ISOTEST-OK").installedOn("2026-05-01")
                .removed("2026-05-01", "0")));
    }

    @Test
    void theNoteIsAtMostFiveHundredCharactersAndAMeterDoesNotReplaceItself() {
        UUID plot = tempPlot(DEMO_TENANT);
        assertCheck("meter_note_ck", insert(plotMeter(DEMO_TENANT, plot, "ISOTEST-X").note("x".repeat(501))));
        inTenant(DEMO_TENANT, insert(plotMeter(DEMO_TENANT, plot, "ISOTEST-OK").note("x".repeat(500))));

        UUID self = UUID.randomUUID();
        assertCheck("meter_replaces_ck", sql -> sql.update(METER_INSERT, self, DEMO_TENANT, "SUBSTATION", "ELECTRICITY",
                "ISOTEST-X", "Self", null, "2026-01-01", 0, null, null, null, self));
    }

    // ---- check constraints: parent and reading

    @Test
    void aParentPeriodHasADistinctParentAndDoesNotEndBeforeItStarts() {
        UUID quarter = insertMeter(namedMeter(DEMO_TENANT, "QUARTER", "ISOTEST quarter"));

        assertCheck("meter_parent_self_ck", sql -> sql.update(PARENT_INSERT, DEMO_TENANT, quarter, quarter, "2026-01-01",
                null));
        assertCheck("meter_parent_period_ck", sql -> sql.update(PARENT_INSERT, DEMO_TENANT, quarter, demoSubstation,
                "2026-02-01", "2026-01-31"));
        // a one-day period is fine
        inTenant(DEMO_TENANT, sql -> sql.update(PARENT_INSERT, DEMO_TENANT, quarter, demoSubstation, "2026-02-01",
                "2026-02-01"));
    }

    @Test
    void readingValuesSourcesAndStatusesAreChecked() {
        assertCheck("meter_reading_value_ck", sql -> sql.update(READING_INSERT, UUID.randomUUID(), DEMO_TENANT,
                demoPlotMeter, "2026-04-01", new BigDecimal("-0.001"), "ENTERED", "APPROVED", null, null, null, null,
                null));
        assertCheck("meter_reading_source_ck", sql -> sql.update(READING_INSERT, UUID.randomUUID(), DEMO_TENANT,
                demoPlotMeter, "2026-04-01", 1, "PHONE", "APPROVED", null, null, null, null, null));
        assertCheck("meter_reading_status_ck", sql -> sql.update(READING_INSERT, UUID.randomUUID(), DEMO_TENANT,
                demoPlotMeter, "2026-04-01", 1, "ENTERED", "DRAFT", null, null, null, null, null));
        List<String> sources = List.of("ENTERED", "IMPORTED", "MEMBER");
        for (int i = 0; i < sources.size(); i++) {
            String source = sources.get(i);
            String takenOn = "2026-04-0" + (i + 1);
            inTenant(DEMO_TENANT, sql -> sql.update(READING_INSERT, UUID.randomUUID(), DEMO_TENANT, demoPlotMeter,
                    takenOn, 1, source, "PENDING", null, null, null, null, null));
        }
    }

    @Test
    void aRejectionAlwaysSaysWhyAndNothingElseHasAReason() {
        assertCheck("meter_reading_reject_reason_ck", readingInsert(demoPlotMeter, "2026-04-01", "REJECTED"));
        assertCheck("meter_reading_reject_reason_ck", readingInsert(demoPlotMeter, "2026-04-01", "REJECTED", "   "));
        assertCheck("meter_reading_reject_reason_ck", readingInsert(demoPlotMeter, "2026-04-01", "REJECTED", " padded "));
        assertCheck("meter_reading_reject_reason_ck", readingInsert(demoPlotMeter, "2026-04-01", "REJECTED",
                "x".repeat(501)));
        assertCheck("meter_reading_reject_reason_ck", readingInsert(demoPlotMeter, "2026-04-01", "PENDING", "Why"));
        assertCheck("meter_reading_reject_reason_ck", readingInsert(demoPlotMeter, "2026-04-01", "APPROVED", "Why"));
        inTenant(DEMO_TENANT, readingInsert(demoPlotMeter, "2026-04-01", "REJECTED", "x".repeat(500)));
    }

    @Test
    void onlyASupersededReadingHasASuccessorAndNeverItself() {
        assertCheck("meter_reading_superseded_ck", sql -> sql.update(READING_INSERT, UUID.randomUUID(), DEMO_TENANT,
                demoPlotMeter, "2026-04-01", 1, "ENTERED", "APPROVED", null, null, demoReading, null, null));
        assertCheck("meter_reading_superseded_ck", sql -> sql.update(READING_INSERT, UUID.randomUUID(), DEMO_TENANT,
                demoPlotMeter, "2026-04-01", 1, "ENTERED", "PENDING", null, null, demoReading, null, null));
        UUID id = UUID.randomUUID();
        inTenant(DEMO_TENANT, sql -> sql.update(READING_INSERT, id, DEMO_TENANT, demoPlotMeter, "2026-04-02", 1,
                "ENTERED", "SUPERSEDED", null, null, demoReading, null, null));
        assertCheck("meter_reading_not_self_superseded_ck", sql -> sql.update(
                "update meter_reading set superseded_by_id = id, status = 'SUPERSEDED' where id = ?", demoReading));
    }

    @Test
    void aReadingKeepsThreeDecimalsAndWhoDecidedWhen() {
        UUID id = UUID.randomUUID();
        inTenant(DEMO_TENANT, sql -> sql.update(READING_INSERT, id, DEMO_TENANT, demoPlotMeter, "2026-04-01",
                new BigDecimal("8205.125"), "MEMBER", "APPROVED", demoMember, null, null, demoMember,
                "2026-04-02T08:00:00Z"));

        assertThat(ownerJdbc().queryForObject("select value::text from meter_reading where id = ?", String.class, id))
                .isEqualTo("8205.125");
        assertThat(ownerJdbc().queryForObject("select decided_by_member_id from meter_reading where id = ?", UUID.class,
                id)).isEqualTo(demoMember);
    }

    @Test
    void metersAndReadingsKeepTheirRowsWhenReadBackWithoutRounding() {
        UUID id = inTenant(DEMO_TENANT, insert(plotMeter(DEMO_TENANT, tempPlot(DEMO_TENANT), "ISOTEST-V")
                .initial("0.125").removed("2026-12-01", "0.250")));

        List<String> values = inTenant(DEMO_TENANT, sql -> sql.queryForList(
                "select initial_value::text || '/' || final_value::text from meter where id = ?", String.class, id));

        assertThat(values).containsExactly("0.125/0.250");
    }

    // ---- fixtures and helpers

    /** The parameters of a meter insert; the factories give a plain electricity meter installed on 2026-01-01. */
    private static final class MeterRow {
        UUID id = UUID.randomUUID();
        final UUID tenant;
        String kind;
        String resource = "ELECTRICITY";
        String serial = "ISOTEST-S";
        String name;
        UUID plot;
        String installedOn = "2026-01-01";
        String initial = "0";
        String removedOn;
        String finalValue;
        String note;
        UUID replaces;

        MeterRow(UUID tenant, String kind) {
            this.tenant = tenant;
            this.kind = kind;
        }

        MeterRow resource(String value) {
            resource = value;
            return this;
        }

        MeterRow serial(String value) {
            serial = value;
            return this;
        }

        MeterRow named(String value) {
            name = value;
            return this;
        }

        MeterRow onPlot(UUID value) {
            plot = value;
            return this;
        }

        MeterRow installedOn(String value) {
            installedOn = value;
            return this;
        }

        MeterRow initial(String value) {
            initial = value;
            return this;
        }

        MeterRow removed(String on, String value) {
            removedOn = on;
            finalValue = value;
            return this;
        }

        MeterRow note(String value) {
            note = value;
            return this;
        }

        MeterRow replacing(UUID value) {
            replaces = value;
            return this;
        }

        Object[] arguments() {
            return new Object[] {id, tenant, kind, resource, serial, name, plot, installedOn, new BigDecimal(initial),
                    removedOn, finalValue == null ? null : new BigDecimal(finalValue), note, replaces};
        }
    }

    private static MeterRow plotMeter(UUID tenant, UUID plot, String serial) {
        return new MeterRow(tenant, "PLOT").onPlot(plot).serial(serial);
    }

    private static MeterRow namedMeter(UUID tenant, String kind, String name) {
        return new MeterRow(tenant, kind).named(name);
    }

    /** Inserts a meter as the container owner (no row security) and remembers it for the cleanup. */
    private UUID insertMeter(MeterRow row) {
        ownerJdbc().update(METER_INSERT, row.arguments());
        meters.add(row.id);
        return row.id;
    }

    /** An insert statement for {@code inTenant}; the row is remembered for the cleanup, so is the plot. */
    private Function<JdbcTemplate, UUID> insert(MeterRow row) {
        meters.add(row.id);
        return sql -> {
            sql.update(METER_INSERT, row.arguments());
            return row.id;
        };
    }

    /** Inserts a reading as the container owner; a rejected one gets a reason. */
    private UUID insertReading(UUID tenant, UUID meter, String takenOn, String value, String status) {
        UUID id = UUID.randomUUID();
        String reason = status.equals("REJECTED") ? "Fixture" : null;
        ownerJdbc().update(READING_INSERT, id, tenant, meter, takenOn, new BigDecimal(value), "ENTERED", status, null,
                reason, null, null, null);
        return id;
    }

    private Function<JdbcTemplate, Object> readingInsert(UUID meter, String takenOn, String status) {
        return readingInsert(meter, takenOn, status, null);
    }

    private Function<JdbcTemplate, Object> readingInsert(UUID meter, String takenOn, String status, String reason) {
        return sql -> sql.update(READING_INSERT, UUID.randomUUID(), DEMO_TENANT, meter, takenOn, 100, "ENTERED", status,
                null, reason, null, null, null);
    }

    private int count(UUID tenant, String table, String where) {
        return inTenant(tenant, sql -> sql.queryForObject("select count(*) from " + table + " " + where,
                Integer.class));
    }

    /** A throw-away plot (the owner role can create it); removed after the test, once its meters are gone. */
    private UUID tempPlot(UUID tenant) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 100)", id,
                tenant, "M-" + id.toString().substring(0, 8));
        plots.add(id);
        return id;
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

    private void assertDenied(String table, Function<JdbcTemplate, Object> statement) {
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
