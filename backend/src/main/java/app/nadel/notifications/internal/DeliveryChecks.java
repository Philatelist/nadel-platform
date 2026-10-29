package app.nadel.notifications.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.notifications.internal.DeliverySettingsService.BotToken;
import app.nadel.notifications.internal.DeliverySettingsService.TestLetter;
import app.nadel.notifications.internal.mail.MailOutcome;
import app.nadel.notifications.internal.mail.TestLetters;
import app.nadel.notifications.internal.telegram.BotIdentity;
import app.nadel.notifications.internal.telegram.TelegramClient;
import app.nadel.notifications.internal.telegram.TelegramException;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The two checks of the delivery settings (feature 017 §2.1): the test letter through the cooperative's mailbox and
 * the question to Telegram about its bot. Each reads what it needs in a short transaction of
 * {@link DeliverySettingsService} and talks to the network outside any transaction. Logs carry the cooperative id
 * and an outcome code only, never an address, a token or a text.
 */
@Component
class DeliveryChecks {

    private static final Logger log = LoggerFactory.getLogger(DeliveryChecks.class);

    /** Why the bot check failed. */
    enum BotProblem {
        TOKEN_INVALID,
        TELEGRAM_UNAVAILABLE
    }

    /** The answer of the bot check: the bot's @name, or why there is none. */
    record BotCheck(@Nullable String botUsername, @Nullable BotProblem problem) {
    }

    private final DeliverySettingsService settings;
    private final TestLetters letters;
    private final TelegramClient telegram;

    DeliveryChecks(DeliverySettingsService settings, TestLetters letters, TelegramClient telegram) {
        this.settings = settings;
        this.letters = letters;
        this.telegram = telegram;
    }

    /** Sends the test letter to the actor's own e-mail; 409 without a mailbox or an own e-mail. */
    MailOutcome testEmail(AccessProfile actor) {
        TestLetter letter = settings.testLetter(actor);
        NotificationTexts texts = NotificationTexts.uk();
        MailOutcome outcome = letters.send(Objects.requireNonNull(actor.tenantId()), letter.smtp(), letter.to(),
                texts.text("testEmail.subject"),
                texts.text("testEmail.body", Map.of("cooperative", letter.cooperativeName())));
        log.info("Test letter of cooperative {}: {}", actor.tenantId(),
                outcome.accepted() ? "ACCEPTED" : outcome.failure());
        return outcome;
    }

    /**
     * Asks Telegram who the saved token belongs to and keeps the bot's @name; 409 without a token. A token Telegram
     * does not accept is {@link BotProblem#TOKEN_INVALID}, no answer {@link BotProblem#TELEGRAM_UNAVAILABLE}.
     */
    BotCheck testTelegram(AccessProfile actor) {
        BotToken token = settings.botToken().orElseThrow(() -> new ApiProblemException(HttpStatus.CONFLICT,
                ProblemCodes.DELIVERY_NOT_CONFIGURED, "The cooperative's bot is not set up."));
        try {
            BotIdentity bot = telegram.getMe(token.token());
            settings.rememberBotUsername(token.settingsVersion(), bot.username());
            return new BotCheck(bot.username(), null);
        } catch (TelegramException e) {
            if (e.kind() == TelegramException.Kind.UNAUTHORIZED) {
                return new BotCheck(null, BotProblem.TOKEN_INVALID);
            }
            log.warn("Bot check of cooperative {}: {}", actor.tenantId(), e.getMessage());
            return new BotCheck(null, BotProblem.TELEGRAM_UNAVAILABLE);
        }
    }
}
