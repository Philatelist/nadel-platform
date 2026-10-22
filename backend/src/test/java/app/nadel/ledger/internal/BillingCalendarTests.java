package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.ledger.internal.BillingCalendar.MonthState;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which month a charge is booked in (feature 011 §2.2): its own month while open, otherwise the earliest open month
 * after it, otherwise a new month after the last closed one; never a month after the current one.
 */
class BillingCalendarTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    private static MonthState open(String month) {
        return new MonthState(YearMonth.parse(month), false);
    }

    private static MonthState closed(String month) {
        return new MonthState(YearMonth.parse(month), true);
    }

    private static YearMonth book(String date, List<MonthState> months) {
        return BillingCalendar.bookingMonth(LocalDate.parse(date), months, TODAY);
    }

    @Test
    void theFirstChargeOfACooperativeOpensItsOwnMonth() {
        assertThat(book("2026-09-01", List.of())).isEqualTo(YearMonth.of(2026, 9));
        assertThat(book("2025-01-15", List.of())).isEqualTo(YearMonth.of(2025, 1));
    }

    @Test
    void aChargeGoesToItsOwnMonthWhileItIsOpen() {
        List<MonthState> months = List.of(closed("2026-08"), open("2026-09"), open("2026-10"));

        assertThat(book("2026-09-28", months)).isEqualTo(YearMonth.of(2026, 9));
        assertThat(book("2026-10-01", months)).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void aMonthThatDoesNotExistAfterTheLastClosedOneIsOpenedForTheCharge() {
        assertThat(book("2026-10-05", List.of(closed("2026-09")))).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void aChargeOfAClosedMonthGoesToTheEarliestOpenMonthAfterIt() {
        // functional spec 2.2: a reading dated 28.09 approved after September closed is booked in October
        List<MonthState> months = List.of(closed("2026-08"), closed("2026-09"), open("2026-10"));

        assertThat(book("2026-09-28", months)).isEqualTo(YearMonth.of(2026, 10));
        assertThat(book("2026-08-01", months)).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void theEarliestOpenMonthIsChosenAlsoWhenAGapPrecedesIt() {
        // September closed, October never opened, November open: an existing open month comes first
        List<MonthState> months = List.of(open("2026-11"), closed("2026-09"));

        assertThat(BillingCalendar.bookingMonth(LocalDate.parse("2026-09-10"), months, LocalDate.parse("2026-11-03")))
                .isEqualTo(YearMonth.of(2026, 11));
    }

    @Test
    void withoutAnOpenMonthAfterItTheMonthAfterTheLastClosedOneIsOpened() {
        List<MonthState> months = List.of(closed("2026-08"), closed("2026-09"));

        assertThat(book("2026-09-28", months)).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void aMonthBeforeTheLastClosedOneCountsAsClosedEvenWhenItDoesNotExist() {
        // months close in order, so July (never opened) cannot take a charge once September is closed
        List<MonthState> months = List.of(closed("2026-09"), open("2026-10"));

        assertThat(book("2026-07-15", months)).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void neverAMonthAfterTheCurrentOne() {
        assertThat(book("2026-12-01", List.of())).isEqualTo(YearMonth.of(2026, 10));
        assertThat(book("2027-03-15", List.of(closed("2026-09"), open("2026-10")))).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void anOpenMonthAfterTheCurrentOneIsNotChosen() {
        // a month after the current one is never charged, even when such a row exists
        List<MonthState> months = List.of(closed("2026-09"), open("2026-11"));

        assertThat(book("2026-09-15", months)).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void theOrderOfTheMonthsDoesNotMatter() {
        List<MonthState> months = List.of(open("2026-10"), closed("2026-09"), closed("2026-08"));

        assertThat(book("2026-08-20", months)).isEqualTo(YearMonth.of(2026, 10));
    }
}
