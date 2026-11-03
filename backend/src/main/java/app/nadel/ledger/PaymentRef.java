package app.nadel.ledger;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a journal line of the treasury shows about the payment row behind it (feature 018 §2.2,
 * {@link LedgerReadModel#paymentRefs}). No personal data: the payer and the payer note stay in the ledger.
 *
 * @param ledgerRowId   the payment row (a payment or a payment reversal)
 * @param paymentId     the payment (kind {@code PAYMENT}): the row itself, or the payment a reversal cancels
 * @param plotId        the plot of the payment
 * @param reversal      whether the row is a payment reversal
 * @param source        cash or bank
 * @param receiptNumber the receipt number of a cash payment (for a reversal, its payment's); null for a bank payment
 * @param bankReference the bank document number of a bank payment when entered (for a reversal, its payment's);
 *                      user content
 * @param reason        the reason of a reversal (user content); null for a payment
 */
public record PaymentRef(UUID ledgerRowId, UUID paymentId, UUID plotId, boolean reversal, PaymentSource source,
        @Nullable Long receiptNumber, @Nullable String bankReference, @Nullable String reason) {
}
