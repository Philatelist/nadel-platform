package app.nadel.notifications.internal;

import app.nadel.members.DeliveryChannel;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Which channel a member actually gets a document through (feature 017 §2.3). Pure: the member's preferred channel and
 * what is set up decide.
 *
 * <table>
 *   <caption>The rule</caption>
 *   <tr><th>Preferred</th><th>Situation</th><th>Channel</th></tr>
 *   <tr><td>TELEGRAM</td><td>linked and the bot is set up</td><td>TELEGRAM</td></tr>
 *   <tr><td>TELEGRAM</td><td>not linked, or no bot</td><td>EMAIL with an e-mail and a mailbox, otherwise PAPER</td></tr>
 *   <tr><td>EMAIL</td><td>an e-mail and a mailbox</td><td>EMAIL</td></tr>
 *   <tr><td>EMAIL</td><td>no e-mail, or no mailbox</td><td>PAPER</td></tr>
 *   <tr><td>PAPER</td><td>—</td><td>PAPER</td></tr>
 *   <tr><td>VIBER</td><td>—</td><td>PAPER with the note {@value #VIBER_UNSUPPORTED}</td></tr>
 * </table>
 */
final class ChannelResolver {

    /** The note of a Viber member's paper delivery («Viber ще не підтримується»). */
    static final String VIBER_UNSUPPORTED = "VIBER_UNSUPPORTED";

    /** The channel and, for a Viber member, the note to show with the delivery. */
    record Resolution(Channel channel, @Nullable String note) {

        Resolution {
            Objects.requireNonNull(channel, "channel");
        }

        static Resolution of(Channel channel) {
            return new Resolution(channel, null);
        }
    }

    private ChannelResolver() {
    }

    /**
     * The channel of a member.
     *
     * @param preferred      the member's preferred channel
     * @param hasEmail       whether the member has an e-mail
     * @param telegramLinked whether the member's Telegram is linked to the cooperative's bot
     * @param mailboxReady   whether the cooperative's mailbox is set up
     * @param botReady       whether the cooperative's bot is set up
     */
    static Resolution resolve(DeliveryChannel preferred, boolean hasEmail, boolean telegramLinked,
            boolean mailboxReady, boolean botReady) {
        Objects.requireNonNull(preferred, "preferred");
        boolean email = hasEmail && mailboxReady;
        return switch (preferred) {
            case TELEGRAM -> telegramLinked && botReady ? Resolution.of(Channel.TELEGRAM)
                    : Resolution.of(email ? Channel.EMAIL : Channel.PAPER);
            case EMAIL -> Resolution.of(email ? Channel.EMAIL : Channel.PAPER);
            case PAPER -> Resolution.of(Channel.PAPER);
            case VIBER -> new Resolution(Channel.PAPER, VIBER_UNSUPPORTED);
        };
    }
}
