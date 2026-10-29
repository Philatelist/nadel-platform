package app.nadel.notifications.internal;

import app.nadel.identity.OwnedPlot;
import app.nadel.identity.OwnedPlotsSource;
import app.nadel.members.MemberRef;
import app.nadel.notifications.internal.BotDialogue.State;
import app.nadel.notifications.internal.BotDialogue.Step;
import app.nadel.notifications.internal.DeliverySettingsService.BotToken;
import app.nadel.notifications.internal.TelegramLinks.EndReason;
import app.nadel.notifications.internal.TelegramLinks.Invalid;
import app.nadel.notifications.internal.TelegramLinks.Linked;
import app.nadel.notifications.internal.TelegramLinks.NeedsConfirmation;
import app.nadel.notifications.internal.TelegramLinks.Redeemed;
import app.nadel.notifications.internal.telegram.ReplyMarkup;
import app.nadel.notifications.internal.telegram.TelegramClient;
import app.nadel.notifications.internal.telegram.TelegramUpdate;
import app.nadel.tenancy.TenantContext;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Answers one message to a cooperative's bot (feature 017 §2.2, §2.5). The caller ({@link BotWorker}) has bound the
 * cooperative and taken the update. Only commands, the menu buttons and the steps of the dialogue are understood;
 * anything else gets the help text, or, in a chat that is not linked, the invitation to link through the account. No
 * data of any member reaches an unlinked chat.
 * <ul>
 *   <li>{@code /start <code>}: links the chat ({@link TelegramLinks#redeem}); a chat linked to another member asks
 *       «Це ви, {ім'я}?» first ({@link Step#CONFIRM_MOVE}).</li>
 *   <li>{@code /start}, {@code /help}: the help and the menu.</li>
 *   <li>«Відключити», {@code /stop}: ends the link.</li>
 *   <li>«Борг», «Рахунок», «Показання» and the steps of the plot choice and the reading dialogue: {@link BotCommands}.
 *       </li>
 * </ul>
 * Untrusted input: at most {@value BotInputLimiter#PER_MINUTE} messages a minute per chat ({@link BotInputLimiter}),
 * texts over {@value #MAX_TEXT} characters refused, only private chats answered. Message texts and chat ids are
 * never logged or stored; logs carry the cooperative id and an outcome only. Telegram answering 403 to a reply means
 * the member blocked the bot: the link ends ({@link EndReason#BLOCKED}).
 */
@Component
class BotConversation {

    static final int MAX_TEXT = 64;

    private static final Logger log = LoggerFactory.getLogger(BotConversation.class);
    private static final String START = "/start";

    private final TelegramLinks links;
    private final BotDialogue dialogue;
    private final BotInputLimiter limiter;
    private final OwnedPlotsSource plots;
    private final TenantDirectory tenants;
    private final TelegramClient telegram;
    private final BotCommands commands;
    private final NotificationTexts texts = NotificationTexts.uk();

    BotConversation(TelegramLinks links, BotDialogue dialogue, BotInputLimiter limiter, OwnedPlotsSource plots,
            TenantDirectory tenants, TelegramClient telegram, BotCommands commands) {
        this.links = links;
        this.dialogue = dialogue;
        this.limiter = limiter;
        this.plots = plots;
        this.tenants = tenants;
        this.telegram = telegram;
        this.commands = commands;
    }

    /** Answers the update; the cooperative is bound. */
    void handle(BotToken token, TelegramUpdate update) {
        TelegramUpdate.Message message = update.message();
        if (message == null || !message.isPrivate()) {
            return;
        }
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("No cooperative is bound"));
        long chatId = message.chatId();
        BotChat chat = new BotChat(tenantId, token, chatId, telegram, links);
        switch (limiter.check(tenantId, chatId)) {
            case WARN -> {
                chat.send(texts.text("bot.tooMany"), null);
                return;
            }
            case DROP -> {
                return;
            }
            case ALLOW -> {
                // handled below
            }
        }
        String text = message.text() == null ? "" : message.text().strip();
        if (text.length() > MAX_TEXT) {
            chat.send(texts.text("bot.tooLong", Map.of("max", Integer.toString(MAX_TEXT))), null);
            return;
        }
        State state = dialogue.get(tenantId, chatId);
        if (state.step() == Step.CONFIRM_MOVE) {
            dialogue.reset(tenantId, chatId);
            if (text.equals(texts.text("bot.move.yes")) && state.codeHash() != null) {
                link(chat, state.codeHash(), true);
                return;
            }
            if (text.equals(texts.text("bot.move.no"))) {
                chat.send(texts.text("bot.move.cancelled"), menuOrNone(chatId));
                return;
            }
        }
        if (text.startsWith(START + " ")) {
            String code = text.substring(START.length() + 1).strip();
            if (!LinkCodes.wellFormed(code)) {
                chat.send(texts.text("bot.link.invalid"), menuOrNone(chatId));
                return;
            }
            link(chat, LinkCodes.hash(code), false);
            return;
        }
        Optional<UUID> member = links.memberOfChat(chatId);
        if (member.isEmpty()) {
            dialogue.reset(tenantId, chatId);
            chat.send(texts.text("bot.invite", Map.of("cooperative", cooperativeName())),
                    new ReplyMarkup.RemoveKeyboard());
            return;
        }
        if (text.equals("/stop") || text.equals(texts.text("bot.menu.unlink"))) {
            dialogue.reset(tenantId, chatId);
            links.unlinkChat(chatId, EndReason.UNLINKED);
            chat.send(texts.text("bot.unlinked"), new ReplyMarkup.RemoveKeyboard());
        } else if (commands.isAction(text)) {
            commands.action(chat, member.get(), text);
        } else if (!text.startsWith("/") && commands.step(chat, member.get(), state, text)) {
            log.debug("Bot dialogue step answered in cooperative {}", tenantId);
        } else {
            dialogue.reset(tenantId, chatId);
            chat.send(texts.text("bot.help", Map.of("cooperative", cooperativeName())), menu());
        }
    }

    private void link(BotChat chat, byte[] codeHash, boolean moveConfirmed) {
        Redeemed redeemed = links.redeem(chat.chatId(), codeHash, moveConfirmed);
        switch (redeemed) {
            case Invalid invalid -> chat.send(texts.text("bot.link.invalid"), menuOrNone(chat.chatId()));
            case NeedsConfirmation confirmation -> {
                dialogue.confirmMove(chat.tenantId(), chat.chatId(), codeHash);
                chat.send(texts.text("bot.move.question", Map.of("name", confirmation.member().firstName())),
                        new ReplyMarkup.Keyboard(List.of(List.of(texts.text("bot.move.yes"),
                                texts.text("bot.move.no")))));
            }
            case Linked linked -> {
                chat.send(greeting(linked.member()), menu());
                log.info("Telegram linked in cooperative {}", chat.tenantId());
            }
        }
    }

    private String greeting(MemberRef member) {
        List<String> numbers = plots.currentPlotsOf(member.id()).stream().map(OwnedPlot::number).distinct().toList();
        String key = switch (numbers.size()) {
            case 0 -> "bot.greeting.noPlots";
            case 1 -> "bot.greeting.onePlot";
            default -> "bot.greeting.plots";
        };
        return texts.text(key, Map.of("name", member.firstName(), "plots", String.join(", ", numbers)));
    }

    /** The menu for a linked chat, nothing for one that is not linked. */
    private ReplyMarkup menuOrNone(long chatId) {
        return links.memberOfChat(chatId).isPresent() ? menu() : new ReplyMarkup.RemoveKeyboard();
    }

    private ReplyMarkup menu() {
        return commands.menu();
    }

    private String cooperativeName() {
        return tenants.current().map(TenantSummary::name).orElse("");
    }
}
