package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.tariffs.DaySet.Range;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The days a "not charged" item of a run is narrowed to (feature 011 §2.5 and §2.6: a run or a retry that charged some
 * of the days leaves the rest in the queue): gaps at the start, in the middle and at the end, booked ranges that start
 * before the item's days or end after them, and full coverage.
 * <pre>
 * @layer: unit
 * @spec: 011-ledger-charges-period-closing
 * @regression
 * </pre>
 */
@Tag("regression")
class UnchargedDaysTests {

    private static final LocalDate FROM = LocalDate.parse("2026-09-01");
    private static final LocalDate TO = LocalDate.parse("2026-09-30");

    private static Range range(String from, String to) {
        return new Range(LocalDate.parse(from), LocalDate.parse(to));
    }

    @Test
    void withNothingChargedEveryDayIsLeft() {
        assertThat(UnchargedDays.left(FROM, TO, List.of())).containsExactly(range("2026-09-01", "2026-09-30"));
    }

    @Test
    void aGapAtTheStartIsLeftWhenTheChargedRangeStartsLater() {
        assertThat(UnchargedDays.left(FROM, TO, List.of(range("2026-09-11", "2026-09-30"))))
                .containsExactly(range("2026-09-01", "2026-09-10"));
    }

    @Test
    void aGapAtTheEndIsLeftWhenTheChargedRangeEndsEarlier() {
        assertThat(UnchargedDays.left(FROM, TO, List.of(range("2026-09-01", "2026-09-15"))))
                .containsExactly(range("2026-09-16", "2026-09-30"));
    }

    @Test
    void gapsInTheMiddleAreLeftBetweenTheChargedRanges() {
        assertThat(UnchargedDays.left(FROM, TO,
                List.of(range("2026-09-05", "2026-09-10"), range("2026-09-20", "2026-09-22"))))
                .containsExactly(range("2026-09-01", "2026-09-04"), range("2026-09-11", "2026-09-19"),
                        range("2026-09-23", "2026-09-30"));
    }

    @Test
    void aChargedRangeThatStartsBeforeTheDaysOnlyTakesTheDaysItCovers() {
        assertThat(UnchargedDays.left(FROM, TO, List.of(range("2026-08-15", "2026-09-10"))))
                .containsExactly(range("2026-09-11", "2026-09-30"));
    }

    @Test
    void aChargedRangeThatEndsAfterTheDaysLeavesTheGapBeforeItOnly() {
        assertThat(UnchargedDays.left(FROM, TO, List.of(range("2026-09-20", "2026-10-31"))))
                .containsExactly(range("2026-09-01", "2026-09-19"));
    }

    @Test
    void aRangeAfterTheDaysChangesNothing() {
        assertThat(UnchargedDays.left(FROM, TO, List.of(range("2026-10-01", "2026-10-31"))))
                .containsExactly(range("2026-09-01", "2026-09-30"));
    }

    @Test
    void fullCoverageLeavesNothingEvenWhenSplitInSeveralRangesOrWiderThanTheDays() {
        assertThat(UnchargedDays.left(FROM, TO, List.of(range("2026-08-01", "2026-12-31")))).isEmpty();
        assertThat(UnchargedDays.left(FROM, TO, List.of(range("2026-09-01", "2026-09-14"),
                range("2026-09-15", "2026-09-30")))).isEmpty();
    }

    @Test
    void aSingleDayIsLeftOrCovered() {
        LocalDate day = LocalDate.parse("2026-09-15");
        assertThat(UnchargedDays.left(day, day, List.of())).containsExactly(new Range(day, day));
        assertThat(UnchargedDays.left(day, day, List.of(range("2026-09-15", "2026-09-15")))).isEmpty();
    }
}
