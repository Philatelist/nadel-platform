package app.nadel.notifications.internal;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What one delivery says, rebuilt from the documents and the ledger when it is sent (a delivery row holds no text): the
 * subject of a letter, the text (the Telegram message or caption and the letter's first line), the payment link of the
 * «Оплатити» button, and the PDF when one goes along (an invoice, the receipt of a cash payment). Never log it.
 *
 * @param subject      the letter's subject
 * @param text         the message («Рахунок за жовтень 2026, ділянка 9: до сплати 413,26 ₴»)
 * @param payUrl       the invoice's payment link when something is due, else null
 * @param fileName     the PDF's file name (ASCII); null without a PDF
 * @param pdf          the PDF; null for a message without one (a bank payment, a reversal)
 * @param attachedLine the letter's line saying what is attached («Рахунок у вкладенні (PDF).»); null without a PDF
 * @param sender       the cooperative's name for the letter's signature
 */
record DeliveryMessage(String subject, String text, @Nullable String payUrl, @Nullable String fileName,
        byte @Nullable [] pdf, @Nullable String attachedLine, String sender) {

    DeliveryMessage {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(sender, "sender");
        if ((fileName == null) != (pdf == null)) {
            throw new IllegalArgumentException("A PDF goes with its file name");
        }
    }

    /** Whether a PDF goes along. */
    boolean hasPdf() {
        return pdf != null;
    }

    @Override
    public String toString() {
        return "DeliveryMessage[pay=" + (payUrl != null) + ", pdf=" + (pdf != null) + "]";
    }
}
