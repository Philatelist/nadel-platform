package app.nadel.notifications.internal;

import app.nadel.documents.DocumentsFacade;
import app.nadel.documents.MonthInvoice;
import app.nadel.identity.AccessProfile;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import app.nadel.notifications.internal.ChannelResolver.Resolution;
import app.nadel.notifications.internal.DeliverySettingsService.Change;
import app.nadel.notifications.internal.DeliverySettingsService.EmailChange;
import app.nadel.plots.PlotOwners;
import app.nadel.problem.ApiProblemException;
import app.nadel.tenancy.TenantContext;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sets up delivery for the demo cooperative (feature 017, technical considerations 2.10) once the application has
 * started, so {@code make up} shows the channels, a linked bot and a sent month without any manual step. The second
 * demo cooperative stays without a mailbox and a bot.
 * <p>
 * <b>When.</b> Registered under the same condition as the demo documents runner of the documents module: the
 * {@code db/demo} location is active and the demo ledger placeholder {@code demoledger} is {@code on} (the {@code dev}
 * profile). Production has neither; the integration tests turn the placeholder off, so the runner never starts there
 * ({@code DemoNotificationsTests} calls {@link #seed}). It runs after that runner ({@link Order}), whose invoices it
 * marks as sent.
 * <p>
 * <b>What</b>, under {@code TenantContext.runAs} for the demo cooperative, through the module's own services:
 * <ul>
 *   <li>the mailbox: Mailpit of {@code infra/docker-compose.yml} ({@value #SMTP_HOST}:{@value #SMTP_PORT}, no TLS, no
 *       user or password: Mailpit takes any), the sender {@value #FROM_ADDRESS} named after the cooperative, and the
 *       bot token {@value #BOT_TOKEN} for the fake Telegram server of {@code infra/fake-telegram} (the {@code dev}
 *       profile points {@code nadel.telegram.api-url} at it), with its @name {@value #BOT_USERNAME} as that server's
 *       {@code getMe} reports it; payment messages on. Saved through {@link DeliverySettingsService#update}, so the
 *       token is sealed and the change audited (subject {@value #SYSTEM}); each part only while it is not set, so the
 *       owner's own settings stay;</li>
 *   <li>two Telegram links with fake chat ids, through a link code and {@link TelegramLinks#redeem} as the bot would
 *       (the chat id sealed, audited as {@code TELEGRAM_LINKED}): the member behind {@code member@demo.local} (chat
 *       {@value #MEMBER_CHAT}, so the owner can talk to the bot as that user through the fake server's control API;
 *       their invoice channel stays e-mail) and Дмитро Кравченко, who prefers Telegram (chat {@value #TELEGRAM_CHAT});
 *       a member already linked, or a chat linked to someone else, is left as it is;</li>
 *   <li>the month the demo documents runner builds (the cooperative's first billing month, the closed one) "sent":
 *       its latest invoices with something due, to every current owner of the plot through the channel of the rule of
 *       invoices ({@link RecipientChannels}), written directly as {@code SENT} (e-mail and Telegram, one attempt, sent
 *       now) or {@code TO_PRINT} (paper). Nothing is queued or sent, so a start never floods Mailpit or the fake
 *       Telegram server; {@code ON CONFLICT DO NOTHING} keeps a second run from adding anything.</li>
 * </ul>
 * Idempotent: a second run finds everything and writes nothing. A failure is logged with its problem code or class only
 * and never stops the application. The log has counts only.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnExpression("'${spring.flyway.locations:}'.contains('db/demo') "
        + "and '${spring.flyway.placeholders.demoledger:off}' == 'on'")
class DemoNotifications implements ApplicationRunner {

    /** The demo cooperative of {@code R__demo_data.sql}. */
    static final UUID DEMO_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** The demo member behind {@code member@demo.local}. */
    static final UUID DEMO_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");

    /** Дмитро Кравченко, a demo member who prefers Telegram (plot 11). */
    static final UUID TELEGRAM_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000008");

    static final long MEMBER_CHAT = 700000001L;
    static final long TELEGRAM_CHAT = 700000002L;

    static final String SMTP_HOST = "localhost";
    static final int SMTP_PORT = 1025;
    static final String FROM_ADDRESS = "board@demo.local";
    /** Matches the token pattern of the contract; only the fake Telegram server of the dev profile accepts it. */
    static final String BOT_TOKEN = "123456:DEMO-fake-telegram-token";
    /** What the fake Telegram server's {@code getMe} answers. */
    static final String BOT_USERNAME = "nadel_demo_bot";

    /** The audit subject of the settings change. */
    static final String SYSTEM = "system:notifications";

    private static final Logger log = LoggerFactory.getLogger(DemoNotifications.class);

    /** The links the demo makes: member and chat. */
    private static final Map<UUID, Long> LINKS = Map.of(DEMO_MEMBER, MEMBER_CHAT, TELEGRAM_MEMBER, TELEGRAM_CHAT);

    /**
     * What one run did: whether it saved the mailbox and the bot, the links it made, the month it marked as sent (null
     * when the cooperative has no billing month) and the delivery rows it wrote.
     */
    record Seeded(boolean mailbox, boolean bot, int links, @Nullable YearMonth month, int deliveries) {
    }

    private final TransactionTemplate transaction;
    private final DeliverySettingsService settings;
    private final LinkCodes codes;
    private final TelegramLinks links;
    private final MemberDirectory members;
    private final LedgerReadModel ledger;
    private final DocumentsFacade documents;
    private final PlotOwners owners;
    private final RecipientChannels recipients;
    private final TenantDirectory tenants;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    DemoNotifications(PlatformTransactionManager transactionManager, DeliverySettingsService settings,
            LinkCodes codes, TelegramLinks links, MemberDirectory members, LedgerReadModel ledger,
            DocumentsFacade documents, PlotOwners owners, RecipientChannels recipients, TenantDirectory tenants,
            JdbcTemplate jdbc, Clock clock) {
        this.transaction = new TransactionTemplate(transactionManager);
        this.settings = settings;
        this.codes = codes;
        this.links = links;
        this.members = members;
        this.ledger = ledger;
        this.documents = documents;
        this.owners = owners;
        this.recipients = recipients;
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Seeded seeded = seed();
            log.info("Demo delivery: mailbox saved: {}, bot saved: {}, {} Telegram links made, month {}, "
                    + "{} delivery rows written", seeded.mailbox(), seeded.bot(), seeded.links(), seeded.month(),
                    seeded.deliveries());
        } catch (ApiProblemException e) {
            log.warn("Demo delivery not set up: {}", e.code());
        } catch (RuntimeException e) {
            log.warn("Demo delivery not set up: {}", e.getClass().getName());
        }
    }

    /** Sets up what is missing of the demo delivery (see the class comment). */
    Seeded seed() {
        return TenantContext.runAs(DEMO_TENANT, this::seedDemo);
    }

    private Seeded seedDemo() {
        DeliverySettingsValues before = settings.current();
        boolean mailbox = !before.emailConfigured();
        boolean bot = !before.botConfigured();
        if (mailbox || bot) {
            String name = transaction.execute(status -> tenants.current().map(TenantSummary::name).orElse(null));
            EmailChange email = mailbox
                    ? new EmailChange(SMTP_HOST, SMTP_PORT, null, null, null, FROM_ADDRESS, name)
                    : null;
            AccessProfile system = new AccessProfile(DEMO_TENANT, null, SYSTEM, "", Set.of(), List.of(), false);
            settings.update(new Change(email, bot ? BOT_TOKEN : null, null, true), system);
        }
        DeliverySettingsValues after = settings.current();
        if (after.botConfigured() && after.botUsername() == null) {
            settings.rememberBotUsername(after.settingsVersion(), BOT_USERNAME);
        }
        int linked = 0;
        for (Map.Entry<UUID, Long> link : LINKS.entrySet()) {
            if (Boolean.TRUE.equals(transaction.execute(status -> link(link.getKey(), link.getValue())))) {
                linked++;
            }
        }
        YearMonth month = transaction.execute(status -> ledger.firstBillingMonth().orElse(null));
        Integer written = month == null ? Integer.valueOf(0) : transaction.execute(status -> markSent(month));
        return new Seeded(mailbox, bot, linked, month, Objects.requireNonNullElse(written, 0));
    }

    /** Links the member's Telegram to the chat as {@code /start <code>} would; false when nothing was linked. */
    private boolean link(UUID memberId, long chatId) {
        MemberRef member = members.find(List.of(memberId)).get(memberId);
        if (member == null || member.archived() || links.linked(memberId)) {
            return false;
        }
        LinkCodes.Issued code = codes.issue(memberId, LinkCodes.Purpose.ACCOUNT);
        return links.redeem(chatId, LinkCodes.hash(code.code()), false) instanceof TelegramLinks.Linked;
    }

    /** Writes the month's invoice deliveries as already sent or waiting to be printed; the rows written. */
    private int markSent(YearMonth month) {
        List<MonthInvoice> invoices = documents.latestInvoices(month).stream()
                .filter(invoice -> invoice.due().signum() > 0)
                .toList();
        Map<UUID, List<UUID>> plotOwners = owners.currentOwnerIds(invoices.stream().map(MonthInvoice::plotId)
                .distinct().toList());
        Map<UUID, Resolution> channels = recipients.resolve(plotOwners.values().stream().flatMap(List::stream)
                .distinct().toList(), settings.current());
        Instant now = clock.instant();
        int written = 0;
        for (MonthInvoice invoice : invoices) {
            for (UUID member : plotOwners.getOrDefault(invoice.plotId(), List.of()).stream().distinct().toList()) {
                Resolution resolution = channels.get(member);
                if (resolution != null && insert(invoice, member, resolution, now)) {
                    written++;
                }
            }
        }
        return written;
    }

    private boolean insert(MonthInvoice invoice, UUID member, Resolution resolution, Instant now) {
        boolean paper = resolution.channel() == Channel.PAPER;
        return jdbc.update("insert into delivery (tenant_id, kind, invoice_id, invoice_version, billing_month, "
                + "member_id, channel, note, status, attempts, sent_at, created_at) "
                + "values (?, 'INVOICE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing", DEMO_TENANT,
                invoice.invoiceId(), invoice.version(), invoice.month().atDay(1), member, resolution.channel().name(),
                resolution.note(), paper ? DeliveryRows.TO_PRINT : DeliveryRows.SENT, paper ? 0 : 1,
                paper ? null : now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC)) == 1;
    }
}
