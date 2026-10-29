package app.nadel.notifications.internal.telegram;

import org.jspecify.annotations.Nullable;

/**
 * One incoming update of a bot ({@code getUpdates}), reduced to what the bot handles: a message in a chat. The chat id
 * and the text are personal data and member input: never log them.
 *
 * @param updateId the update's id, increasing per bot; the next poll asks for {@code updateId + 1}
 * @param message  the message, or null for an update of another kind
 */
public record TelegramUpdate(long updateId, @Nullable Message message) {

    /**
     * A message to the bot.
     *
     * @param chatId   the chat it came from (a private chat's id is the user's id)
     * @param chatType {@code private}, {@code group}, {@code supergroup} or {@code channel}
     * @param text     the text, or null for a message without one (a photo, a sticker)
     */
    public record Message(long chatId, String chatType, @Nullable String text) {

        public boolean isPrivate() {
            return "private".equals(chatType);
        }

        /** Never prints the chat id or the text. */
        @Override
        public String toString() {
            return "Message[" + chatType + "]";
        }
    }
}
