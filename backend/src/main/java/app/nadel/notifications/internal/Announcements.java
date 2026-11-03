package app.nadel.notifications.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import app.nadel.notifications.internal.AnnouncementRows.Stored;
import app.nadel.notifications.internal.ChannelResolver.Resolution;
import app.nadel.notifications.internal.DeliveryRows.AnnouncementCounts;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.plots.PlotOwners;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.text.Collator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The board's announcements (feature 017 §2.6): the preview, the send, the list and one announcement with its
 * deliveries. The caller holds {@code ANNOUNCEMENTS_SEND} (checked on the controller); the cooperative is the caller's.
 * <p>
 * <b>Audience.</b> Every active member ({@link MemberDirectory#activeIds}) when no street is chosen, otherwise the
 * current owners ({@link PlotOwners}) of the active plots that are not common land on the chosen streets
 * ({@link PlotCatalog#findActive}: the street compared ignoring case and surrounding spaces). <b>Channel.</b> Each
 * member gets one delivery through {@link ChannelResolver#forAnnouncement}: only a channel the board chose, else paper
 * («на папері», the board informs them in person). E-mail and Telegram rows are queued and their lanes woken after the
 * commit; paper rows are {@code TO_PRINT} so the announcement keeps its paper list and counts.
 * <p>
 * <b>The text</b> is user content: stored as entered (surrounding spaces removed) and sent as is, never in the audit
 * log, the outbox or a log. The send is audited as {@code ANNOUNCEMENT_SENT} (entity type {@code ANNOUNCEMENT}, the
 * announcement's id, label {@code announcement}) with the audience, the channels and the counts per channel.
 */
@Service
class Announcements {

    static final String ACTION = "ANNOUNCEMENT_SENT";
    static final String ENTITY_TYPE = "ANNOUNCEMENT";
    static final String LABEL = "announcement";
    /** The most characters (Unicode code points) of a text, as the table's CHECK. */
    static final int MAX_LENGTH = 2000;

    private static final Collator NAMES = Collator.getInstance(Locale.forLanguageTag("uk"));

    /** What to send: the text, the streets (empty for every member) and the channels chosen. */
    record Request(String text, List<String> streets, Set<Channel> channels) {
    }

    /** A member on paper. */
    record PaperMember(UUID memberId, String name) {
    }

    /** What a send created (or a preview would create): the deliveries per channel and the members on paper. */
    record Plan(@Nullable UUID announcementId, int telegram, int email, int paper, List<PaperMember> paperMembers) {
    }

    /** An announcement of the list (no text) or of its page (with the text), its author's name and counts. */
    record View(Stored announcement, @Nullable String authorName, AnnouncementCounts counts) {
    }

    /** A page of the list. */
    record Page(List<View> items, long total) {
    }

    private final AnnouncementRows announcements;
    private final DeliveryRows rows;
    private final MemberDirectory members;
    private final PlotCatalog plots;
    private final PlotOwners owners;
    private final RecipientChannels recipients;
    private final DeliverySettingsService settings;
    private final DeliveryQueue queue;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    Announcements(AnnouncementRows announcements, DeliveryRows rows, MemberDirectory members, PlotCatalog plots,
            PlotOwners owners, RecipientChannels recipients, DeliverySettingsService settings, DeliveryQueue queue,
            ApplicationEventPublisher events, Clock clock) {
        this.announcements = announcements;
        this.rows = rows;
        this.members = members;
        this.plots = plots;
        this.owners = owners;
        this.recipients = recipients;
        this.settings = settings;
        this.queue = queue;
        this.events = events;
        this.clock = clock;
    }

    /** The preview ({@code preview}: nothing is written) or the send of an announcement. */
    @Transactional
    Plan send(Request request, boolean preview, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        String text = text(request.text());
        List<String> streets = streets(request.streets());
        Set<Channel> chosen = EnumSet.noneOf(Channel.class);
        chosen.addAll(request.channels());
        chosen.remove(Channel.PAPER);
        if (chosen.isEmpty()) {
            throw invalid("channels", ProblemCodes.REQUIRED);
        }
        Map<UUID, Resolution> channels = recipients.resolveForAnnouncement(audience(streets), settings.current(),
                chosen);
        Map<Channel, Integer> counts = new EnumMap<>(Channel.class);
        List<UUID> paper = new ArrayList<>();
        channels.forEach((member, resolution) -> {
            counts.merge(resolution.channel(), 1, Integer::sum);
            if (resolution.channel() == Channel.PAPER) {
                paper.add(member);
            }
        });
        UUID announcementId = null;
        if (!preview) {
            Instant now = clock.instant();
            announcementId = announcements.insert(tenantId, text, streets, chosen, actor.memberId(), now);
            for (Map.Entry<UUID, Resolution> entry : channels.entrySet()) {
                rows.insertAnnouncement(tenantId, announcementId, entry.getKey(), entry.getValue().channel(),
                        entry.getValue().note(), now);
            }
            audit(tenantId, actor, announcementId, streets, chosen, counts);
            for (Channel channel : List.of(Channel.EMAIL, Channel.TELEGRAM)) {
                if (counts.getOrDefault(channel, 0) > 0) {
                    queue.wakeAfterCommit(tenantId, channel);
                }
            }
        }
        return new Plan(announcementId, counts.getOrDefault(Channel.TELEGRAM, 0),
                counts.getOrDefault(Channel.EMAIL, 0), counts.getOrDefault(Channel.PAPER, 0), names(paper));
    }

    /** A page of the announcements, newest first, without their text. */
    @Transactional(readOnly = true)
    Page list(int page, int size) {
        List<Stored> found = announcements.page(page, size);
        return new Page(views(found), announcements.count());
    }

    /** One announcement with its text; 404 for an unknown id or one of another cooperative. */
    @Transactional(readOnly = true)
    View find(UUID id) {
        Stored found = announcements.find(id).orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                ProblemCodes.NOT_FOUND, "The announcement does not exist."));
        return views(List.of(found)).getFirst();
    }

    private List<View> views(List<Stored> found) {
        Map<UUID, AnnouncementCounts> counts = rows.announcementCounts(found.stream().map(Stored::id).toList());
        Map<UUID, MemberRef> authors = members.find(found.stream().map(Stored::createdBy).filter(Objects::nonNull)
                .distinct().toList());
        return found.stream().map(announcement -> {
            MemberRef author = announcement.createdBy() == null ? null : authors.get(announcement.createdBy());
            return new View(announcement, author == null ? null : author.shortName(),
                    counts.getOrDefault(announcement.id(), AnnouncementCounts.NONE));
        }).toList();
    }

    /** The members the announcement goes to. */
    private Collection<UUID> audience(List<String> streets) {
        if (streets.isEmpty()) {
            return members.activeIds();
        }
        List<UUID> plotIds = streets.stream().flatMap(street -> plots.findActive(street).stream())
                .map(PlotFacts::id).distinct().toList();
        Set<UUID> memberIds = new LinkedHashSet<>();
        owners.currentOwnerIds(plotIds).values().forEach(memberIds::addAll);
        Map<UUID, MemberRef> found = members.find(memberIds);
        return memberIds.stream().filter(id -> found.containsKey(id) && !found.get(id).archived()).toList();
    }

    /** The paper members with their names "Last First", in Ukrainian alphabetical order. */
    private List<PaperMember> names(List<UUID> paper) {
        Map<UUID, MemberRef> found = members.find(paper);
        return paper.stream().filter(found::containsKey)
                .map(id -> new PaperMember(id, found.get(id).shortName()))
                .sorted(Comparator.comparing(PaperMember::name, NAMES).thenComparing(PaperMember::memberId))
                .toList();
    }

    private void audit(UUID tenantId, AccessProfile actor, UUID announcementId, List<String> streets,
            Set<Channel> chosen, Map<Channel, Integer> counts) {
        List<FieldChange> fields = new ArrayList<>();
        fields.add(new FieldChange("audience", null, streets.isEmpty() ? "ALL" : "STREETS"));
        if (!streets.isEmpty()) {
            fields.add(new FieldChange("streets", null, String.join(", ", streets)));
        }
        fields.add(new FieldChange("channels", null, String.join(", ", chosen.stream().map(Channel::name).toList())));
        fields.add(new FieldChange("telegram", null, Integer.toString(counts.getOrDefault(Channel.TELEGRAM, 0))));
        fields.add(new FieldChange("email", null, Integer.toString(counts.getOrDefault(Channel.EMAIL, 0))));
        fields.add(new FieldChange("paper", null, Integer.toString(counts.getOrDefault(Channel.PAPER, 0))));
        events.publishEvent(AuditEvent.now(tenantId, new Actor(actor.memberId(), actor.subject(),
                actor.displayName()), ACTION, ENTITY_TYPE, announcementId, LABEL, fields));
    }

    /**
     * The text without surrounding spaces: 400 {@code VALIDATION_FAILED} ({@code text}: {@code REQUIRED}) when blank,
     * 400 {@code ANNOUNCEMENT_TOO_LONG} ({@code text}: {@code TOO_LONG}) over {@value #MAX_LENGTH} characters.
     */
    static String text(@Nullable String entered) {
        String text = entered == null ? "" : entered.strip();
        if (text.isEmpty()) {
            throw invalid("text", ProblemCodes.REQUIRED);
        }
        if (text.codePointCount(0, text.length()) > MAX_LENGTH) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.ANNOUNCEMENT_TOO_LONG,
                    "The announcement is longer than " + MAX_LENGTH + " characters.",
                    List.of(new FieldProblem("text", ProblemCodes.TOO_LONG)));
        }
        return text;
    }

    /**
     * The streets without surrounding spaces, each once ignoring case, in the order given; a blank street is 400
     * {@code VALIDATION_FAILED} ({@code streets}: {@code INVALID}).
     */
    static List<String> streets(@Nullable List<String> entered) {
        Map<String, String> streets = new LinkedHashMap<>();
        for (String street : entered == null ? List.<String>of() : entered) {
            String stripped = street == null ? "" : street.strip();
            if (stripped.isEmpty()) {
                throw invalid("streets", ProblemCodes.INVALID);
            }
            streets.putIfAbsent(stripped.toLowerCase(Locale.ROOT), stripped);
        }
        return List.copyOf(streets.values());
    }

    private static ApiProblemException invalid(String field, String code) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                "The request is not valid.", List.of(new FieldProblem(field, code)));
    }
}
