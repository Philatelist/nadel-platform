package app.nadel;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.stream.Stream;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The day the demo meters and the demo ledger of {@code R__demo_data.sql} are loaded on, for the tests that check the
 * demo data ({@code DemoLedgerDataTests}, {@code DemoDocumentsTests}). Both blocks date their rows from the day they
 * are first loaded ({@code CURRENT_DATE}, {@code now()}), and the application dates its rules from the {@link Clock}
 * bean, so a test that loads the demo "on" a day replaces both: the blocks run with that day in place of
 * {@code CURRENT_DATE} and noon of it (Kyiv) in place of {@code now()}, and the clock of the test context
 * ({@link Config}) is set to the same noon.
 * <p>
 * The tests run on {@link #days()}: today alone in a normal build, and with {@code -Dnadel.demo.days=all} (the CI job
 * "Demo data by day") every day of {@link #MONTHS} and {@link #LEAP_DAY}, so the data is checked on every position of
 * a day in a month: the first days, when the latest round of readings still lies in the month before; the turn of a
 * year; the ends of 31-, 28- and 29-day months.
 */
public final class DemoDay {

    /** The property that asks for every day ({@code all}); absent, the tests run on today. */
    public static final String PROPERTY = "nadel.demo.days";

    /**
     * The months checked day by day: January 2027 (31 days; its first days fall back to December 2026, the turn of a
     * year and the month of the demo one-off road fee) and February 2027 (28 days).
     */
    public static final YearMonth[] MONTHS = {YearMonth.of(2027, 1), YearMonth.of(2027, 2)};

    /** The 29th of February, the day a 28-day check cannot reach. */
    public static final LocalDate LEAP_DAY = LocalDate.of(2028, 2, 29);

    /** The clock of the contexts that import {@link Config}: the system clock until a test fixes it. */
    public static final DemoClock CLOCK = new DemoClock();

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    /** The day the demo meters are loaded on by {@link #loadOnce}; null for the migration's own load. */
    private static LocalDate loaded;

    private DemoDay() {
    }

    /** The days the demo data tests run on: today, or every day of {@link #MONTHS} and {@link #LEAP_DAY}. */
    public static Stream<LocalDate> days() {
        if (!"all".equals(System.getProperty(PROPERTY))) {
            return Stream.of(LocalDate.now(KYIV));
        }
        return Stream.concat(Stream.of(MONTHS).flatMap(month -> month.atDay(1).datesUntil(month.plusMonths(1)
                .atDay(1))), Stream.of(LEAP_DAY));
    }

    /**
     * Loads the demo meters as if on {@code day} (without the demo ledger, which a test loads with
     * {@link DemoLedgerData#restore(JdbcTemplate, LocalDate)}) and fixes the clock at noon of the day.
     */
    public static void load(JdbcTemplate owner, LocalDate day) {
        CLOCK.fix(noon(day));
        DemoMeterData.clear(owner);
        DemoMeterData.restore(owner, day);
    }

    /** {@link #load} unless the demo meters are loaded on {@code day} already. */
    public static synchronized void loadOnce(JdbcTemplate owner, LocalDate day) {
        if (!day.equals(loaded)) {
            load(owner, day);
            loaded = day;
        }
    }

    /** Puts the demo meters back as the migration loads them today, without a ledger, and the system clock. */
    public static synchronized void reset(JdbcTemplate owner) {
        loaded = null;
        CLOCK.release();
        DemoMeterData.clear(owner);
        DemoMeterData.restore(owner);
    }

    /** A block of the demo script as it runs on {@code day}: that day for {@code CURRENT_DATE}, its noon for {@code now()}. */
    static String on(String block, LocalDate day) {
        return block.replace("CURRENT_DATE", "DATE '" + day + "'")
                .replace("now()", "(TIMESTAMP '" + day + " 12:00:00' AT TIME ZONE 'Europe/Kyiv')");
    }

    private static Instant noon(LocalDate day) {
        return day.atTime(LocalTime.NOON).atZone(KYIV).toInstant();
    }

    /** The {@link Clock} bean of the demo data tests: fixed at a demo day, or the system clock (Kyiv). */
    public static final class DemoClock extends Clock {

        private volatile Instant fixed;

        void fix(Instant instant) {
            fixed = instant;
        }

        void release() {
            fixed = null;
        }

        @Override
        public ZoneId getZone() {
            return KYIV;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            Instant now = fixed;
            return now == null ? Clock.system(zone) : Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            Instant now = fixed;
            return now == null ? Instant.now() : now;
        }
    }

    /** Replaces the application's clock with {@link #CLOCK}. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        Clock demoDayClock() {
            return CLOCK;
        }
    }
}
