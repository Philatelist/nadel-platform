package app.nadel.notifications.internal.telegram;

/**
 * Who a bot token belongs to, as {@code getMe} reports it.
 *
 * @param id       the bot's Telegram id
 * @param username the bot's @name without {@code @}
 */
public record BotIdentity(long id, String username) {
}
