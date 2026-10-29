package app.nadel.notifications.internal;

import app.nadel.notifications.internal.DeliverySettingsService.BotToken;
import app.nadel.notifications.internal.TelegramLinks.EndReason;
import app.nadel.notifications.internal.telegram.ReplyMarkup;
import app.nadel.notifications.internal.telegram.TelegramClient;
import app.nadel.notifications.internal.telegram.TelegramException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One chat of a cooperative's bot that the bot answers or tells something (feature 017 §2.5). The cooperative is bound
 * by the caller ({@code TenantContext.runAs}). Telegram answering 403 means the member blocked the bot: the chat's link
 * ends ({@link EndReason#BLOCKED}). Sends outside any transaction; never logs the chat id or a text, and never prints
 * them.
 */
final class BotChat {

    private static final Logger log = LoggerFactory.getLogger(BotChat.class);

    private final UUID tenantId;
    private final BotToken token;
    private final long chatId;
    private final TelegramClient telegram;
    private final TelegramLinks links;

    BotChat(UUID tenantId, BotToken token, long chatId, TelegramClient telegram, TelegramLinks links) {
        this.tenantId = tenantId;
        this.token = token;
        this.chatId = chatId;
        this.telegram = telegram;
        this.links = links;
    }

    UUID tenantId() {
        return tenantId;
    }

    long chatId() {
        return chatId;
    }

    /** Sends a text with the buttons of {@code markup} when given; false when it did not go. */
    boolean send(String text, @Nullable ReplyMarkup markup) {
        try {
            telegram.sendMessage(token.token(), chatId, text, markup);
            return true;
        } catch (TelegramException e) {
            failed(e);
            return false;
        }
    }

    /** Sends a PDF with its caption and the buttons of {@code markup} when given; false when it did not go. */
    boolean sendDocument(String fileName, byte[] pdf, String caption, @Nullable ReplyMarkup markup) {
        try {
            telegram.sendDocument(token.token(), chatId, fileName, pdf, caption, markup);
            return true;
        } catch (TelegramException e) {
            failed(e);
            return false;
        }
    }

    private void failed(TelegramException e) {
        if (e.kind() == TelegramException.Kind.FORBIDDEN) {
            links.unlinkChat(chatId, EndReason.BLOCKED);
            log.info("Bot of cooperative {} is blocked by a chat; its link ended", tenantId);
        } else {
            log.warn("Bot message of cooperative {} failed: {}", tenantId, e.getMessage());
        }
    }

    @Override
    public String toString() {
        return "BotChat[" + tenantId + "]";
    }
}
