package app.nadel.documents.internal;

import app.nadel.ledger.InvoiceLedger;
import app.nadel.ledger.MonthStatus;
import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One invoice version with everything it prints (feature 016 §2.3, technical considerations 2.5), the names in
 * plaintext: what {@link InvoiceSnapshots} stores (the names sealed) and opens again, what {@link InvoiceLayout} draws
 * and what {@code GET /invoices/{id}} answers. Pure value. Holds personal data (the owners', the chairmen's and the
 * treasurers' names): never log it.
 *
 * @param id            the invoice row
 * @param version       the version, 1 for the first
 * @param number        {@code YYYY-MM/plot number}
 * @param month         the billing month
 * @param monthStatus   the month's status as of {@code asOf}; an open month's invoice states «станом на»
 * @param asOf          the moment the figures were read
 * @param issuedAt      the moment it was issued (the footer, the PDF's dates)
 * @param issuedOn      the day of issue in Kyiv
 * @param cooperative   the requisites as printed
 * @param plot          the plot as printed
 * @param owners        the current owners with their shares, the earliest first
 * @param chairmen      the names of the active chairmen
 * @param treasurers    the names of the active treasurers
 * @param types         the rows per charge type, in the charge type order
 * @param lines         the charges booked in the month
 * @param receivedAfter the payments booked after the month and received before the issue
 * @param totals        the totals
 * @param qr            the payment QR code; null when nothing is due
 */
record InvoiceDocument(UUID id, int version, String number, YearMonth month, MonthStatus monthStatus, Instant asOf,
        Instant issuedAt, LocalDate issuedOn, Cooperative cooperative, Plot plot, List<Owner> owners,
        List<String> chairmen, List<String> treasurers, List<TypeRow> types, List<InvoiceLedger.Line> lines,
        List<InvoiceLedger.LaterPayment> receivedAfter, Totals totals, @Nullable Qr qr) {

    InvoiceDocument {
        Objects.requireNonNull(id, "id");
        if (version < 1) {
            throw new IllegalArgumentException("version is at least 1");
        }
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(monthStatus, "monthStatus");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(issuedOn, "issuedOn");
        Objects.requireNonNull(cooperative, "cooperative");
        Objects.requireNonNull(plot, "plot");
        owners = List.copyOf(owners);
        chairmen = List.copyOf(chairmen);
        treasurers = List.copyOf(treasurers);
        types = List.copyOf(types);
        lines = List.copyOf(lines);
        receivedAfter = List.copyOf(receivedAfter);
        Objects.requireNonNull(totals, "totals");
    }

    /**
     * The requisites printed on top.
     *
     * @param officialName the official name, or the cooperative's name when none was entered
     * @param edrpou       the ЄДРПОУ; null when not set
     * @param address      the legal address; null when not set
     * @param bankName     the bank; null when not set
     * @param iban         the IBAN; null when not set
     */
    record Cooperative(String officialName, @Nullable String edrpou, @Nullable String address,
            @Nullable String bankName, @Nullable String iban) {

        Cooperative {
            Objects.requireNonNull(officialName, "officialName");
        }
    }

    /** The plot: its id, number and street (null when none). */
    record Plot(UUID id, String number, @Nullable String street) {

        Plot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(number, "number");
        }
    }

    /** An owner with the full name and the share {@code numerator/denominator}. */
    record Owner(UUID memberId, String name, int numerator, int denominator) {

        Owner {
            Objects.requireNonNull(memberId, "memberId");
            Objects.requireNonNull(name, "name");
        }

        /** {@code 1/2}. */
        String share() {
            return numerator + "/" + denominator;
        }
    }

    /**
     * One charge type: its id, its code (a seeded type), the name printed and the four figures.
     */
    record TypeRow(UUID chargeTypeId, @Nullable String code, String name, Money start, Money charged, Money paid,
            Money end) {

        TypeRow {
            Objects.requireNonNull(chargeTypeId, "chargeTypeId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(charged, "charged");
            Objects.requireNonNull(paid, "paid");
            Objects.requireNonNull(end, "end");
        }
    }

    /** The totals: the end debt, the prepayment («Аванс»), the money received after the month and «До сплати». */
    record Totals(Money endDebt, Money prepayment, Money receivedAfter, Money due) {

        Totals {
            Objects.requireNonNull(endDebt, "endDebt");
            Objects.requireNonNull(prepayment, "prepayment");
            Objects.requireNonNull(receivedAfter, "receivedAfter");
            Objects.requireNonNull(due, "due");
        }
    }

    /** The payment QR code: the stored link (what the code holds) and the purpose of payment it carries. */
    record Qr(String link, String purpose) {

        Qr {
            Objects.requireNonNull(link, "link");
            Objects.requireNonNull(purpose, "purpose");
        }
    }

    /** The name of the charge type of a line, as printed in the type rows (the stored name of an unknown type). */
    String typeName(UUID chargeTypeId) {
        return types.stream().filter(type -> type.chargeTypeId().equals(chargeTypeId)).map(TypeRow::name)
                .findFirst().orElse("");
    }
}
