package app.nadel.notifications.internal;

import app.nadel.documents.PaperCopy;
import app.nadel.documents.PaperRecipients;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import app.nadel.notifications.internal.ChannelResolver.Resolution;
import app.nadel.plots.PlotOwners;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who gets a printed invoice (the documents SPI {@link PaperRecipients}, feature 017 §2.2, §2.3): every current owner
 * of the plot whose channel comes out as paper by the rule of {@link ChannelResolver} — paper chosen, Viber, or e-mail
 * or Telegram that cannot be used (the same rule the sending follows). Each gets their own copy named "Last First".
 * <p>
 * When the cooperative has a bot whose @name is known ({@code delivery_settings.bot_username}, kept by the bot check
 * or the first account link), an owner who is not linked yet gets a new {@code PAPER} link code (90 days) printed as
 * the QR code «Отримувати рахунки в Telegram»: the deep link {@code https://t.me/<bot>?start=<code>}. One code per
 * member per print (an owner of two plots finds the same code on both copies). Only the code's hash is stored, so a
 * code exists only on paper: printing creates codes, which is why the print runs in a read-write transaction. Runs in
 * the print's transaction of the bound cooperative; never logs a code or a name.
 */
@Component
class PaperCopiesProvider implements PaperRecipients {

    private final PlotOwners owners;
    private final MemberDirectory members;
    private final RecipientChannels recipients;
    private final DeliverySettingsService settings;
    private final TelegramLinks links;
    private final LinkCodes codes;

    PaperCopiesProvider(PlotOwners owners, MemberDirectory members, RecipientChannels recipients,
            DeliverySettingsService settings, TelegramLinks links, LinkCodes codes) {
        this.owners = owners;
        this.members = members;
        this.recipients = recipients;
        this.settings = settings;
        this.links = links;
        this.codes = codes;
    }

    @Override
    public Map<UUID, List<PaperCopy>> copiesFor(YearMonth month, Collection<UUID> plotIds) {
        Map<UUID, List<UUID>> plotOwners = owners.currentOwnerIds(plotIds);
        List<UUID> memberIds = plotOwners.values().stream().flatMap(List::stream).distinct().toList();
        DeliverySettingsValues values = settings.current();
        Map<UUID, Resolution> channels = recipients.resolve(memberIds, values);
        Map<UUID, MemberRef> names = members.find(memberIds);
        String bot = values.botConfigured() ? values.botUsername() : null;
        Set<UUID> linked = bot == null ? Set.of() : links.linkedAmong(memberIds);
        Map<UUID, Optional<String>> deepLinks = new HashMap<>();
        Map<UUID, List<PaperCopy>> copies = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<UUID>> plot : plotOwners.entrySet()) {
            List<PaperCopy> list = new ArrayList<>();
            for (UUID member : plot.getValue().stream().distinct().toList()) {
                Resolution resolution = channels.get(member);
                MemberRef name = names.get(member);
                if (resolution == null || resolution.channel() != Channel.PAPER || name == null) {
                    continue;
                }
                Optional<String> link = deepLinks.computeIfAbsent(member, id -> bot == null || linked.contains(id)
                        ? Optional.empty()
                        : Optional.of("https://t.me/" + bot + "?start="
                                + codes.issue(id, LinkCodes.Purpose.PAPER).code()));
                list.add(new PaperCopy(member, name.shortName(), link));
            }
            copies.put(plot.getKey(), List.copyOf(list));
        }
        return copies;
    }
}
