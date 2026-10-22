package app.nadel.tariffs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.tariffs.DaySet.Range;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Sets of days: normalising, counting, intersecting, membership and the first and last day in a window. */
class DaySetTests {

    private static LocalDate d(String day) {
        return LocalDate.parse(day);
    }

    private static Range r(String from, String to) {
        return new Range(d(from), d(to));
    }

    @Test
    void allCountsEveryDayOfAWindow() {
        assertThat(DaySet.ALL.count(d("2026-10-01"), d("2026-10-31"))).isEqualTo(31);
        assertThat(DaySet.ALL.count(d("2028-02-01"), d("2028-02-29"))).isEqualTo(29);
        assertThat(DaySet.ALL.contains(d("2026-10-16"))).isTrue();
        assertThat(DaySet.ALL.isEmpty()).isFalse();
    }

    @Test
    void noneHasNoDay() {
        assertThat(DaySet.NONE.isEmpty()).isTrue();
        assertThat(DaySet.NONE.count(d("2026-10-01"), d("2026-10-31"))).isZero();
        assertThat(DaySet.NONE.contains(d("2026-10-01"))).isFalse();
        assertThat(DaySet.NONE.first(d("2026-10-01"), d("2026-10-31"))).isEmpty();
        assertThat(DaySet.NONE.last(d("2026-10-01"), d("2026-10-31"))).isEmpty();
    }

    @Test
    void aConnectionFromTheSixteenthCountsSixteenDaysOfOctober() {
        DaySet connected = DaySet.of(d("2026-10-16"), LocalDate.MAX);

        assertThat(connected.count(d("2026-10-01"), d("2026-10-31"))).isEqualTo(16);
        assertThat(connected.first(d("2026-10-01"), d("2026-10-31"))).contains(d("2026-10-16"));
        assertThat(connected.last(d("2026-10-01"), d("2026-10-31"))).contains(d("2026-10-31"));
        assertThat(connected.contains(d("2026-10-15"))).isFalse();
        assertThat(connected.contains(d("2026-10-16"))).isTrue();
    }

    @Test
    void overlappingAndAdjacentRangesAreMergedAndSorted() {
        DaySet set = DaySet.of(List.of(r("2026-10-20", "2026-10-25"), r("2026-10-01", "2026-10-05"),
                r("2026-10-06", "2026-10-10"), r("2026-10-08", "2026-10-12")));

        assertThat(set.ranges()).containsExactly(r("2026-10-01", "2026-10-12"), r("2026-10-20", "2026-10-25"));
        assertThat(set.count(d("2026-10-01"), d("2026-10-31"))).isEqualTo(12 + 6);
        assertThat(set.count(d("2026-10-11"), d("2026-10-21"))).isEqualTo(2 + 2);
        assertThat(set.first(d("2026-10-13"), d("2026-10-31"))).contains(d("2026-10-20"));
        assertThat(set.last(d("2026-10-01"), d("2026-10-19"))).contains(d("2026-10-12"));
        assertThat(set).isEqualTo(DaySet.of(List.of(r("2026-10-01", "2026-10-12"), r("2026-10-20", "2026-10-25"))))
                .hasSameHashCodeAs(DaySet.of(List.of(r("2026-10-20", "2026-10-25"), r("2026-10-01", "2026-10-12"))));
        assertThat(set.toString()).contains("2026-10-01");
    }

    @Test
    void anUnboundedRangeSwallowsEverythingAfterIt() {
        DaySet set = DaySet.of(List.of(new Range(d("2026-10-01"), LocalDate.MAX), r("2026-11-01", "2026-11-05")));

        assertThat(set.ranges()).containsExactly(new Range(d("2026-10-01"), LocalDate.MAX));
    }

    @Test
    void intersectKeepsTheCommonDays() {
        DaySet a = DaySet.of(List.of(r("2026-10-01", "2026-10-10"), r("2026-10-20", "2026-10-31")));
        DaySet b = DaySet.of(d("2026-10-05"), d("2026-10-25"));

        assertThat(a.intersect(b).ranges()).containsExactly(r("2026-10-05", "2026-10-10"),
                r("2026-10-20", "2026-10-25"));
        assertThat(a.intersect(DaySet.ALL)).isEqualTo(a);
        assertThat(a.intersect(DaySet.NONE).isEmpty()).isTrue();
        assertThat(DaySet.of(d("2026-10-01"), d("2026-10-02")).intersect(DaySet.of(d("2026-10-03"), d("2026-10-04")))
                .isEmpty()).isTrue();
    }

    @Test
    void anEmptyWindowCountsNothing() {
        assertThat(DaySet.ALL.count(d("2026-10-31"), d("2026-10-01"))).isZero();
        assertThat(DaySet.ALL.first(d("2026-10-31"), d("2026-10-01"))).isEmpty();
        assertThat(DaySet.ALL.last(d("2026-10-31"), d("2026-10-01"))).isEmpty();
    }

    @Test
    void aRangeEndsOnOrAfterItsFirstDay() {
        assertThatThrownBy(() -> r("2026-10-02", "2026-10-01")).isInstanceOf(IllegalArgumentException.class);
        assertThat(r("2026-10-01", "2026-10-01").from()).isEqualTo(d("2026-10-01"));
    }
}
