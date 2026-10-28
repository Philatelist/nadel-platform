package app.nadel.documents.internal;

import app.nadel.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Everything a printed cash receipt shows (feature 016 §2.8), already named: the input of {@link ReceiptLayout}. Pure
 * value; its text form is the identity of the PDF file (the same content gives the same {@code /ID}). Holds personal
 * data (the payer, the note, who accepted it): never log it.
 *
 * @param cooperative   the requisites block
 * @param number        the receipt number
 * @param receivedOn    the day the money was received
 * @param payerName     the payer member's full name; null when no member is named
 * @param payerNote     the payer note; null when there is none or the viewer may not see members
 * @param parts         the parts in street and plot order
 * @param placements    where the money is now, part by part
 * @param total         the amount received
 * @param asOf          the day the placement is shown as of (the day printed)
 * @param acceptedBy    the name of who entered the payment; null when unknown
 * @param reversedOn    the day of the last reversal when every part is reversed; null otherwise
 */
record ReceiptContent(Requisites cooperative, long number, LocalDate receivedOn, @Nullable String payerName,
        @Nullable String payerNote, List<Part> parts, List<PlacementLine> placements, Money total, LocalDate asOf,
        @Nullable String acceptedBy, @Nullable LocalDate reversedOn) {

    ReceiptContent {
        Objects.requireNonNull(cooperative, "cooperative");
        Objects.requireNonNull(receivedOn, "receivedOn");
        parts = List.copyOf(parts);
        placements = List.copyOf(placements);
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(asOf, "asOf");
    }

    /**
     * The requisites printed on top of a document.
     *
     * @param name    the official name, or the cooperative's name when none was entered
     * @param edrpou  the ЄДРПОУ code; null when not set
     * @param address the legal address; null when not set
     * @param iban    the IBAN; null when not set
     * @param bank    the bank's name; null when not set
     */
    record Requisites(String name, @Nullable String edrpou, @Nullable String address, @Nullable String iban,
            @Nullable String bank) {

        Requisites {
            Objects.requireNonNull(name, "name");
        }
    }

    /** One part: the plot, the amount and the day it was reversed (null when not). */
    record Part(String plotNumber, Money amount, @Nullable LocalDate reversedOn) {

        Part {
            Objects.requireNonNull(plotNumber, "plotNumber");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /** One line of the placement: the plot, what it is ({@code Членський внесок, жовтень 2026}) and the amount. */
    record PlacementLine(String plotNumber, String label, @Nullable Money amount) {

        PlacementLine {
            Objects.requireNonNull(plotNumber, "plotNumber");
            Objects.requireNonNull(label, "label");
        }
    }
}
