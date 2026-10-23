package app.nadel.ledger.internal;

import app.nadel.tariffs.DaySet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The days of a "not charged" item of a run (a losses node, a common consumer) that the booked runs left uncharged:
 * what a run or a retry narrows the item to when it charged some of its days ({@code LossesRuns.settle},
 * {@code CommonConsumerRuns.settle}).
 */
final class UnchargedDays {

    private UnchargedDays() {
    }

    /**
     * The runs of days from {@code from} to {@code to} that none of {@code charged} covers, oldest first.
     * {@code charged} is the ranges of the booked runs that charged the node or consumer and meet the days, oldest
     * first; they never overlap (the exclusion constraints of the run tables), and may start before {@code from} or end
     * after {@code to}. Every day uncharged: the one range {@code from} to {@code to}; every day charged: none.
     */
    static List<DaySet.Range> left(LocalDate from, LocalDate to, List<DaySet.Range> charged) {
        List<DaySet.Range> left = new ArrayList<>();
        LocalDate next = from;
        for (DaySet.Range range : charged) {
            if (next.isAfter(to)) {
                break;
            }
            if (range.from().isAfter(next)) {
                left.add(new DaySet.Range(next, min(range.from().minusDays(1), to)));
            }
            if (!range.to().isBefore(next)) {
                next = range.to().plusDays(1);
            }
        }
        if (!next.isAfter(to)) {
            left.add(new DaySet.Range(next, to));
        }
        return left;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
