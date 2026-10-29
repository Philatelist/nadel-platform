package app.nadel.notifications.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.documents.DocumentsFacade;
import app.nadel.documents.InvoiceSummary;
import app.nadel.documents.MonthInvoice;
import app.nadel.identity.AccessProfile;
import app.nadel.notifications.internal.ChannelResolver.Resolution;
import app.nadel.notifications.internal.DeliveryRows.InvoiceKey;
import app.nadel.plots.PlotOwners;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
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
 * Plans the delivery of invoices (feature 017 §2.3, §2.4): for each invoice version, every current owner of its plot
 * ({@code plots.PlotOwners}) gets one delivery through their channel ({@link RecipientChannels}); an owner of two
 * plots gets two. A delivery is created once per (invoice version, member, channel) — {@code INSERT … ON CONFLICT DO
 * NOTHING} on the table's unique key — so sending a month again adds only what is new and nobody gets the same invoice
 * twice. E-mail and Telegram rows are queued and their lanes woken after the commit ({@link DeliveryQueue}); paper rows
 * wait as {@code TO_PRINT}.
 * <p>
 * The preview runs the same plan without writing. A send that created something is audited as {@code INVOICES_SENT}
 * (entity type {@code BILLING_MONTH}, the entity id of the month's {@code INVOICES_ISSUED}, label {@code YYYY-MM},
 * fields {@code month}, {@code telegram}, {@code email}, {@code paper} and, for one invoice, {@code number}). The
 * caller holds {@code INVOICES_ISSUE} (checked on the controller); the cooperative is the caller's.
 */
@Service
class DeliveryPlanner {

    static final String ACTION = "INVOICES_SENT";
    static final String ENTITY_TYPE = "BILLING_MONTH";

    /** The deliveries a send created (or a preview would create) per channel, and how many were queued. */
    record Plan(int telegram, int email, int paper, int queued) {
    }

    private final DocumentsFacade documents;
    private final PlotOwners owners;
    private final RecipientChannels recipients;
    private final DeliverySettingsService settings;
    private final DeliveryRows rows;
    private final DeliveryQueue queue;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    DeliveryPlanner(DocumentsFacade documents, PlotOwners owners, RecipientChannels recipients,
            DeliverySettingsService settings, DeliveryRows rows, DeliveryQueue queue, ApplicationEventPublisher events,
            Clock clock) {
        this.documents = documents;
        this.owners = owners;
        this.recipients = recipients;
        this.settings = settings;
        this.rows = rows;
        this.queue = queue;
        this.events = events;
        this.clock = clock;
    }

    /** The latest invoices of the month, only those with something due unless {@code includeNothingDue}. */
    @Transactional
    Plan sendMonth(YearMonth month, boolean includeNothingDue, boolean preview, AccessProfile actor) {
        List<MonthInvoice> invoices = documents.latestInvoices(month).stream()
                .filter(invoice -> includeNothingDue || invoice.due().signum() > 0)
                .toList();
        return plan(month, invoices, preview, actor, null);
    }

    /** One invoice version to its plot's owners, whatever is due on it; 404 for an unknown id. */
    @Transactional
    Plan sendInvoice(UUID invoiceId, AccessProfile actor) {
        InvoiceSummary invoice = documents.invoiceSummary(invoiceId).orElseThrow(() -> new ApiProblemException(
                HttpStatus.NOT_FOUND, ProblemCodes.NOT_FOUND, "The invoice does not exist."));
        return plan(invoice.month(), List.of(new MonthInvoice(invoice.invoiceId(), invoice.plotId(), invoice.version(),
                invoice.number(), invoice.due(), invoice.month())), false, actor, invoice.number());
    }

    private Plan plan(YearMonth month, List<MonthInvoice> invoices, boolean preview, AccessProfile actor,
            @Nullable String number) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        Map<UUID, List<UUID>> plotOwners = owners.currentOwnerIds(invoices.stream().map(MonthInvoice::plotId)
                .distinct().toList());
        Map<UUID, Resolution> channels = recipients.resolve(plotOwners.values().stream().flatMap(List::stream)
                .distinct().toList(), settings.current());
        Set<InvoiceKey> existing = rows.invoiceKeys(invoices.stream().map(MonthInvoice::invoiceId).toList());
        Map<Channel, Integer> created = new EnumMap<>(Channel.class);
        Instant now = clock.instant();
        for (MonthInvoice invoice : invoices) {
            for (UUID member : plotOwners.getOrDefault(invoice.plotId(), List.of()).stream().distinct().toList()) {
                Resolution resolution = channels.get(member);
                if (resolution == null) {
                    continue;
                }
                Channel channel = resolution.channel();
                boolean isNew = preview
                        ? !existing.contains(new InvoiceKey(invoice.invoiceId(), member, channel))
                        : rows.insertInvoice(tenantId, invoice.invoiceId(), invoice.version(), invoice.month(), member,
                                channel, resolution.note(), now);
                if (isNew) {
                    created.merge(channel, 1, Integer::sum);
                }
            }
        }
        int telegram = created.getOrDefault(Channel.TELEGRAM, 0);
        int email = created.getOrDefault(Channel.EMAIL, 0);
        int paper = created.getOrDefault(Channel.PAPER, 0);
        if (preview) {
            return new Plan(telegram, email, paper, 0);
        }
        if (telegram + email + paper > 0) {
            audit(tenantId, actor, month, telegram, email, paper, number);
        }
        if (email > 0) {
            queue.wakeAfterCommit(tenantId, Channel.EMAIL);
        }
        if (telegram > 0) {
            queue.wakeAfterCommit(tenantId, Channel.TELEGRAM);
        }
        return new Plan(telegram, email, paper, telegram + email);
    }

    private void audit(UUID tenantId, AccessProfile actor, YearMonth month, int telegram, int email, int paper,
            @Nullable String number) {
        List<FieldChange> fields = new ArrayList<>(List.of(
                new FieldChange("month", null, month.toString()),
                new FieldChange("telegram", null, Integer.toString(telegram)),
                new FieldChange("email", null, Integer.toString(email)),
                new FieldChange("paper", null, Integer.toString(paper))));
        if (number != null) {
            fields.add(new FieldChange("number", null, number));
        }
        events.publishEvent(AuditEvent.now(tenantId, new Actor(actor.memberId(), actor.subject(),
                actor.displayName()), ACTION, ENTITY_TYPE, monthEntity(month), month.toString(), fields));
    }

    /** The entity id of the month's invoices: the name-based UUID of {@code invoices:YYYY-MM}, as the build's. */
    static UUID monthEntity(YearMonth month) {
        return UUID.nameUUIDFromBytes(("invoices:" + month).getBytes(StandardCharsets.UTF_8));
    }
}
