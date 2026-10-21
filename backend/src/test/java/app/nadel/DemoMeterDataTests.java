package app.nadel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** The demo meter network of {@code R__demo_data.sql}: its shape, the special cases and that re-running changes nothing. */
class DemoMeterDataTests extends IntegrationTest {

    private final JdbcTemplate owner = ownerJdbc();

    @AfterEach
    void putTheDemoBack() {
        DemoMeterData.restore(owner);
    }

    @Test
    void theDemoCooperativeHasARealisticNetwork() {
        assertThat(count("meter where tenant_id = ?", DEMO_TENANT)).isEqualTo(38);
        assertThat(count("meter where tenant_id = ? and kind = 'SUBSTATION'", DEMO_TENANT)).isEqualTo(1);
        assertThat(count("meter where tenant_id = ? and kind = 'QUARTER'", DEMO_TENANT)).isEqualTo(2);
        assertThat(count("meter where tenant_id = ? and kind = 'COMMON_CONSUMER'", DEMO_TENANT)).isEqualTo(2);
        assertThat(count("meter where tenant_id = ? and resource = 'WATER'", DEMO_TENANT)).isEqualTo(4);
        assertThat(count("meter where tenant_id = ? and kind = 'PLOT' and resource = 'ELECTRICITY'", DEMO_TENANT))
                .isEqualTo(29);
        assertThat(count("meter_parent where tenant_id = ?", DEMO_TENANT)).isEqualTo(33);
        assertThat(count("meter_reading where tenant_id = ?", DEMO_TENANT)).isEqualTo(READINGS);
        // plots 25 and 28 have no meter
        assertThat(count("meter m join plot p on p.id = m.plot_id where p.number in ('25', '28')")).isZero();
        // every plot meter hangs under a quarter, the quarters and the common consumers under the tree
        assertThat(count("meter_parent where tenant_id = ? and valid_to is null", DEMO_TENANT)).isEqualTo(32);
    }

    @Test
    void plotNineWasReplacedThirtyDaysAgo() {
        List<String> row = owner.queryForList("select o.removed_on || '|' || o.final_value || '|' || n.installed_on "
                + "|| '|' || n.initial_value from meter o join meter n on n.replaces_meter_id = o.id "
                + "join plot p on p.id = o.plot_id where p.number = '9' and o.tenant_id = ?", String.class,
                DEMO_TENANT);
        LocalDate day = LocalDate.now().minusDays(30);
        assertThat(row).singleElement().satisfies(r -> assertThat(r.split("\\|")[0])
                .isIn(day.toString(), day.minusDays(1).toString(), day.plusDays(1).toString()));
    }

    @Test
    void oneMeterHasNoReadingForMoreThanSixtyDays() {
        List<String> stale = owner.queryForList("select p.number from meter m join plot p on p.id = m.plot_id "
                + "where m.tenant_id = ? and m.removed_on is null and (select max(r.taken_on) from meter_reading r "
                + "where r.meter_id = m.id and r.status = 'APPROVED') < current_date - 60", String.class, DEMO_TENANT);
        assertThat(stale).containsExactly("20");
    }

    @Test
    void memberDemoHasPendingReadingsAndTheHistoryShowsEveryStatus() {
        assertThat(owner.queryForList("select p.number from meter_reading r join meter m on m.id = r.meter_id "
                + "join plot p on p.id = m.plot_id where r.status = 'PENDING' and r.entered_by_member_id = "
                + "'aaaaaaaa-0000-0000-0000-000000000003' order by p.number", String.class)).containsExactly("15", "7");
        assertThat(count("meter_reading where status = 'REJECTED' and reject_reason is not null")).isEqualTo(1);
        assertThat(count("meter_reading s join meter_reading a on a.id = s.superseded_by_id "
                + "where s.status = 'SUPERSEDED' and a.status = 'APPROVED'")).isEqualTo(1);
    }

    @Test
    void readingsAreOrderedAndHaveGapsBetweenSixAndFiftyFiveDays() {
        // values never decrease with the date and neighbouring dates are 6-55 days apart (plot meters and network)
        assertThat(owner.queryForList("select m.serial from meter m where m.tenant_id = ? and exists (select 1 from "
                + "meter_reading a join meter_reading b on b.meter_id = a.meter_id and b.taken_on > a.taken_on "
                + "where a.meter_id = m.id and a.status = 'APPROVED' and b.status = 'APPROVED' and b.value < a.value)",
                String.class, DEMO_TENANT)).isEmpty();
        assertThat(owner.queryForObject("select max(gap) from (select taken_on - lag(taken_on) over (partition by "
                + "meter_id order by taken_on) as gap from meter_reading where tenant_id = ? and status = 'APPROVED') g",
                Integer.class, DEMO_TENANT)).isBetween(6, 55);
        assertThat(owner.queryForObject("select min(gap) from (select taken_on - lag(taken_on) over (partition by "
                + "meter_id order by taken_on) as gap from meter_reading where tenant_id = ? and status = 'APPROVED') g",
                Integer.class, DEMO_TENANT)).isGreaterThanOrEqualTo(6);
    }

    @Test
    void theOtherCooperativeHasAMeterOfItsOwn() {
        assertThat(count("meter where tenant_id = ?", OTHER_TENANT)).isEqualTo(1);
        assertThat(count("meter_reading where tenant_id = ?", OTHER_TENANT)).isEqualTo(1);
    }

    @Test
    void theElectricianCanViewMetersAndEnterReadings() {
        assertThat(owner.queryForList("select permission from role_permission "
                + "where role_id = 'cccccccc-0000-0000-0000-000000000001' order by permission", String.class))
                .containsExactly("METERS_VIEW", "READINGS_ENTER");
        assertThat(owner.queryForObject("select description from role "
                + "where id = 'cccccccc-0000-0000-0000-000000000001'", String.class))
                .isEqualTo("Вносить покази лічильників на обході");
    }

    @Test
    void runningTheBlockAgainChangesNothingAndClearRemovesEverything() {
        DemoMeterData.restore(owner);
        assertThat(count("meter where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT)).isEqualTo(39);
        assertThat(count("meter_reading where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT)).isEqualTo(READINGS + 1);

        DemoMeterData.clear(owner);
        assertThat(count("meter where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT)).isZero();
        assertThat(count("meter_reading where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT)).isZero();
        assertThat(count("meter_parent where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT)).isZero();

        DemoMeterData.restore(owner);
        assertThat(count("meter where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT)).isEqualTo(39);
    }

    private static final int READINGS = 169;

    private int count(String from, Object... args) {
        return owner.queryForObject("select count(*) from " + from, Integer.class, args);
    }
}
