package app.nadel.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.ledger.InvoiceLedger;
import app.nadel.ledger.InvoiceLedger.LineKind;
import app.nadel.ledger.InvoiceLedger.LineSource;
import app.nadel.ledger.MonthStatus;
import app.nadel.money.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import nadel.contract.model.Basis;
import nadel.contract.model.CalculationUnit;
import nadel.contract.model.ChargeCalculationPart;
import nadel.contract.model.Frequency;
import nadel.contract.model.InvoiceLine;
import nadel.contract.model.InvoiceSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stored form of an invoice version read back (feature 016 §2.4, technical considerations 2.5): a snapshot with
 * the stored calculation of every charge (tariff parts, the reading interval, losses and the month a correction is
 * for) reads back, through the jsonb text, to the same document; a snapshot without a required object or value is
 * refused instead of giving a different invoice; the contract form carries the stored calculation as codes.
 */
class InvoiceSnapshotFormTests {

    private static final UUID LOSSES = UUID.randomUUID();

    /** The invoice of {@link InvoicePdfTests} with a losses charge and a later payment added. */
    private static InvoiceDocument invoice() {
        InvoiceDocument base = InvoicePdfTests.invoice(2, MonthStatus.OPEN, "313.26", "0.00");
        List<InvoiceLedger.Line> lines = new ArrayList<>(base.lines());
        lines.add(new InvoiceLedger.Line(UUID.randomUUID(), LOSSES, LineKind.REGULAR, LineSource.LOSSES,
                Money.parse("16.92"), LocalDate.of(2026, 9, 6), LocalDate.of(2026, 10, 5), null,
                List.of(new InvoiceLedger.Part(null, null, null, null, null, 30, LocalDate.of(2026, 9, 6),
                        LocalDate.of(2026, 10, 5))),
                List.of(), "5.00", "338.300", null));
        List<InvoiceLedger.LaterPayment> later = List.of(new InvoiceLedger.LaterPayment(UUID.randomUUID(),
                LocalDate.of(2026, 11, 3), YearMonth.of(2026, 11), Money.parse("100.00")));
        return new InvoiceDocument(base.id(), base.version(), base.number(), base.month(), base.monthStatus(),
                base.asOf(), base.issuedAt(), base.issuedOn(), base.cooperative(), base.plot(), base.owners(),
                base.chairmen(), base.treasurers(), base.types(), lines, base.payments(), later,
                new InvoiceDocument.Totals(Money.parse("413.26"), Money.parse("0.00"), Money.parse("100.00"),
                        Money.parse("313.26")),
                base.qr());
    }

    /** The value as the jsonb column gives it back: compact JSON parsed again. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> stored(Map<String, Object> value) {
        return JsonMapper.shared().readValue(InvoiceSnapshots.compact(value), Map.class);
    }

    @Test
    void theStoredSnapshotReadsBackToTheSameDocument() {
        InvoiceDocument invoice = invoice();

        InvoiceDocument read = InvoiceSnapshots.read(invoice.id(), stored(InvoiceSnapshots.body(invoice)),
                stored(InvoiceSnapshots.persons(invoice)));

        assertThat(read).isEqualTo(invoice);
        InvoiceLedger.Line electricity = read.lines().stream()
                .filter(line -> line.source() == LineSource.CONSUMPTION).findFirst().orElseThrow();
        assertThat(electricity.readings()).extracting(InvoiceLedger.Reading::value)
                .containsExactly("8205.000", "8543.300");
        assertThat(electricity.parts()).singleElement().satisfies(part -> {
            assertThat(part.unit()).isEqualTo("KWH");
            assertThat(part.quantity()).isEqualTo("338.300");
            assertThat(part.days()).isEqualTo(30);
        });
        assertThat(read.lines().getLast().lossesPercent()).isEqualTo("5.00");
        assertThat(read.lines().getLast().parts().getFirst().basis()).isNull();
    }

    @Test
    void aSnapshotWithoutARequiredObjectOrValueIsRefused() {
        InvoiceDocument invoice = invoice();
        Map<String, Object> persons = stored(InvoiceSnapshots.persons(invoice));

        Map<String, Object> withoutTotals = new LinkedHashMap<>(stored(InvoiceSnapshots.body(invoice)));
        withoutTotals.remove("totals");
        assertThatThrownBy(() -> InvoiceSnapshots.read(invoice.id(), withoutTotals, persons))
                .isInstanceOf(IllegalStateException.class).hasMessage("The invoice snapshot has no object totals");

        Map<String, Object> withoutNumber = new LinkedHashMap<>(stored(InvoiceSnapshots.body(invoice)));
        withoutNumber.remove("number");
        assertThatThrownBy(() -> InvoiceSnapshots.read(invoice.id(), withoutNumber, persons))
                .isInstanceOf(IllegalStateException.class).hasMessage("The invoice snapshot has no value number");
    }

    @Test
    void theContractFormCarriesTheStoredCalculationAsCodes() {
        InvoiceDocument invoice = invoice();

        InvoiceSnapshot snapshot = DocumentsController.snapshot(invoice);

        List<InvoiceLine> lines = snapshot.getLines();
        assertThat(lines).hasSize(4);
        ChargeCalculationPart membership = lines.get(0).getParts().getFirst();
        assertThat(membership.getBasis()).isEqualTo(Basis.PER_AREA_SOTKA);
        assertThat(membership.getFrequency()).isEqualTo(Frequency.YEARLY);
        assertThat(membership.getUnit()).isEqualTo(CalculationUnit.SOTKA);
        assertThat(membership.getQuantity()).isEqualTo("6.00");
        assertThat(membership.getRate()).isEqualTo("50.0000");
        assertThat(membership.getDays()).isEqualTo(31);
        assertThat(lines.get(1).getCorrects()).isEqualTo("2026-09");
        assertThat(lines.get(2).getParts().getFirst().getUnit()).isEqualTo(CalculationUnit.KWH);
        assertThat(lines.get(2).getReadings()).extracting(reading -> reading.getValue())
                .containsExactly("8205.000", "8543.300");
        ChargeCalculationPart losses = lines.get(3).getParts().getFirst();
        assertThat(losses.getBasis()).isNull();
        assertThat(losses.getFrequency()).isNull();
        assertThat(losses.getUnit()).isNull();
        assertThat(lines.get(3).getLossesPercent()).isEqualTo("5.00");
        assertThat(snapshot.getReceivedAfter()).singleElement()
                .satisfies(payment -> assertThat(payment.getAmount()).isEqualTo("100.00"));
        assertThat(snapshot.getTotals().getDue()).isEqualTo("313.26");
        assertThat(snapshot.getVersion()).isEqualTo(2);
    }
}
