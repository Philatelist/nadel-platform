package app.nadel.notifications.internal;

import app.nadel.notifications.internal.telegram.ReplyMarkup;
import app.nadel.notifications.internal.telegram.TelegramClient;
import app.nadel.notifications.internal.telegram.TelegramException;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Sends a delivery through the cooperative's bot (feature 017 §2.4): the PDF with the message as its caption (a
 * message without a PDF as a text message) and, when something is due, the button «Оплатити» opening the invoice's
 * payment link. Telegram's answer decides the outcome:
 * 403 (the member blocked the bot) is permanent {@code TELEGRAM_BLOCKED} and the caller ends the link; 429 waits as
 * Telegram asks; 401 is {@code CHANNEL_NOT_CONFIGURED} (the token stopped working) and another refusal
 * {@code NO_CONTACT} (the chat cannot be written to), both permanent; no answer is {@code CONNECTION_FAILED}, tried
 * again. Runs outside any transaction; never logs the chat or the text.
 */
@Component
class TelegramChannel {

    private final TelegramClient telegram;

    TelegramChannel(TelegramClient telegram) {
        this.telegram = telegram;
    }

    SendOutcome send(String token, long chatId, DeliveryMessage message) {
        ReplyMarkup pay = message.payUrl() == null ? null
                : new ReplyMarkup.UrlButton(NotificationTexts.uk().text("invoice.pay"), message.payUrl());
        try {
            if (message.hasPdf()) {
                telegram.sendDocument(token, chatId, Objects.requireNonNull(message.fileName()),
                        Objects.requireNonNull(message.pdf()), message.text(), pay);
            } else {
                telegram.sendMessage(token, chatId, message.text(), pay);
            }
            return new SendOutcome.Sent();
        } catch (TelegramException e) {
            return switch (e.kind()) {
                case FORBIDDEN -> SendOutcome.permanent(SendOutcome.TELEGRAM_BLOCKED);
                case TOO_MANY_REQUESTS -> new SendOutcome.Wait(e.retryAfter().orElse(java.time.Duration.ofSeconds(1)));
                case UNAUTHORIZED -> SendOutcome.permanent(SendOutcome.CHANNEL_NOT_CONFIGURED);
                case REJECTED -> SendOutcome.permanent(SendOutcome.NO_CONTACT);
                case UNAVAILABLE -> SendOutcome.transientFailure(SendOutcome.CONNECTION_FAILED);
            };
        }
    }
}
