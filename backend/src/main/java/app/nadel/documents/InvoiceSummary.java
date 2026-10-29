package app.nadel.documents;

import app.nadel.money.Money;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What a message about one invoice version needs ({@link DocumentsFacade#invoiceSummary}): no names, no addresses.
 *
 * @param invoiceId  the invoice version
 * @param plotId     its plot
 * @param plotNumber the plot's number as printed on the invoice
 * @param number     the invoice number, {@code YYYY-MM/plot}
 * @param month      the billing month
 * @param version    the version number, from 1
 * @param due        «До сплати» (zero when nothing is due)
 * @param qrLink     the NBU payment link of the version ({@code https://qr.bank.gov.ua/...}); empty when nothing is due
 */
public record InvoiceSummary(UUID invoiceId, UUID plotId, String plotNumber, String number, YearMonth month,
        int version, Money due, Optional<String> qrLink) {

    public InvoiceSummary {
        Objects.requireNonNull(invoiceId, "invoiceId");
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(plotNumber, "plotNumber");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(due, "due");
        Objects.requireNonNull(qrLink, "qrLink");
    }
}
