package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.ledger.InvoiceLedger.LaterPayment;
import app.nadel.ledger.InvoiceLedger.TypeMovement;
import app.nadel.ledger.internal.MonthMovements.AllocationEntry;
import app.nadel.ledger.internal.MonthMovements.ChargeEntry;
import app.nadel.ledger.internal.MonthMovements.ClosedMonth;
import app.nadel.ledger.internal.MonthMovements.PaymentEntry;
import app.nadel.ledger.internal.MonthMovements.Result;
import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The booking-month rule of an invoice (feature 016 §2.3, technical considerations 2.3, money rules «Рахунки та
 * довідки»), with the worked examples 1 to 5 of the requirement §6: plot 9, charge types Membership then Electricity,
 * October 2026, issued 05.11.2026.
 */
class MonthMovementsTests {

    private static final UUID MEMBERSHIP = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ELECTRICITY = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
    /** The issue on 05.11.2026. */
    private static final Instant ISSUED = Instant.parse("2026-11-05T09:00:00Z");

    private final List<ClosedMonth> closed = new ArrayList<>();
    private final List<ChargeEntry> charges = new ArrayList<>();
    private final List<AllocationEntry> allocations = new ArrayList<>();
    private final List<PaymentEntry> payments = new ArrayList<>();

    private static Money m(String amount) {
        return Money.parse(amount);
    }

    private static Instant at(String day) {
        return Instant.parse(day + "T10:00:00Z");
    }

    private void charge(UUID type, YearMonth bookedIn, String amount, String createdOn) {
        charges.add(new ChargeEntry(type, bookedIn, m(amount), at(createdOn)));
    }

    private void allocate(UUID type, YearMonth paymentMonth, YearMonth chargeMonth, String amount, String createdOn) {
        allocations.add(new AllocationEntry(type, paymentMonth, chargeMonth, m(amount), at(createdOn)));
    }

    private UUID pay(YearMonth bookedIn, String receivedOn, String amount) {
        UUID id = UUID.randomUUID();
        payments.add(new PaymentEntry(id, null, bookedIn, LocalDate.parse(receivedOn), m(amount), at(receivedOn)));
        return id;
    }

    private Result october(Instant asOf) {
        return MonthMovements.of(OCTOBER, asOf, closed, charges, allocations, payments);
    }

    private static TypeMovement type(Result result, UUID id) {
        return result.types().stream().filter(type -> type.chargeTypeId().equals(id)).findFirst().orElseThrow();
    }

    private static Money sum(Result result, java.util.function.Function<TypeMovement, Money> figure) {
        return result.types().stream().map(figure).reduce(Money.ZERO, Money::plus);
    }

    private static void figures(TypeMovement type, String start, String charged, String paid, String end) {
        assertThat(type.start()).isEqualTo(m(start));
        assertThat(type.charged()).isEqualTo(m(charged));
        assertThat(type.paid()).isEqualTo(m(paid));
        assertThat(type.end()).isEqualTo(m(end));
    }

    /** Example 1: start 0.00; charged 413.26; paid 0.00; end 413.26. */
    private void example1() {
        charge(MEMBERSHIP, OCTOBER, "75.00", "2026-10-01");
        charge(ELECTRICITY, OCTOBER, "338.26", "2026-10-06");
    }

    @Test
    void example1NoPaymentsTheWholeChargeOfTheMonthIsTheEndDebt() {
        example1();

        Result result = october(ISSUED);

        figures(type(result, MEMBERSHIP), "0.00", "75.00", "0.00", "75.00");
        figures(type(result, ELECTRICITY), "0.00", "338.26", "0.00", "338.26");
        assertThat(sum(result, TypeMovement::start)).isEqualTo(m("0.00"));
        assertThat(sum(result, TypeMovement::charged)).isEqualTo(m("413.26"));
        assertThat(sum(result, TypeMovement::paid)).isEqualTo(m("0.00"));
        assertThat(sum(result, TypeMovement::end)).isEqualTo(m("413.26"));
        assertThat(result.prepaymentAtEnd()).isEqualTo(Money.ZERO);
        assertThat(result.receivedAfter()).isEmpty();
    }

    @Test
    void example2AnOctoberPaymentOnTheSeptemberDebtCountsAsPaidInOctober() {
        charge(ELECTRICITY, SEPTEMBER, "1135.08", "2026-09-06");
        closed.add(new ClosedMonth(SEPTEMBER, at("2026-10-03")));
        example1();
        pay(OCTOBER, "2026-10-20", "1500.00");
        allocate(ELECTRICITY, OCTOBER, SEPTEMBER, "1135.08", "2026-10-20");
        allocate(MEMBERSHIP, OCTOBER, OCTOBER, "75.00", "2026-10-20");
        allocate(ELECTRICITY, OCTOBER, OCTOBER, "289.92", "2026-10-20");

        Result result = october(ISSUED);

        figures(type(result, MEMBERSHIP), "0.00", "75.00", "75.00", "0.00");
        figures(type(result, ELECTRICITY), "1135.08", "338.26", "1425.00", "48.34");
        assertThat(sum(result, TypeMovement::start)).isEqualTo(m("1135.08"));
        assertThat(sum(result, TypeMovement::charged)).isEqualTo(m("413.26"));
        assertThat(sum(result, TypeMovement::paid)).isEqualTo(m("1500.00"));
        assertThat(sum(result, TypeMovement::end)).isEqualTo(m("48.34"));
        assertThat(result.prepaymentAtEnd()).isEqualTo(Money.ZERO);
        assertThat(result.receivedAfter()).isEmpty();
    }

    @Test
    void example3APaymentOnTheThirdOfNovemberIsReceivedAfterOctoberNotPaidInIt() {
        example1();
        UUID november = pay(NOVEMBER, "2026-11-03", "413.26");
        allocate(MEMBERSHIP, NOVEMBER, OCTOBER, "75.00", "2026-11-03");
        allocate(ELECTRICITY, NOVEMBER, OCTOBER, "338.26", "2026-11-03");

        Result result = october(ISSUED);

        assertThat(sum(result, TypeMovement::paid)).isEqualTo(m("0.00"));
        assertThat(sum(result, TypeMovement::end)).isEqualTo(m("413.26"));
        assertThat(result.receivedAfter()).containsExactly(
                new LaterPayment(november, LocalDate.of(2026, 11, 3), NOVEMBER, m("413.26")));
        assertThat(result.prepaymentAtEnd()).isEqualTo(Money.ZERO);
    }

    @Test
    void example4AnOverpaymentIsThePrepaymentAtTheEndOfTheMonth() {
        charge(MEMBERSHIP, OCTOBER, "75.00", "2026-10-01");
        pay(OCTOBER, "2026-10-10", "175.00");
        allocate(MEMBERSHIP, OCTOBER, OCTOBER, "75.00", "2026-10-10");

        Result result = october(ISSUED);

        figures(type(result, MEMBERSHIP), "0.00", "75.00", "75.00", "0.00");
        assertThat(result.prepaymentAtEnd()).isEqualTo(m("100.00"));
        assertThat(result.receivedAfter()).isEmpty();
    }

    /**
     * Example 5: October is closed on 03.11; on 06.11 a recalculation lowers the October electricity charge by 20.00,
     * booked in November, and the money it no longer needs is released. The October invoice issued on 05.11 and
     * re-issued on 07.11 has the same figures; the November invoice carries the adjustment and the release.
     */
    @Test
    void example5ACorrectionBookedAfterTheClosingLandsInNovemberAndOctoberNeverChanges() {
        example1();
        pay(OCTOBER, "2026-10-25", "413.26");
        allocate(MEMBERSHIP, OCTOBER, OCTOBER, "75.00", "2026-10-25");
        allocate(ELECTRICITY, OCTOBER, OCTOBER, "338.26", "2026-10-25");
        closed.add(new ClosedMonth(OCTOBER, at("2026-11-03")));
        Result before = october(ISSUED);

        charge(ELECTRICITY, NOVEMBER, "-20.00", "2026-11-06");
        allocate(ELECTRICITY, OCTOBER, OCTOBER, "-20.00", "2026-11-06");
        Instant reissue = at("2026-11-07");
        Result after = october(reissue);

        assertThat(after).isEqualTo(before);
        figures(type(after, MEMBERSHIP), "0.00", "75.00", "75.00", "0.00");
        figures(type(after, ELECTRICITY), "0.00", "338.26", "338.26", "0.00");
        assertThat(after.prepaymentAtEnd()).isEqualTo(Money.ZERO);

        Result november = MonthMovements.of(NOVEMBER, reissue, closed, charges, allocations, payments);
        figures(type(november, ELECTRICITY), "0.00", "-20.00", "-20.00", "0.00");
        figures(type(november, MEMBERSHIP), "0.00", "0.00", "0.00", "0.00");
        assertThat(november.prepaymentAtEnd()).isEqualTo(m("20.00"));
    }

    @Test
    void anAllocationCreatedWhileTheMonthWasOpenCountsInTheLaterOfThePaymentAndTheChargeMonth() {
        // an October payment placed on a November charge counts in November
        AllocationEntry forward = new AllocationEntry(ELECTRICITY, OCTOBER, NOVEMBER, m("10.00"), at("2026-11-02"));
        assertThat(MonthMovements.countedIn(forward, List.of())).isEqualTo(NOVEMBER);
        // a September payment placed on a September charge after September was closed counts in the open month
        AllocationEntry late = new AllocationEntry(ELECTRICITY, SEPTEMBER, SEPTEMBER, m("10.00"), at("2026-10-15"));
        assertThat(MonthMovements.countedIn(late, List.of(new ClosedMonth(SEPTEMBER, at("2026-10-03")))))
                .isEqualTo(OCTOBER);
        // ... and in September when it was created before the closing
        AllocationEntry early = new AllocationEntry(ELECTRICITY, SEPTEMBER, SEPTEMBER, m("10.00"), at("2026-09-30"));
        assertThat(MonthMovements.countedIn(early, List.of(new ClosedMonth(SEPTEMBER, at("2026-10-03")))))
                .isEqualTo(SEPTEMBER);
    }

    @Test
    void theOpenMonthIsTheMonthAfterTheLastClosedByThen() {
        List<ClosedMonth> months = List.of(new ClosedMonth(SEPTEMBER, at("2026-10-03")),
                new ClosedMonth(OCTOBER, at("2026-11-03")));

        assertThat(MonthMovements.openMonthAt(at("2026-10-01"), months)).isNull();
        assertThat(MonthMovements.openMonthAt(at("2026-10-03"), months)).isEqualTo(OCTOBER);
        assertThat(MonthMovements.openMonthAt(at("2026-11-04"), months)).isEqualTo(NOVEMBER);
    }

    @Test
    void rowsCreatedAfterTheMomentOfTheInvoiceAreLeftOut() {
        example1();
        pay(OCTOBER, "2026-10-20", "75.00");
        allocate(MEMBERSHIP, OCTOBER, OCTOBER, "75.00", "2026-10-20");

        Result early = october(at("2026-10-10"));

        figures(type(early, MEMBERSHIP), "0.00", "75.00", "0.00", "75.00");
        figures(type(early, ELECTRICITY), "0.00", "338.26", "0.00", "338.26");
        assertThat(early.prepaymentAtEnd()).isEqualTo(Money.ZERO);
    }

    @Test
    void aLaterPaymentReversedByTheInvoiceIsNotReceivedAfterTheMonth() {
        example1();
        UUID kept = pay(NOVEMBER, "2026-11-02", "100.00");
        UUID reversed = pay(NOVEMBER, "2026-11-01", "50.00");
        payments.add(new PaymentEntry(UUID.randomUUID(), reversed, NOVEMBER, LocalDate.of(2026, 11, 1), m("-50.00"),
                at("2026-11-04")));

        assertThat(october(ISSUED).receivedAfter()).extracting(LaterPayment::paymentId).containsExactly(kept);
        assertThat(october(at("2026-11-03")).receivedAfter()).extracting(LaterPayment::paymentId)
                .containsExactly(reversed, kept);
    }
}
