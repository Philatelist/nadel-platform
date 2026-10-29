package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The delivery tables (V29): cooperatives are isolated (RLS with USING and WITH CHECK, nothing visible without a
 * cooperative), links never cross cooperatives (composite foreign keys), and the same chat may serve two cooperatives.
 */
class NotificationsTenantIsolationTests extends NotificationsTestSupport {

    /** One harmless UPDATE per table the application role may update. */
    private static final Map<String, String> UPDATES = Map.of(
            "delivery_settings", "update delivery_settings set smtp_host = 'changed'",
            "telegram_link", "update telegram_link set ended_at = now(), end_reason = 'UNLINKED'",
            "telegram_link_code", "update telegram_link_code set used_at = now()",
            "delivery", "update delivery set attempts = attempts + 1",
            "delivery_lane", "update delivery_lane set lease_owner = 'changed'",
            "bot_poll_state", "update bot_poll_state set last_update_id = 99");

    private UUID demoMember;
    private UUID otherMember;
    private UUID demoInvoice;
    private UUID otherInvoice;
    private UUID otherPlot;
    private UUID otherAnnouncement;
    private UUID demoAnnouncement;

    @BeforeEach
    void createFixtures() {
        demoMember = newMember(DEMO_TENANT, "iso-demo@notifications.local");
        otherMember = newMember(OTHER_TENANT, "iso-other@notifications.local");
        UUID demoPlot = plot(DEMO_TENANT);
        otherPlot = plot(OTHER_TENANT);
        demoInvoice = invoice(DEMO_TENANT, demoPlot, 1);
        otherInvoice = invoice(OTHER_TENANT, otherPlot, 1);
        demoAnnouncement = announcement(DEMO_TENANT);
        otherAnnouncement = announcement(OTHER_TENANT);
        for (UUID tenant : List.of(DEMO_TENANT, OTHER_TENANT)) {
            boolean demo = tenant.equals(DEMO_TENANT);
            settings(tenant, false);
            link(tenant, demo ? demoMember : otherMember, 1);
            linkCode(tenant, demo ? demoMember : otherMember, 1);
            delivery(tenant, demo ? demoInvoice : otherInvoice, demo ? demoMember : otherMember, "EMAIL");
            ownerJdbc().update("insert into delivery_lane (tenant_id, channel) values (?, 'EMAIL')", tenant);
            ownerJdbc().update("insert into bot_poll_state (tenant_id) values (?)", tenant);
        }
    }

    // ---- row level security

    @Test
    void everyTableHasRowLevelSecurityWithAPolicyAndATenantIndex() {
        for (String table : TABLES) {
            assertThat(ownerJdbc().queryForObject(
                    "select relrowsecurity from pg_class where oid = ('public.' || ?)::regclass", Boolean.class, table))
                    .as("RLS on %s", table).isTrue();
            assertThat(ownerJdbc().queryForObject(
                    "select relforcerowsecurity from pg_class where oid = ('public.' || ?)::regclass", Boolean.class,
                    table)).as("RLS not forced on %s", table).isFalse();
            assertThat(ownerJdbc().queryForObject(
                    "select count(*) from pg_policies where tablename = ? and policyname = 'tenant_isolation' "
                            + "and qual like '%current_tenant_id%' and with_check like '%current_tenant_id%'",
                    Integer.class, table)).as("policy on %s", table).isEqualTo(1);
            assertThat(ownerJdbc().queryForObject(
                    "select count(*) from pg_indexes where tablename = ? and indexdef like '%(tenant_id)'",
                    Integer.class, table)).as("tenant index on %s", table).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void eachCooperativeSeesOnlyItsOwnRows() {
        for (UUID tenant : List.of(DEMO_TENANT, OTHER_TENANT)) {
            for (String table : TABLES) {
                int own = 1;
                assertThat(count(tenant, table, "where tenant_id = '" + tenant + "'")).as(table).isEqualTo(own);
                assertThat(count(tenant, table, "where tenant_id <> '" + tenant + "'")).as(table).isZero();
            }
        }
    }

    @Test
    void withoutACooperativeNothingIsVisible() {
        UUID nobody = UUID.randomUUID();

        for (String table : TABLES) {
            assertThat(count(nobody, table, "")).as(table).isZero();
        }
    }

    @Test
    void cannotUpdateOrDeleteRowsOfAnotherCooperative() {
        for (Map.Entry<String, String> update : UPDATES.entrySet()) {
            Integer updated = inTenant(DEMO_TENANT, sql -> sql.update(update.getValue()
                    + " where tenant_id = '" + OTHER_TENANT + "'"));

            assertThat(updated).as("update of %s", update.getKey()).isZero();
            assertThat(ownerJdbc().queryForObject("select count(*) from " + update.getKey() + " where tenant_id = ?",
                    Integer.class, OTHER_TENANT)).isEqualTo(1);
        }
        assertThat(ownerJdbc().queryForObject("select smtp_host from delivery_settings where tenant_id = ?",
                String.class, OTHER_TENANT)).isNull();
        assertThat(ownerJdbc().queryForObject("select attempts from delivery where tenant_id = ?", Integer.class,
                OTHER_TENANT)).isZero();
        assertThat(ownerJdbc().queryForObject("select ended_at is null from telegram_link where tenant_id = ?",
                Boolean.class, OTHER_TENANT)).isTrue();

        Integer deleted = inTenant(DEMO_TENANT, sql -> sql.update(
                "delete from telegram_link_code where tenant_id = ?", OTHER_TENANT));
        assertThat(deleted).isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from telegram_link_code where tenant_id = ?",
                Integer.class, OTHER_TENANT)).isEqualTo(1);
    }

    @Test
    void cannotInsertRowsForAnotherCooperative() {
        UUID other = OTHER_TENANT;
        assertRowSecurity(sql -> sql.update("insert into delivery_settings (tenant_id) values (?)", other));
        assertRowSecurity(sql -> sql.update("insert into telegram_link (tenant_id, member_id, chat_id_enc, "
                + "chat_id_bidx) values (?, ?, '\\x01'::bytea, '\\x09'::bytea)", other, otherMember));
        assertRowSecurity(sql -> sql.update("insert into telegram_link_code (tenant_id, code_hash, member_id, purpose, "
                + "expires_at) values (?, '\\x09'::bytea, ?, 'PAPER', now() + interval '1 day')", other, otherMember));
        assertRowSecurity(sql -> sql.update("insert into announcement (tenant_id, text, channels) values (?, 'Hi', "
                + "array['EMAIL'])", other));
        assertRowSecurity(sql -> sql.update("insert into delivery (tenant_id, kind, invoice_id, invoice_version, "
                + "member_id, channel, status) values (?, 'INVOICE', ?, 1, ?, 'TELEGRAM', 'QUEUED')",
                other, otherInvoice, otherMember));
        assertRowSecurity(sql -> sql.update("insert into delivery_lane (tenant_id, channel) values (?, 'TELEGRAM')",
                other));
        assertRowSecurity(sql -> sql.update("insert into bot_poll_state (tenant_id) values (?)", other));
        for (String table : TABLES) {
            assertThat(ownerJdbc().queryForObject("select count(*) from " + table + " where tenant_id = ?",
                    Integer.class, OTHER_TENANT)).as(table).isEqualTo(1);
        }
    }

    @Test
    void cannotHandARowToAnotherCooperative() {
        assertRowSecurity(sql -> sql.update("update delivery_settings set tenant_id = ? where tenant_id = ?",
                OTHER_TENANT, DEMO_TENANT));
        assertRowSecurity(sql -> sql.update("update bot_poll_state set tenant_id = ?", OTHER_TENANT));
        assertThat(ownerJdbc().queryForObject("select count(*) from delivery_settings where tenant_id = ?",
                Integer.class, DEMO_TENANT)).isEqualTo(1);
    }

    // ---- composite foreign keys

    @Test
    void rowsCannotLinkRecordsOfAnotherCooperative() {
        assertForeignKey("telegram_link_member_fk", sql -> sql.update("insert into telegram_link (tenant_id, member_id, "
                + "chat_id_enc, chat_id_bidx) values (?, ?, '\\x01'::bytea, '\\x07'::bytea)", DEMO_TENANT, otherMember));
        assertForeignKey("telegram_link_code_member_fk", sql -> sql.update("insert into telegram_link_code (tenant_id, "
                + "code_hash, member_id, purpose, expires_at) values (?, '\\x07'::bytea, ?, 'PAPER', "
                + "now() + interval '1 day')", DEMO_TENANT, otherMember));
        assertForeignKey("announcement_created_by_fk", sql -> sql.update("insert into announcement (tenant_id, text, "
                + "channels, created_by) values (?, 'Hi', array['EMAIL'], ?)", DEMO_TENANT, otherMember));
        String insert = "insert into delivery (tenant_id, kind, invoice_id, invoice_version, member_id, channel, "
                + "status) values (?, 'INVOICE', ?, 1, ?, 'TELEGRAM', 'QUEUED')";
        assertForeignKey("delivery_member_fk", sql -> sql.update(insert, DEMO_TENANT, demoInvoice, otherMember));
        assertForeignKey("delivery_invoice_fk", sql -> sql.update(insert, DEMO_TENANT, otherInvoice, demoMember));
        assertForeignKey("delivery_announcement_fk", sql -> sql.update("insert into delivery (tenant_id, kind, "
                + "announcement_id, member_id, channel, status) values (?, 'ANNOUNCEMENT', ?, ?, 'TELEGRAM', 'QUEUED')",
                DEMO_TENANT, otherAnnouncement, demoMember));
        UUID otherPayment = payment(OTHER_TENANT, otherPlot);
        assertForeignKey("delivery_payment_fk", sql -> sql.update("insert into delivery (tenant_id, kind, payment_id, "
                + "member_id, channel, status) values (?, 'PAYMENT', ?, ?, 'TELEGRAM', 'QUEUED')",
                DEMO_TENANT, otherPayment, demoMember));
        assertThat(demoAnnouncement).isNotNull();
    }

    // ---- the same chat in two cooperatives

    @Test
    void theSameChatMayBeLinkedInTwoCooperatives() {
        // the fixtures linked chat 1 in both cooperatives already (the same blind index bytes)
        assertThat(ownerJdbc().queryForObject("select count(distinct tenant_id) from telegram_link "
                + "where chat_id_bidx = '\\x0201'::bytea", Integer.class)).isEqualTo(2);
    }

    @Test
    void theSameChatCannotBeLinkedTwiceInOneCooperative() {
        UUID second = newMember(DEMO_TENANT, "iso-second@notifications.local");
        String insert = "insert into telegram_link (tenant_id, member_id, chat_id_enc, chat_id_bidx) "
                + "values (?, ?, '\\x05'::bytea, '\\x0201'::bytea)";

        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(insert, DEMO_TENANT, second)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("telegram_link_chat_active_uq");

        // once the first link has ended the chat is free again
        inTenant(DEMO_TENANT, sql -> sql.update(
                "update telegram_link set ended_at = now(), end_reason = 'MOVED' where member_id = ?", demoMember));
        assertThat(run(sql -> sql.update(insert, DEMO_TENANT, second))).isEqualTo(1);
    }

    // ---- helpers

    private int run(Function<JdbcTemplate, Integer> work) {
        Integer result = inTenant(DEMO_TENANT, work);
        return result;
    }

    private int count(UUID tenant, String table, String where) {
        return inTenant(tenant, sql -> sql.queryForObject("select count(*) from " + table + " " + where,
                Integer.class));
    }

    private void assertRowSecurity(Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("violates row-level security policy");
    }

    private void assertForeignKey(String constraint, Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"))
                .hasMessageContaining(constraint);
    }
}
