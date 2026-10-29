package app.nadel.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.documents.internal.InvoiceFigures.TypeRow;
import app.nadel.ledger.InvoiceLedger;
import app.nadel.ledger.InvoiceLedger.LaterPayment;
import app.nadel.ledger.InvoiceLedger.Line;
import app.nadel.ledger.InvoiceLedger.LineKind;
import app.nadel.ledger.InvoiceLedger.LineSource;
import app.nadel.ledger.InvoiceLedger.TypeMovement;
import app.nadel.ledger.MonthStatus;
import app.nadel.money.Money;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The figures of an invoice (feature 016 §2.3) with the worked examples 1 to 5 of the requirement §6: plot 9, charge
 * types Membership then Electricity, October 2026, issued 05.11.2026. The ledger's booking-month rule is
 * {@code MonthMovementsTests}; here the rows are ordered and the totals and «До сплати» worked out.
 */
class InvoiceCalculatorTests {

    private static final UUID MEMBERSHIP = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ELECTRICITY = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID WATER = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID PLOT = UUID.randomUUID();
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

    /** The cooperative's order: Membership, Electricity, Water. */
    private static final List<ChargeTypeFacts> TYPES = List.of(
            new ChargeTypeFacts(MEMBERSHIP, "MEMBERSHIP", "Membership", false, true, false, 1),
            new ChargeTypeFacts(ELECTRICITY, "ELECTRICITY", "Electricity", false, true, false, 2),
            new ChargeTypeFacts(WATER, "WATER", "Water", false, true, false, 4));

    private static Money m(String amount) {
        return Money.parse(amount);
    }

    private static TypeMovement movement(UUID type, String start, String charged, String paid, String end) {
        return new TypeMovement(type, m(start), m(charged), m(paid), m(end));
    }

    private static Line line(UUID type, LineKind kind, String amount, YearMonth corrects) {
        return new Line(UUID.randomUUID(), type, kind, LineSource.TARIFF, m(amount), LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31), corrects, List.of(), List.of(), null, null, null);
    }

    private static InvoiceLedger ledger(YearMonth month, List<TypeMovement> types, List<Line> lines,
            String prepayment, List<LaterPayment> later) {
        return new InvoiceLedger(PLOT, month, MonthStatus.CLOSED, null, types, lines, m(prepayment), later, List.of());
    }

    private static void row(TypeRow row, UUID type, String start, String charged, String paid, String end) {
        assertThat(row.chargeTypeId()).isEqualTo(type);
        assertThat(row.start()).isEqualTo(m(start));
        assertThat(row.charged()).isEqualTo(m(charged));
        assertThat(row.paid()).isEqualTo(m(paid));
        assertThat(row.end()).isEqualTo(m(end));
    }

    private static Money total(InvoiceFigures figures, java.util.function.Function<TypeRow, Money> figure) {
        return figures.types().stream().map(figure).reduce(Money.ZERO, Money::plus);
    }

    @Test
    void example1StartZeroChargedAndDue41326WithAQrCode() {
        InvoiceFigures figures = InvoiceCalculator.of(ledger(OCTOBER, List.of(
                movement(ELECTRICITY, "0.00", "338.26", "0.00", "338.26"),
                movement(MEMBERSHIP, "0.00", "75.00", "0.00", "75.00")),
                List.of(line(ELECTRICITY, LineKind.REGULAR, "338.26", null),
                        line(MEMBERSHIP, LineKind.REGULAR, "75.00", null)), "0.00", List.of()), TYPES);

        assertThat(figures.types()).hasSize(2);
        row(figures.types().get(0), MEMBERSHIP, "0.00", "75.00", "0.00", "75.00");
        row(figures.types().get(1), ELECTRICITY, "0.00", "338.26", "0.00", "338.26");
        assertThat(total(figures, TypeRow::start)).isEqualTo(m("0.00"));
        assertThat(total(figures, TypeRow::charged)).isEqualTo(m("413.26"));
        assertThat(total(figures, TypeRow::paid)).isEqualTo(m("0.00"));
        assertThat(figures.endDebt()).isEqualTo(m("413.26"));
        assertThat(figures.due()).isEqualTo(m("413.26"));
        assertThat(figures.payable()).isTrue();
        assertThat(figures.lines()).extracting(Line::chargeTypeId).containsExactly(MEMBERSHIP, ELECTRICITY);
    }

    @Test
    void example2StartTheSeptemberDebtPaid1500AndDueTheElectricityLeft() {
        InvoiceFigures figures = InvoiceCalculator.of(ledger(OCTOBER, List.of(
                movement(MEMBERSHIP, "0.00", "75.00", "75.00", "0.00"),
                movement(ELECTRICITY, "1135.08", "338.26", "1425.00", "48.34")), List.of(), "0.00", List.of()),
                TYPES);

        assertThat(total(figures, TypeRow::start)).isEqualTo(m("1135.08"));
        assertThat(total(figures, TypeRow::charged)).isEqualTo(m("413.26"));
        assertThat(total(figures, TypeRow::paid)).isEqualTo(m("1500.00"));
        assertThat(figures.endDebt()).isEqualTo(m("48.34"));
        row(figures.types().get(1), ELECTRICITY, "1135.08", "338.26", "1425.00", "48.34");
        assertThat(figures.due()).isEqualTo(m("48.34"));
    }

    @Test
    void example3APaymentReceivedAfterTheMonthLowersTheDueToZeroAndThereIsNoQrCode() {
        LaterPayment third = new LaterPayment(UUID.randomUUID(), LocalDate.of(2026, 11, 3), NOVEMBER, m("413.26"));
        InvoiceFigures figures = InvoiceCalculator.of(ledger(OCTOBER, List.of(
                movement(MEMBERSHIP, "0.00", "75.00", "0.00", "75.00"),
                movement(ELECTRICITY, "0.00", "338.26", "0.00", "338.26")), List.of(), "0.00", List.of(third)),
                TYPES);

        assertThat(figures.endDebt()).isEqualTo(m("413.26"));
        assertThat(figures.receivedAfter()).containsExactly(third);
        assertThat(figures.received()).isEqualTo(m("413.26"));
        assertThat(figures.due()).isEqualTo(m("0.00"));
        assertThat(figures.payable()).isFalse();
    }

    @Test
    void example4APrepaymentIsShownAndNothingIsDue() {
        InvoiceFigures figures = InvoiceCalculator.of(ledger(OCTOBER, List.of(
                movement(MEMBERSHIP, "0.00", "75.00", "75.00", "0.00")), List.of(), "100.00", List.of()), TYPES);

        assertThat(figures.endDebt()).isEqualTo(m("0.00"));
        assertThat(figures.prepayment()).isEqualTo(m("100.00"));
        assertThat(figures.due()).isEqualTo(m("0.00"));
        assertThat(figures.payable()).isFalse();
    }

    @Test
    void example5TheNovemberInvoiceShowsTheAdjustmentForOctober() {
        Line adjustment = line(ELECTRICITY, LineKind.ADJUSTMENT, "-20.00", OCTOBER);
        InvoiceFigures november = InvoiceCalculator.of(ledger(NOVEMBER, List.of(
                movement(MEMBERSHIP, "0.00", "0.00", "0.00", "0.00"),
                movement(ELECTRICITY, "0.00", "-20.00", "-20.00", "0.00")), List.of(adjustment), "20.00",
                List.of()), TYPES);

        // the type without a figure and without a line is left out
        assertThat(november.types()).hasSize(1);
        row(november.types().getFirst(), ELECTRICITY, "0.00", "-20.00", "-20.00", "0.00");
        assertThat(november.lines()).containsExactly(adjustment);
        assertThat(november.lines().getFirst().corrects()).isEqualTo(OCTOBER);
        assertThat(november.prepayment()).isEqualTo(m("20.00"));
        assertThat(november.due()).isEqualTo(m("0.00"));
    }

    @Test
    void aTypeWithALineButNoFigureIsShownAndAnUnknownTypeComesLast() {
        UUID unknown = UUID.fromString("00000000-0000-0000-0000-000000000001");
        InvoiceFigures figures = InvoiceCalculator.of(ledger(OCTOBER, List.of(
                movement(unknown, "5.00", "0.00", "0.00", "5.00"),
                movement(WATER, "0.00", "10.00", "0.00", "10.00")),
                List.of(line(MEMBERSHIP, LineKind.REVERSAL, "0.00", null)), "0.00", List.of()), TYPES);

        assertThat(figures.types()).extracting(TypeRow::chargeTypeId).containsExactly(MEMBERSHIP, WATER, unknown);
        assertThat(figures.endDebt()).isEqualTo(m("15.00"));
    }

    @Test
    void theDueIsNeverBelowZero() {
        LaterPayment big = new LaterPayment(UUID.randomUUID(), LocalDate.of(2026, 11, 2), NOVEMBER, m("500.00"));
        InvoiceFigures figures = InvoiceCalculator.of(ledger(OCTOBER, List.of(
                movement(MEMBERSHIP, "0.00", "75.00", "0.00", "75.00")), List.of(), "0.00", List.of(big)), TYPES);

        assertThat(figures.due()).isEqualTo(Money.ZERO);
    }
}
