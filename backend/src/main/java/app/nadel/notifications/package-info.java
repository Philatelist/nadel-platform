/**
 * Delivery to members (feature 017): e-mail through the cooperative's own mailbox and Telegram through its own bot.
 * The module owns the delivery settings of a cooperative (table {@code delivery_settings}: the mailbox, the bot token,
 * the e-mail rate and the payment-message switch, the secrets sealed with the cooperative's data key, ADR 0018), the
 * Telegram links and their one-time codes, the bot (long polling, one instance per bot by a lease), the delivery
 * register with its sending worker (JobRunr, one lane per cooperative and channel by a lease), the payment messages,
 * the bot's menu («Борг», «Рахунок», «Показання») with the messages about the board's decision on a reading and, in a
 * later slice, announcements.
 * <p>
 * It has no public API: other modules never call it. It reads other modules only through their public APIs
 * ({@code members.MemberDirectory}, {@code members.MemberChannels}, {@code identity.OwnedPlotsSource},
 * {@code tenancy.TenantDirectory}, {@code documents.DocumentsFacade}, {@code plots.PlotOwners},
 * {@code ledger.LedgerReadModel}, {@code tariffs.ChargeTypeCatalog}, {@code meters.MemberReadings}), implements their
 * SPIs ({@code members.TelegramLinkStatus}, {@code documents.PaperRecipients}) and listens to their events (the
 * ledger's {@code PaymentRecorded} and {@code PaymentReversed}, the meters' {@code ReadingDecided}). Everything is internal: the endpoints of tag
 * {@code notifications} ({@code NotificationsController}),
 * {@code DeliverySettingsService}, {@code DeliveryChecks}, {@code MemberNotifications}, {@code LinkCodes},
 * {@code TelegramLinks}, the bot ({@code BotSupervisor}, {@code BotWorker}, {@code BotConversation},
 * {@code BotCommands}, {@code BotChat}, {@code BotDialogue}, {@code BotInputLimiter}, {@code BotPollState},
 * {@code ReadingMessages}), the sending ({@code ChannelResolver},
 * {@code RecipientChannels}, {@code DeliveryPlanner}, {@code DeliveryRows}, {@code DeliveryRegister},
 * {@code DeliveryDispatcher} with its job {@code DispatchDeliveries}, {@code DeliveryLanes}, {@code DeliveryQueue},
 * {@code RateLimiters}, {@code InvoiceMessages}, {@code PaymentMessages}, {@code MailChannel}, {@code TelegramChannel},
 * {@code PaperCopiesProvider}), the texts ({@code NotificationTexts}), the mail layer ({@code internal.mail}) and the
 * Telegram Bot API client ({@code internal.telegram}). See the module README.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Notifications",
        allowedDependencies = {"members", "documents", "ledger", "meters", "plots", "identity", "privacy", "audit",
                "tenancy", "tariffs", "money", "settings", "problem"})
package app.nadel.notifications;
