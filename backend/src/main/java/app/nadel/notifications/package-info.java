/**
 * Delivery to members (feature 017): e-mail through the cooperative's own mailbox and Telegram through its own bot.
 * The module owns the delivery settings of a cooperative (table {@code delivery_settings}: the mailbox, the bot token,
 * the e-mail rate and the payment-message switch, the secrets sealed with the cooperative's data key, ADR 0018) and,
 * in later slices, the Telegram links, the delivery register, the sending worker, the bot and announcements.
 * <p>
 * It has no public API: other modules never call it. It reads other modules only through their public APIs
 * ({@code members.MemberDirectory} for the signed-in member's own e-mail, {@code tenancy.TenantDirectory} for the
 * cooperative's name) and, later, listens to their events and implements their SPIs. Everything is internal: the
 * endpoints of tag {@code notifications} ({@code DeliverySettingsController}), {@code DeliverySettingsService},
 * {@code DeliveryChecks}, the texts ({@code NotificationTexts}), the mail layer ({@code internal.mail}:
 * {@code MailSenderFactory}, {@code SmtpSettings}, {@code TestLetters}) and the Telegram Bot API client
 * ({@code internal.telegram}: {@code TelegramClient}). See the module README.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Notifications",
        allowedDependencies = {"members", "documents", "ledger", "meters", "plots", "identity", "privacy", "audit",
                "tenancy", "tariffs", "money", "settings", "problem"})
package app.nadel.notifications;
