package app.nadel.notifications.internal;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import app.nadel.members.MemberDirectory;
import app.nadel.notifications.FakeBotApi;
import app.nadel.notifications.Mailpit;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A throw-away cooperative per test for the delivery of invoices (feature 017 §2.3, §2.4): the seeded roles and charge
 * types, requisites, a chairman and a treasurer (tokens), members with plots and charges of the current month written
 * as the database owner, the month's invoices built through the API, the mailbox pointing at Mailpit and the bot at
 * the in-JVM fake Bot API, Telegram links made through the bot's own code path. The sending runs through a
 * dispatcher of the test with a movable clock and a pause that only moves the clock, so attempts and pace are checked
 * without waiting. After the test everything of the cooperative is removed.
 */
abstract class DeliveryTestSupport extends IntegrationTest {

    static final String TOKEN = "123456789:AAdeliveryTestsToken0001";
    static final String BOT = "coop_delivery_bot";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    DeliveryRows rows;

    @Autowired
    DeliveryLanes lanes;

    @Autowired
    DeliveryQueue queue;

    @Autowired
    InvoiceMessages invoiceMessages;

    @Autowired
    PaymentMessages paymentMessages;

    @Autowired
    AnnouncementMessages announcementMessages;

    @Autowired
    DeliverySettingsService settingsService;

    @Autowired
    MemberDirectory memberDirectory;

    @Autowired
    TelegramLinks telegramLinks;

    @Autowired
    LinkCodes linkCodes;

    @Autowired
    MailChannel mailChannel;

    @Autowired
    TelegramChannel telegramChannel;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    final Mailpit mailpit = Mailpit.shared();
    final FakeBotApi telegram = FakeBotApi.shared();
    /** Starts at the first dispatch, after the rows were queued by the real clock. */
    final MovableClock clock = new MovableClock(null);
    /** Every pause the senders asked for. */
    final List<Duration> pauses = new ArrayList<>();

    UUID tenant;
    String chairman;
    String treasurer;
    private UUID lastPerson;

    @BeforeAll
    static void createOwnerRole() {
        ownerJdbc().execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nadel_owner') THEN
                        CREATE ROLE nadel_owner NOLOGIN BYPASSRLS;
                    END IF;
                END
                $$""");
        ownerJdbc().execute("GRANT USAGE ON SCHEMA public TO nadel_owner");
    }

    @BeforeEach
    void createCooperative() {
        mailpit.clear();
        mailpit.refuseRecipients(false);
        telegram.reset();
        telegram.username(BOT);
        tenant = UUID.randomUUID();
        ownerJdbc().update("insert into tenant (id, name, edrpou) values (?, 'СТ «Розсилка»', '12345678')", tenant);
        ownerJdbc().queryForList("select seed_base_roles(?)", tenant);
        ownerJdbc().update("insert into cooperative_settings (tenant_id, official_name, legal_address, bank_name, iban) "
                + "values (?, 'Садівниче товариство «Розсилка»', 'с. Тестове, вул. Садова, 1', 'АТ «Тестбанк»', "
                + "'UA850000000000000000000123456')", tenant);
        chairman = person("CHAIRMAN", "Бондаренко", "Іван", "board-" + UUID.randomUUID() + "@delivery.test");
        treasurer = person("TREASURER", "Коваленко", "Олена", "cash-" + UUID.randomUUID() + "@delivery.test");
    }

    @AfterEach
    void removeCooperative() {
        mailpit.refuseRecipients(false);
        telegram.reset();
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                    "select count(*) from event_publication where completion_date is null and serialized_event like ?",
                    Integer.class, "%" + tenant + "%") == 0);
        } catch (ConditionTimeoutException e) {
            // a listener that failed stays incomplete; the clean-up goes on
        }
        UUID id = tenant;
        // the jobs a send or a run scheduled for this cooperative
        ownerJdbc().update("delete from jobrunr_jobs where jobasjson like ?", "%" + id + "%");
        asLedgerOwner(sql -> {
            for (String table : List.of("delivery", "announcement", "telegram_link_code", "telegram_link", "delivery_lane",
                    "bot_poll_state", "delivery_settings")) {
                sql.update("delete from " + table + " where tenant_id = ?", id);
            }
            // releases point at their allocation, reversals at their payment: the pointing rows first
            sql.update("delete from allocation where tenant_id = ? and releases_allocation_id is not null", id);
            sql.update("delete from allocation where tenant_id = ?", id);
            sql.update("delete from payment where tenant_id = ? and reverses_payment_id is not null", id);
            sql.update("delete from payment where tenant_id = ?", id);
            sql.update("delete from receipt_counter where tenant_id = ?", id);
            sql.update("delete from charge where tenant_id = ?", id);
            sql.update("delete from invoice where tenant_id = ?", id);
            sql.update("delete from document_counter where tenant_id = ?", id);
            sql.update("delete from billing_month where tenant_id = ?", id);
        });
        for (String table : List.of("audit_entry", "plot_status", "ownership", "plot", "member_role",
                "role_permission", "role", "member", "charge_type", "cooperative_settings")) {
            ownerJdbc().update("delete from " + table + " where tenant_id = ?", id);
        }
        deleteDataKeys(id);
        ownerJdbc().update("delete from tenant where id = ?", id);
    }

    /** Runs the work as nadel_owner with app.ledger_maintenance = 'on', in one transaction. */
    static void asLedgerOwner(Consumer<JdbcTemplate> work) {
        JdbcTemplate sql = ownerJdbc();
        new TransactionTemplate(new DataSourceTransactionManager(sql.getDataSource())).executeWithoutResult(status -> {
            sql.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO nadel_owner");
            sql.execute("SET LOCAL ROLE nadel_owner");
            sql.execute("SET LOCAL app.ledger_maintenance = 'on'");
            work.accept(sql);
        });
    }

    // ---- people, plots, charges and invoices

    /** A member with one base role and an e-mail (sealed); returns a token. */
    String person(String baseRole, String lastName, String firstName, String email) {
        UUID id = UUID.randomUUID();
        lastPerson = id;
        ownerJdbc().update("insert into member (id, tenant_id, last_name, first_name) values (?, ?, ?, ?)", id, tenant,
                lastName, firstName);
        insertContacts(tenant, id, email);
        ownerJdbc().update("insert into member_role (tenant_id, member_id, role_id) "
                + "select ?, ?, id from role where tenant_id = ? and base_code = ?", tenant, id, tenant, baseRole);
        return TestTokens.bearer(tenant, "sub-" + email, email);
    }

    /** The member behind the last {@link #person}. */
    UUID lastPerson() {
        return lastPerson;
    }

    /** A member (base role MEMBER) with the channel and, when not null, an e-mail. */
    UUID member(String lastName, String channel, String email) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into member (id, tenant_id, last_name, first_name) values (?, ?, ?, 'Тест')", id,
                tenant, lastName);
        insertContacts(tenant, id, email);
        if ("VIBER".equals(channel)) {
            setContact(tenant, id, "viber_phone", "+380501234567");
        }
        ownerJdbc().update("update member set delivery_channel = ? where id = ?", channel, id);
        ownerJdbc().update("insert into member_role (tenant_id, member_id, role_id) "
                + "select ?, ?, id from role where tenant_id = ? and base_code = 'MEMBER'", tenant, id, tenant);
        return id;
    }

    /** A plot on the street Садова with its owners (equal shares). */
    UUID plot(String number, UUID... owners) {
        return plotOn("Садова", number, owners);
    }

    /** A plot on the street with its owners (equal shares). */
    UUID plotOn(String street, String number, UUID... owners) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, street, kind, area_m2) values (?, ?, ?, ?, "
                + "'PLOT', 600)", id, tenant, number, street);
        ownerJdbc().update("insert into plot_status (plot_id, tenant_id, status) values (?, ?, 'GREY')", id, tenant);
        for (UUID owner : owners) {
            ownerJdbc().update("insert into ownership (id, tenant_id, plot_id, member_id, share_numerator, "
                    + "share_denominator, valid_from) values (?, ?, ?, ?, 1, ?, date '2020-01-01')", UUID.randomUUID(),
                    tenant, id, owner, owners.length);
        }
        return id;
    }

    /** A membership fee of the plot booked in the current month. */
    void charge(UUID plot, String amount) {
        String month = month().toString();
        ownerJdbc().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month + "-01");
        UUID monthId = ownerJdbc().queryForObject("select id from billing_month where tenant_id = ? and month = "
                + "?::date", UUID.class, tenant, month + "-01");
        UUID type = ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? and code = "
                + "'MEMBERSHIP'", UUID.class, tenant);
        UUID id = UUID.randomUUID();
        LocalDate first = LocalDate.parse(month + "-01");
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, 'REGULAR', "
                + "'TARIFF', ?, ?, ?, ?, ?)", id, tenant, plot, type, monthId, new BigDecimal(amount), first,
                first.withDayOfMonth(first.lengthOfMonth()), first, "TEST:" + id);
    }

    /** The current month in Kyiv. */
    static YearMonth month() {
        return YearMonth.now(ZoneId.of("Europe/Kyiv"));
    }

    /** Builds the month's invoices (also those with nothing due) as the treasurer. */
    void buildInvoices() throws Exception {
        ownerJdbc().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month() + "-01");
        send(treasurer, post("/api/v1/billing-months/" + month() + "/invoices?preview=false"),
                Map.of("includeNothingDue", true)).andExpect(status().isOk());
    }

    /** The latest invoice of the plot this month. */
    UUID invoiceOf(UUID plot) {
        return ownerJdbc().queryForObject("select id from invoice where plot_id = ? order by version desc limit 1",
                UUID.class, plot);
    }

    // ---- channels

    /** The cooperative's mailbox on Mailpit, with the given password, as the chairman saves it. */
    void mailbox(String password) throws Exception {
        mailbox(mailpit.host(), mailpit.smtpPort(), password);
    }

    void mailbox(String host, int port, String password) throws Exception {
        Map<String, Object> email = new LinkedHashMap<>();
        email.put("host", host);
        email.put("port", port);
        email.put("user", Mailpit.USER);
        email.put("password", password);
        email.put("fromAddress", "board@rozsylka.example");
        email.put("fromName", "СТ «Розсилка»");
        send(chairman, put("/api/v1/settings/delivery"), Map.of("email", email)).andExpect(status().isOk());
    }

    /** The cooperative's bot on the fake Bot API, with its @name known. */
    void bot() throws Exception {
        send(chairman, put("/api/v1/settings/delivery"), Map.of("telegram", Map.of("botToken", TOKEN)))
                .andExpect(status().isOk());
        send(chairman, post("/api/v1/settings/delivery/test-telegram"), null).andExpect(status().isOk());
    }

    /** Links a new chat to the member as the bot does with an account code; returns the chat id. */
    long link(UUID member) {
        long chat = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
        TenantContext.runAs(tenant, () -> {
            String code = linkCodes.issue(member, LinkCodes.Purpose.ACCOUNT).code();
            return telegramLinks.redeem(chat, LinkCodes.hash(code), false);
        });
        return chat;
    }

    /** Whether the member's Telegram is linked now. */
    boolean linked(UUID member) {
        return TenantContext.runAs(tenant, () -> telegramLinks.linked(member));
    }

    // ---- sending

    /** A dispatcher of this test: the movable clock, a pause that moves it, the given holder id. */
    DeliveryDispatcher dispatcher(String instanceId) {
        return dispatcher(instanceId, invoiceMessages, queue);
    }

    /** A dispatcher of this test with its own invoice messages and queue (the failure tests). */
    DeliveryDispatcher dispatcher(String instanceId, InvoiceMessages invoices, DeliveryQueue lanesQueue) {
        clock.start();
        RateLimiters limiters = new RateLimiters(clock, pause -> {
            pauses.add(pause);
            clock.advance(pause);
        }, Duration.ofSeconds(1), 20);
        return new DeliveryDispatcher(instanceId, rows, lanes, lanesQueue, invoices, paymentMessages,
                announcementMessages, settingsService, memberDirectory, telegramLinks, mailChannel, telegramChannel, limiters, clock,
                DeliveryProperties.defaults(), jdbc, transactionManager);
    }

    /** One run of the lane by a dispatcher of this test. */
    DeliveryDispatcher.Run dispatch(String channel) {
        return dispatcher("test-" + UUID.randomUUID()).dispatch(tenant, Channel.valueOf(channel));
    }

    /** The deliveries of the cooperative: member, channel, status, failure code, note, attempts, resends. */
    List<Map<String, Object>> deliveries() {
        return ownerJdbc().queryForList("select id, invoice_id, member_id, channel, status, failure_code, note, "
                + "attempts, resends, next_attempt_at, sent_at from delivery where tenant_id = ? order by created_at, id",
                tenant);
    }

    List<Map<String, Object>> deliveriesOf(UUID member) {
        return deliveries().stream().filter(row -> member.equals(row.get("member_id"))).toList();
    }

    /** The audit entries INVOICES_SENT of the cooperative, once written. */
    List<Map<String, Object>> sentAudit(int expected) {
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from audit_entry where tenant_id = ? and action = 'INVOICES_SENT'", Integer.class,
                tenant) >= expected);
        return ownerJdbc().queryForList("select entity_type, entity_label, cast(changes as text) as changes "
                + "from audit_entry where tenant_id = ? and action = 'INVOICES_SENT' order by occurred_at", tenant);
    }

    // ---- requests

    ResultActions sendMonth(String bearer, boolean preview, Boolean includeNothingDue) throws Exception {
        return send(bearer, post("/api/v1/billing-months/" + month() + "/invoices/send?preview=" + preview),
                includeNothingDue == null ? null : Map.of("includeNothingDue", includeNothingDue));
    }

    JsonNode sentMonth(boolean preview, Boolean includeNothingDue) throws Exception {
        return body(sendMonth(treasurer, preview, includeNothingDue).andExpect(status().isOk()));
    }

    ResultActions sendOne(String bearer, UUID invoice) throws Exception {
        return send(bearer, post("/api/v1/invoices/" + invoice + "/send"), null);
    }

    ResultActions listDeliveries(String bearer, String query) throws Exception {
        return send(bearer, get("/api/v1/deliveries" + (query == null ? "" : "?" + query)), null);
    }

    ResultActions retry(String bearer, UUID delivery) throws Exception {
        return send(bearer, post("/api/v1/deliveries/" + delivery + "/retry"), null);
    }

    ResultActions printDone(String bearer) throws Exception {
        return send(bearer, post("/api/v1/billing-months/" + month() + "/print-done"), null);
    }

    ResultActions send(String bearer, MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (bearer != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    /** A clock the test moves. */
    static final class MovableClock extends Clock {

        private volatile Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        /**
         * Starts at the real time unless started, cut to microseconds: PostgreSQL keeps {@code timestamptz} in
         * microseconds, and the system clock of Linux has nanoseconds, so an instant of a finer clock would not read
         * back equal to what was stored.
         */
        void start() {
            if (now == null) {
                now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            }
        }

        void advance(Duration duration) {
            start();
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            start();
            return now;
        }
    }
}
