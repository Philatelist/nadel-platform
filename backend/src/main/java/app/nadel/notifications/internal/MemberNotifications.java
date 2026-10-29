package app.nadel.notifications.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.members.DeliveryChannel;
import app.nadel.members.MemberChannels;
import app.nadel.members.MemberContacts;
import app.nadel.members.MemberDirectory;
import app.nadel.notifications.internal.DeliverySettingsService.BotToken;
import app.nadel.notifications.internal.LinkCodes.Issued;
import app.nadel.notifications.internal.telegram.BotIdentity;
import app.nadel.notifications.internal.telegram.TelegramClient;
import app.nadel.notifications.internal.telegram.TelegramException;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member account's «Сповіщення» (feature 017 §2.2, §2.3): the signed-in member's own channel choice and Telegram
 * link. The member is always the one behind the token, never a request value; the platform operator (no cooperative,
 * no member) is refused with 403. No permission is needed.
 */
@Service
class MemberNotifications {

    private static final Logger log = LoggerFactory.getLogger(MemberNotifications.class);

    /** What the panel shows: the stored channel, the channels the member may choose, the link and the bot. */
    record View(DeliveryChannel channel, List<DeliveryChannel> available, boolean telegramLinked,
            boolean botAvailable) {
    }

    /** A personal deep link to the bot. Never log it: it carries the code. */
    record LinkOffer(String deepLink, Instant expiresAt) {

        @Override
        public String toString() {
            return "LinkOffer[expiresAt=" + expiresAt + "]";
        }
    }

    private final DeliverySettingsService settings;
    private final MemberDirectory members;
    private final MemberChannels channels;
    private final TelegramLinks links;
    private final LinkCodes codes;
    private final TelegramClient telegram;

    MemberNotifications(DeliverySettingsService settings, MemberDirectory members, MemberChannels channels,
            TelegramLinks links, LinkCodes codes, TelegramClient telegram) {
        this.settings = settings;
        this.members = members;
        this.channels = channels;
        this.links = links;
        this.codes = codes;
        this.telegram = telegram;
    }

    @Transactional(readOnly = true)
    View view(AccessProfile me) {
        UUID memberId = memberOf(me);
        DeliverySettingsValues values = settings.values(me);
        DeliveryChannel channel = Objects.requireNonNull(members.deliveryChannels(Set.of(memberId)).get(memberId),
                "member");
        return new View(channel, available(memberId, values), links.linked(memberId), values.botConfigured());
    }

    /** Sets the member's channel to one of the available ones; 400 {@code INVALID} on {@code channel} otherwise. */
    @Transactional
    View choose(AccessProfile me, DeliveryChannel channel) {
        UUID memberId = memberOf(me);
        if (!available(memberId, settings.values(me)).contains(channel)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("channel", ProblemCodes.INVALID)));
        }
        channels.setPreferredChannel(memberId, channel, me);
        return view(me);
    }

    /**
     * A personal deep link to the cooperative's bot with a new 30-minute code. The bot's @name is the one the bot check
     * kept; without it Telegram is asked ({@code getMe}, outside any transaction) and the answer kept. 409
     * {@code TELEGRAM_NOT_CONFIGURED} without a bot or when Telegram does not tell its name.
     */
    LinkOffer offerLink(AccessProfile me) {
        UUID memberId = memberOf(me);
        DeliverySettingsValues values = settings.values(me);
        if (!values.botConfigured()) {
            throw notConfigured();
        }
        String username = values.botUsername() != null ? values.botUsername() : askBotName(me);
        Issued issued = codes.issue(memberId, LinkCodes.Purpose.ACCOUNT);
        return new LinkOffer("https://t.me/" + username + "?start=" + issued.code(), issued.expiresAt());
    }

    /** Ends the member's Telegram link («Відключити Telegram»); nothing when none is active. */
    @Transactional
    void unlink(AccessProfile me) {
        links.unlinkMember(memberOf(me), TelegramLinks.EndReason.UNLINKED, me);
    }

    private String askBotName(AccessProfile me) {
        BotToken token = settings.botToken().orElseThrow(MemberNotifications::notConfigured);
        try {
            BotIdentity bot = telegram.getMe(token.token());
            settings.rememberBotUsername(token.settingsVersion(), bot.username());
            return bot.username();
        } catch (TelegramException e) {
            log.warn("Bot name of cooperative {} unknown: {}", me.tenantId(), e.getMessage());
            throw notConfigured();
        }
    }

    /** EMAIL with an e-mail of the member and a mailbox, TELEGRAM with a bot, PAPER always; in this order. */
    private List<DeliveryChannel> available(UUID memberId, DeliverySettingsValues values) {
        List<DeliveryChannel> available = new ArrayList<>();
        MemberContacts contacts = members.contacts(Set.of(memberId)).get(memberId);
        if (values.emailConfigured() && contacts != null && contacts.email() != null
                && !contacts.email().isBlank()) {
            available.add(DeliveryChannel.EMAIL);
        }
        if (values.botConfigured()) {
            available.add(DeliveryChannel.TELEGRAM);
        }
        available.add(DeliveryChannel.PAPER);
        return List.copyOf(available);
    }

    private static UUID memberOf(AccessProfile me) {
        if (me.platformOperator() || me.tenantId() == null || me.memberId() == null) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, ProblemCodes.PERMISSION_DENIED,
                    "Only a member of a cooperative has delivery choices.");
        }
        return me.memberId();
    }

    private static ApiProblemException notConfigured() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.TELEGRAM_NOT_CONFIGURED,
                "The cooperative has no working bot.");
    }
}
