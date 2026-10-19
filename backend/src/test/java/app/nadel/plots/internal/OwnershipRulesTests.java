package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.plots.internal.OwnershipRules.DateProblem;
import app.nadel.plots.internal.OwnershipRules.Kind;
import app.nadel.plots.internal.OwnershipRules.Period;
import app.nadel.plots.internal.OwnershipRules.Violation;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The ownership timeline rules: shares add up to one whole, no overlaps, no future dates, the date of a change. */
class OwnershipRulesTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    private static Period period(Object key, UUID member, String share, String from, String to) {
        return new Period(key, member, Share.parse(share), LocalDate.parse(from), to == null ? null : LocalDate.parse(to));
    }

    private static List<Kind> kinds(List<Violation> violations) {
        return violations.stream().map(Violation::kind).toList();
    }

    // ---- shares add up to one whole

    @Test
    void threeThirdsAreOneWholeExactly() {
        var periods = List.of(
                period(0, A, "1/3", "2025-01-01", null),
                period(1, B, "1/3", "2025-01-01", null),
                period(2, C, "1/3", "2025-01-01", null));

        assertThat(OwnershipRules.checkTimeline(periods, TODAY)).isEmpty();
    }

    @Test
    void aHalfAndAThirdAreNotOneWhole() {
        var periods = List.of(
                period(0, A, "1/2", "2025-01-01", null),
                period(1, B, "1/3", "2025-01-01", null));

        assertThat(OwnershipRules.checkTimeline(periods, TODAY)).containsExactly(new Violation(Kind.SHARES_NOT_ONE, null));
    }

    @Test
    void moreThanOneWholeIsRefusedToo() {
        var periods = List.of(
                period(0, A, "2/3", "2025-01-01", null),
                period(1, B, "2/3", "2025-01-01", null));

        assertThat(kinds(OwnershipRules.checkTimeline(periods, TODAY))).containsExactly(Kind.SHARES_NOT_ONE);
    }

    @Test
    void aShareWrittenUnreducedCountsByValue() {
        // 2/4 is read as 1/2
        var periods = List.of(
                period(0, A, "2/4", "2025-01-01", null),
                period(1, B, "1/2", "2025-01-01", null));

        assertThat(OwnershipRules.checkTimeline(periods, TODAY)).isEmpty();
        assertThat(periods.getFirst().share()).hasToString("1/2");
    }

    @Test
    void aSumThatDoesNotFitAFractionIsNotOneWhole() {
        // pairwise coprime denominators: the exact sum overflows the int fraction after a few of them
        var periods = List.of(
                period(0, A, "1/997", "2025-01-01", null),
                period(1, B, "1/991", "2025-01-01", null),
                period(2, C, "1/983", "2025-01-01", null),
                period(3, UUID.randomUUID(), "1/977", "2025-01-01", null),
                period(4, UUID.randomUUID(), "1/971", "2025-01-01", null));

        assertThat(kinds(OwnershipRules.checkTimeline(periods, TODAY))).containsExactly(Kind.SHARES_NOT_ONE);
    }

    @Test
    void aTimelineWithoutOwnersIsValid() {
        assertThat(OwnershipRules.checkTimeline(List.of(), TODAY)).isEmpty();
    }

    @Test
    void everyDayWithOwnersMustAddUpNotOnlyTheCurrentOnes() {
        // the past segment adds up to 1/2: a correction that broke the history must be caught
        var periods = List.of(
                period(0, A, "1/2", "2020-01-01", "2024-12-31"),
                period(1, B, "1/1", "2025-01-01", null));

        assertThat(kinds(OwnershipRules.checkTimeline(periods, TODAY))).containsExactly(Kind.SHARES_NOT_ONE);
    }

    @Test
    void aGapWithoutOwnersBetweenTwoOwnersIsAllowed() {
        var periods = List.of(
                period(0, A, "1/1", "2020-01-01", "2022-06-30"),
                period(1, B, "1/1", "2023-03-01", null));

        assertThat(OwnershipRules.checkTimeline(periods, TODAY)).isEmpty();
    }

    @Test
    void successiveOwnersAdjacentWithoutGapAreValid() {
        var periods = List.of(
                period(0, A, "1/1", "2020-01-01", "2025-05-31"),
                period(1, B, "1/2", "2025-06-01", null),
                period(2, C, "1/2", "2025-06-01", null));

        assertThat(OwnershipRules.checkTimeline(periods, TODAY)).isEmpty();
    }

    @Test
    void sharesThatChangeInTheMiddleOfAnotherOwnersPeriodAreCheckedPerSegment() {
        // A owns everything 2020..2024; B and C split from 2022: from 2022 the sum is 1 + 1/2 + 1/2
        var periods = List.of(
                period(0, A, "1/1", "2020-01-01", "2024-12-31"),
                period(1, B, "1/2", "2022-01-01", null),
                period(2, C, "1/2", "2022-01-01", null));

        assertThat(kinds(OwnershipRules.checkTimeline(periods, TODAY))).containsExactly(Kind.SHARES_NOT_ONE);
    }

    @Test
    void theFirstDayWithWrongSharesIsTheEarliestBoundaryThatFails() {
        var periods = List.of(
                period(0, A, "1/1", "2020-01-01", "2022-12-31"),
                period(1, B, "1/1", "2022-06-01", null));

        assertThat(OwnershipRules.firstDayWithWrongShares(periods)).contains(LocalDate.of(2022, 6, 1));
        assertThat(OwnershipRules.firstDayWithWrongShares(List.of(
                period(0, A, "1/1", "2020-01-01", "2022-12-31"),
                period(1, B, "1/1", "2023-01-01", null)))).isEmpty();
    }

    // ---- overlap of one member

    @Test
    void theSameMemberInOverlappingPeriodsIsRefusedWithTheLaterRowsKey() {
        var periods = List.of(
                period("old", A, "1/1", "2020-01-01", "2025-12-31"),
                period("new", A, "1/1", "2025-06-01", null));

        List<Violation> violations = OwnershipRules.checkTimeline(periods, TODAY);

        assertThat(violations).contains(new Violation(Kind.MEMBER_OVERLAP, "new"));
    }

    @Test
    void theSameMemberInAdjacentPeriodsIsFine() {
        var periods = List.of(
                period(0, A, "1/1", "2020-01-01", "2025-05-31"),
                period(1, A, "1/2", "2025-06-01", null),
                period(2, B, "1/2", "2025-06-01", null));

        assertThat(OwnershipRules.checkTimeline(periods, TODAY)).isEmpty();
    }

    @Test
    void twoOpenPeriodsOfOneMemberOverlap() {
        var periods = List.of(
                period(0, A, "1/2", "2025-01-01", null),
                period(1, A, "1/2", "2025-03-01", null));

        assertThat(kinds(OwnershipRules.checkTimeline(periods, TODAY))).contains(Kind.MEMBER_OVERLAP);
    }

    // ---- future dates

    @Test
    void aPeriodStartingTomorrowIsRefused() {
        var periods = List.of(period("p", A, "1/1", "2026-10-20", null));

        assertThat(OwnershipRules.checkTimeline(periods, TODAY)).containsExactly(new Violation(Kind.STARTS_IN_FUTURE, "p"));
    }

    @Test
    void aPeriodStartingTodayIsFine() {
        assertThat(OwnershipRules.checkTimeline(List.of(period("p", A, "1/1", "2026-10-19", null)), TODAY)).isEmpty();
    }

    @Test
    void aPeriodEndingInTheFutureIsRefused() {
        var periods = List.of(period("p", A, "1/1", "2025-01-01", "2026-10-20"));

        assertThat(OwnershipRules.checkTimeline(periods, TODAY)).containsExactly(new Violation(Kind.ENDS_IN_FUTURE, "p"));
    }

    // ---- the date of a change of owner

    @Test
    void theNewOwnersStartTheDayAfterThePreviousOnesEnd() {
        assertThat(OwnershipRules.lastDayOfPreviousOwners(LocalDate.of(2026, 3, 1))).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(OwnershipRules.lastDayOfPreviousOwners(LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(OwnershipRules.lastDayOfPreviousOwners(LocalDate.of(2024, 3, 1))).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(OwnershipRules.lastDayOfPreviousOwners(LocalDate.of(2026, 7, 15))).isEqualTo(LocalDate.of(2026, 7, 14));
    }

    @Test
    void aChangeDateLaterThanEveryStartAndEndIsAccepted() {
        var existing = List.of(
                period(0, A, "1/1", "2015-06-01", "2025-05-31"),
                period(1, B, "1/1", "2025-06-01", null));

        assertThat(OwnershipRules.checkChangeDate(LocalDate.of(2026, 3, 1), existing, TODAY)).isEmpty();
        assertThat(OwnershipRules.checkChangeDate(LocalDate.of(2025, 6, 2), existing, TODAY)).isEmpty();
        assertThat(OwnershipRules.checkChangeDate(TODAY, existing, TODAY)).isEmpty();
    }

    @Test
    void aChangeDateInTheFutureIsRefused() {
        assertThat(OwnershipRules.checkChangeDate(TODAY.plusDays(1), List.of(), TODAY)).contains(DateProblem.IN_FUTURE);
    }

    @Test
    void aChangeDateNotAfterTheStartOfACurrentOwnerIsRefused() {
        var existing = List.of(period(0, B, "1/1", "2025-06-01", null));

        assertThat(OwnershipRules.checkChangeDate(LocalDate.of(2025, 6, 1), existing, TODAY))
                .contains(DateProblem.NOT_AFTER_CURRENT_START);
        assertThat(OwnershipRules.checkChangeDate(LocalDate.of(2025, 5, 1), existing, TODAY))
                .contains(DateProblem.NOT_AFTER_CURRENT_START);
    }

    @Test
    void aChangeDateNotAfterTheEndOfAPastOwnerIsRefused() {
        // no current owner; the last one ended on 2025-05-31
        var existing = List.of(period(0, A, "1/1", "2015-06-01", "2025-05-31"));

        assertThat(OwnershipRules.checkChangeDate(LocalDate.of(2025, 5, 31), existing, TODAY))
                .contains(DateProblem.NOT_AFTER_PAST_END);
        assertThat(OwnershipRules.checkChangeDate(LocalDate.of(2025, 6, 1), existing, TODAY)).isEqualTo(Optional.empty());
    }

    @Test
    void aPlotWithoutAnyOwnerAcceptsAnyPastDate() {
        assertThat(OwnershipRules.checkChangeDate(LocalDate.of(1990, 1, 1), List.of(), TODAY)).isEmpty();
    }
}
