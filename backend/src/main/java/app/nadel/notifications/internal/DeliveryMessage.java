package app.nadel.notifications.internal;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What one delivery says, rebuilt from the documents when it is sent (a delivery row holds no text): the subject of a
 * letter, the text (the Telegram caption and the letter's first line), the payment link of the «Оплатити» button, and
 * the PDF. Never log it.
 *
 * @param subject  the letter's subject
 * @param text     the message («Рахунок за жовтень 2026, ділянка 9: до сплати 413,26 ₴»)
 * @param payUrl   the invoice's payment link when something is due, else null
 * @param fileName the PDF's file name (ASCII)
 * @param pdf      the PDF
 * @param sender   the cooperative's name for the letter's signature
 */
record DeliveryMessage(String subject, String text, @Nullable String payUrl, String fileName, byte[] pdf,
        String sender) {

    DeliveryMessage {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(pdf, "pdf");
        Objects.requireNonNull(sender, "sender");
    }

    @Override
    public String toString() {
        return "DeliveryMessage[pay=" + (payUrl != null) + "]";
    }
}
