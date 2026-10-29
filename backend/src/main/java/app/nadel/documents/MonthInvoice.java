package app.nadel.documents;

import app.nadel.money.Money;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/**
 * The latest version of one plot's invoice for a billing month ({@link DocumentsFacade#latestInvoices}).
 *
 * @param invoiceId the invoice version
 * @param plotId    its plot
 * @param version   the version number, from 1
 * @param number    the invoice number, {@code YYYY-MM/plot}
 * @param due       «До сплати» of the version (zero when nothing is due)
 * @param month     the billing month
 */
public record MonthInvoice(UUID invoiceId, UUID plotId, int version, String number, Money due, YearMonth month) {

    public MonthInvoice {
        Objects.requireNonNull(invoiceId, "invoiceId");
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(due, "due");
        Objects.requireNonNull(month, "month");
    }
}
