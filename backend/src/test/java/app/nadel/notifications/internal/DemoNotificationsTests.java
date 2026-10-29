package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.nadel.documents.DocumentsFacade;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.members.MemberDirectory;
import app.nadel.notifications.FakeBotApi;
import app.nadel.plots.PlotOwners;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.tenancy.TenantDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The demo delivery (feature 017, technical considerations 2.10): {@link DemoNotifications} gives the demo cooperative
 * the Mailpit mailbox and the fake Telegram bot, links two demo members to fake chats through the code path the bot
 * uses, and writes the deliveries of its first billing month as sent (e-mail, Telegram) or waiting to be printed
 * (paper) without sending anything; a second run writes nothing; the second demo cooperative stays without delivery
 * settings. The runner is not registered in the tests (the demo ledger placeholder is off), so it is built here from
 * the module's beans, and the month's invoices are inserted directly for four demo plots.
 */
class DemoNotificationsTests extends NotificationsTestSupport {

    private static final YearMonth MONTH = YearMonth.of(2031, 3);
    /** Owners: 8 (Telegram), 10 (Viber); 12 (paper); 3 (member@demo.local, e-mail) and 7 (e-mail); 13 (Telegram). */
    private static final List<String> PLOTS = List.of("11", "2", "7", "4");
    private static final UUID VIBER_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000010");
    private static final UUID PAPER_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000012");
    /** Prefers Telegram but is not linked: e-mail. */
    private static final UUID UNLINKED_TELEGRAM_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000013");

    private final JdbcTemplate owner = ownerJdbc();

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DeliverySettingsService settings;

    @Autowired
    private LinkCodes codes;

    @Autowired
    private TelegramLinks links;

    @Autowired
    private MemberDirectory members;

    @Autowired
    private LedgerReadModel ledger;

    @Autowired
    private DocumentsFacade documents;

    @Autowired
    private PlotOwners owners;

    @Autowired
    private RecipientChannels recipients;

    @Autowired
    private TenantDirectory tenants;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PersonalDataCipher cipher;

    @Autowired
    private Clock clock;

    private DemoNotifications demo() {
        return new DemoNotifications(transactionManager, settings, codes, links, members, ledger, documents, owners,
                recipients, tenants, jdbc, clock);
    }

    @BeforeEach
    void theMonthAndItsInvoices() {
        FakeBotApi.shared().reset();
        owner.update("insert into billing_month (tenant_id, month) values (?, ?) on conflict do nothing", DEMO_TENANT,
                MONTH.atDay(1));
        for (String number : PLOTS) {
            invoice(DEMO_TENANT, plotId(number), 1);
        }
    }

    @AfterEach
    void removeTheDemoDelivery() {
        await().atMost(Duration.ofSeconds(10)).until(() -> owner.queryForObject(
                "select count(*) from event_publication where completion_date is null", Integer.class) == 0);
        asNadelOwner(sql -> {
            sql.update("delete from audit_entry where tenant_id = ? and action in "
                    + "('DELIVERY_SETTINGS_CHANGED', 'TELEGRAM_LINKED')", DEMO_TENANT);
            sql.update("delete from delivery where tenant_id = ?", DEMO_TENANT);
            sql.update("delete from invoice where tenant_id = ? and month = ?", DEMO_TENANT, MONTH.atDay(1));
            sql.update("delete from billing_month where tenant_id = ? and month = ?", DEMO_TENANT, MONTH.atDay(1));
        });
    }

    @Test
    void theRunnerIsNotRegisteredInTheTests() {
        assertThat(context.getBeansOfType(DemoNotifications.class)).isEmpty();
    }

    @Test
    void theDemoCooperativeGetsItsMailboxBotLinksAndASentMonthOnce() {
        DemoNotifications.Seeded first = demo().seed();

        assertThat(first.mailbox()).isTrue();
        assertThat(first.bot()).isTrue();
        assertThat(first.links()).isEqualTo(2);
        assertThat(first.month()).isEqualTo(MONTH);
        assertThat(first.deliveries()).isPositive();

        // the mailbox and the bot; the second cooperative stays unconfigured
        Map<String, Object> row = owner.queryForMap("select smtp_host, smtp_port, smtp_security, smtp_user, "
                + "smtp_password_enc, smtp_from_address, smtp_from_name, bot_token_last4, bot_username, "
                + "notify_payments from delivery_settings where tenant_id = ?", DEMO_TENANT);
        assertThat(row).containsEntry("smtp_host", "localhost").containsEntry("smtp_port", 1025)
                .containsEntry("smtp_security", null).containsEntry("smtp_user", null)
                .containsEntry("smtp_password_enc", null).containsEntry("smtp_from_address", "board@demo.local")
                .containsEntry("smtp_from_name", "СТ «Надія»").containsEntry("bot_token_last4", "oken")
                .containsEntry("bot_username", "nadel_demo_bot").containsEntry("notify_payments", true);
        assertThat(owner.queryForObject("select count(*) from delivery_settings where tenant_id = ?", Integer.class,
                OTHER_TENANT)).isZero();
        String token = inTenant(DEMO_TENANT, sql -> cipher.decrypt(DEMO_TENANT, new FieldRef("delivery_settings",
                "bot_token_enc", DEMO_TENANT), sql.queryForObject("select bot_token_enc from delivery_settings",
                byte[].class)));
        assertThat(token).isEqualTo(DemoNotifications.BOT_TOKEN);
        assertThat(owner.queryForObject("select position(convert_to(?, 'UTF8') in bot_token_enc) from "
                + "delivery_settings where tenant_id = ?", Integer.class, DemoNotifications.BOT_TOKEN, DEMO_TENANT))
                .isZero();

        // two active links, the chat ids sealed
        List<Map<String, Object>> linkRows = owner.queryForList("select id, member_id, chat_id_enc from telegram_link "
                + "where tenant_id = ? and ended_at is null order by member_id", DEMO_TENANT);
        assertThat(linkRows).extracting(link -> link.get("member_id"))
                .containsExactly(DemoNotifications.DEMO_MEMBER, DemoNotifications.TELEGRAM_MEMBER);
        assertThat(linkRows).extracting(link -> chatOf(link))
                .containsExactly("700000001", "700000002");
        for (String chat : List.of("700000001", "700000002")) {
            assertThat(owner.queryForObject("select count(*) from telegram_link where tenant_id = ? "
                    + "and position(convert_to(?, 'UTF8') in chat_id_enc) > 0", Integer.class, DEMO_TENANT, chat))
                    .isZero();
        }

        // the month's deliveries: sent or waiting to be printed, nothing queued, nothing sent
        List<Map<String, Object>> deliveries = deliveries();
        assertThat(deliveries).hasSize(first.deliveries());
        assertThat(deliveries).allSatisfy(delivery -> {
            assertThat(delivery.get("billing_month")).isEqualTo(java.sql.Date.valueOf(MONTH.atDay(1)));
            if ("PAPER".equals(delivery.get("channel"))) {
                assertThat(delivery.get("status")).isEqualTo("TO_PRINT");
                assertThat(delivery.get("sent_at")).isNull();
            } else {
                assertThat(delivery.get("status")).isEqualTo("SENT");
                assertThat(delivery.get("sent_at")).isNotNull();
                assertThat(delivery.get("attempts")).isEqualTo(1);
            }
            assertThat(delivery.get("next_attempt_at")).isNull();
        });
        assertThat(channelOf(deliveries, DemoNotifications.TELEGRAM_MEMBER)).isEqualTo("TELEGRAM");
        assertThat(channelOf(deliveries, DemoNotifications.DEMO_MEMBER)).isEqualTo("EMAIL");
        assertThat(channelOf(deliveries, UNLINKED_TELEGRAM_MEMBER)).isEqualTo("EMAIL");
        assertThat(channelOf(deliveries, PAPER_MEMBER)).isEqualTo("PAPER");
        assertThat(channelOf(deliveries, VIBER_MEMBER)).isEqualTo("PAPER");
        assertThat(deliveries).filteredOn(delivery -> VIBER_MEMBER.equals(delivery.get("member_id")))
                .extracting(delivery -> delivery.get("note")).containsExactly("VIBER_UNSUPPORTED");
        assertThat(FakeBotApi.shared().calls()).isEmpty();

        DemoNotifications.Seeded second = demo().seed();

        assertThat(second).isEqualTo(new DemoNotifications.Seeded(false, false, 0, MONTH, 0));
        assertThat(deliveries()).isEqualTo(deliveries);
        assertThat(owner.queryForObject("select count(*) from telegram_link where tenant_id = ?", Integer.class,
                DEMO_TENANT)).isEqualTo(2);
        assertThat(owner.queryForObject("select settings_version from delivery_settings where tenant_id = ?",
                Integer.class, DEMO_TENANT)).isEqualTo(2);
    }

    @Test
    void theOwnersOwnSettingsStay() {
        settings(DEMO_TENANT, true);

        DemoNotifications.Seeded seeded = demo().seed();

        assertThat(seeded.mailbox()).isTrue();
        assertThat(seeded.bot()).isFalse();
        assertThat(owner.queryForMap("select bot_token_last4, bot_username from delivery_settings where tenant_id = ?",
                DEMO_TENANT)).containsEntry("bot_token_last4", "1234").containsEntry("bot_username", "demo_bot");
    }

    private UUID plotId(String number) {
        return owner.queryForObject("select id from plot where tenant_id = ? and number = ? and archived_at is null",
                UUID.class, DEMO_TENANT, number);
    }

    private String chatOf(Map<String, Object> link) {
        UUID id = (UUID) link.get("id");
        return inTenant(DEMO_TENANT, sql -> cipher.decrypt(DEMO_TENANT, new FieldRef("telegram_link", "chat_id_enc",
                id), (byte[]) link.get("chat_id_enc")));
    }

    private List<Map<String, Object>> deliveries() {
        return owner.queryForList("select id, kind, invoice_id, billing_month, member_id, channel, note, status, "
                + "attempts, next_attempt_at, sent_at from delivery where tenant_id = ? order by id", DEMO_TENANT);
    }

    private static String channelOf(List<Map<String, Object>> deliveries, UUID member) {
        List<Object> channels = deliveries.stream().filter(delivery -> member.equals(delivery.get("member_id")))
                .map(delivery -> delivery.get("channel")).distinct().toList();
        assertThat(channels).as("the channel of %s", member).hasSize(1);
        return (String) channels.getFirst();
    }
}
