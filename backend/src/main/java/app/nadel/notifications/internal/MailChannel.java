package app.nadel.notifications.internal;

import app.nadel.notifications.internal.mail.Letter;
import app.nadel.notifications.internal.mail.Letters;
import app.nadel.notifications.internal.mail.MailFailure;
import app.nadel.notifications.internal.mail.MailOutcome;
import app.nadel.notifications.internal.mail.SmtpSettings;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Sends a delivery as a letter through the cooperative's mailbox (feature 017 §2.4): plain text and HTML with the same
 * words (the message, escaped and with its line breaks kept, the payment link, the line naming the attachment such as «Рахунок у вкладенні (PDF)», the
 * board's signature), the PDF attached when the message has one, from the mailbox's sender name. The server's refusal of the sign-in is permanent ({@code SMTP_AUTH_FAILED}); a refused
 * letter ({@code SMTP_REJECTED}) or no connection ({@code CONNECTION_FAILED}) is tried again. Runs outside any
 * transaction.
 */
@Component
class MailChannel {

    private final Letters letters;

    MailChannel(Letters letters) {
        this.letters = letters;
    }

    SendOutcome send(UUID tenantId, SmtpSettings smtp, String to, DeliveryMessage message) {
        MailOutcome outcome = letters.send(tenantId, smtp, letter(to, message));
        if (outcome.accepted()) {
            return new SendOutcome.Sent();
        }
        MailFailure failure = outcome.failure();
        if (failure == MailFailure.SMTP_AUTH_FAILED) {
            return SendOutcome.permanent(SendOutcome.SMTP_AUTH_FAILED);
        }
        return SendOutcome.transientFailure(failure == MailFailure.CONNECTION_FAILED ? SendOutcome.CONNECTION_FAILED
                : SendOutcome.SMTP_REJECTED);
    }

    /** The letter of a message. */
    static Letter letter(String to, DeliveryMessage message) {
        NotificationTexts texts = NotificationTexts.uk();
        String attached = message.attachedLine();
        String signature = texts.text("invoice.email.signature", Map.of("cooperative", message.sender()));
        StringBuilder text = new StringBuilder(message.text()).append("\n\n");
        StringBuilder html = new StringBuilder("<p>").append(paragraph(message.text())).append("</p>");
        if (message.payUrl() != null) {
            text.append(texts.text("invoice.email.payLine", Map.of("link", message.payUrl()))).append("\n\n");
            html.append("<p><a href=\"").append(HtmlUtils.htmlEscape(message.payUrl())).append("\">")
                    .append(HtmlUtils.htmlEscape(texts.text("invoice.pay"))).append("</a></p>");
        }
        if (attached != null) {
            text.append(attached).append("\n\n");
            html.append("<p>").append(HtmlUtils.htmlEscape(attached)).append("</p>");
        }
        text.append(signature).append('\n');
        html.append("<p>").append(HtmlUtils.htmlEscape(signature)).append("</p>");
        return new Letter(to, message.subject(), text.toString(), "<html><body>" + html + "</body></html>",
                message.fileName(), message.pdf());
    }

    /** The text escaped for HTML, its line breaks kept as {@code <br>} (an announcement has several lines). */
    static String paragraph(String text) {
        return HtmlUtils.htmlEscape(text).replace("\r\n", "\n").replace("\n", "<br>\n");
    }
}
