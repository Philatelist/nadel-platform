package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The guards of the delivery tables (V29): the immutable columns of delivery, a Telegram link that ends once, the
 * append-only announcement, the CHECKs and the unique keys that make a repeated enqueue a no-op.
 */
class NotificationsGuardsTests extends NotificationsTestSupport {

    private UUID plot;
    private UUID member;
    private UUID invoice;
    private UUID announcement;
    private UUID delivery;
    private UUID link;

    @BeforeEach
    void createFixtures() {
        plot = plot(DEMO_TENANT);
        member = newMember(DEMO_TENANT, "guards@notifications.local");
        invoice = invoice(DEMO_TENANT, plot, 1);
        announcement = announcement(DEMO_TENANT);
        delivery = delivery(DEMO_TENANT, invoice, member, "EMAIL");
        link = link(DEMO_TENANT, member, 1);
    }

    // ---- delivery: only the sending state changes

    @Test
    void theSendingStateOfADeliveryCanChange() {
        inTenant(DEMO_TENANT, sql -> sql.update("update delivery set status = 'FAILED', failure_code = 'SMTP_REJECTED', "
                + "attempts = 3, resends = 1, next_attempt_at = now(), sent_at = now() where id = ?", delivery));
        inTenant(DEMO_TENANT, sql -> sql.update("update delivery set status = 'QUEUED', failure_code = null where id = ?",
                delivery));

        assertThat(ownerJdbc().queryForObject("select attempts from delivery where id = ?", Integer.class, delivery))
                .isEqualTo(3);
    }

    @Test
    void theApplicationRoleCannotChangeTheOtherColumnsOfADelivery() {
        UUID otherMember = newMember(DEMO_TENANT, "guards-other@notifications.local");
        UUID secondInvoice = invoice(DEMO_TENANT, plot, 2);
        for (String change : new String[] {
                "member_id = '" + otherMember + "'", "channel = 'TELEGRAM'", "kind = 'REVERSAL'",
                "invoice_id = '" + secondInvoice + "'", "invoice_version = 2", "billing_month = date '2031-04-01'",
                "note = 'VIBER_UNSUPPORTED'", "created_at = now() - interval '1 day'",
                "id = gen_random_uuid()"}) {
            assertCheckViolation("delivery_guard", sql -> sql.update("update delivery set " + change + " where id = ?",
                    delivery));
        }
        assertThat(ownerJdbc().queryForObject("select channel || member_id from delivery where id = ?", String.class,
                delivery)).isEqualTo("EMAIL" + member);
    }

    @Test
    void aDeliveryIsNeverDeletedByTheApplicationRole() {
        assertDenied(sql -> sql.update("delete from delivery where id = ?", delivery));
    }

    @Test
    void theChecksOfADeliveryHold() {
        String update = "update delivery set ";
        assertThat(violation(update + "status = 'FAILED' where id = ?", delivery)).as("failed without a code").isEqualTo("23514");
        assertThat(violation(update + "failure_code = 'OTHER', status = 'FAILED' where id = ?", delivery)).as("unknown code")
                .isEqualTo("23514");
        assertThat(violation(update + "status = 'TO_PRINT' where id = ?", delivery)).as("print an e-mail").isEqualTo("23514");
        assertThat(violation(update + "status = 'SENT2' where id = ?", delivery)).as("unknown status").isEqualTo("23514");
        assertThat(violation(update + "attempts = -1 where id = ?", delivery)).as("negative attempts").isEqualTo("23514");
        String insert = "insert into delivery (tenant_id, kind, %s member_id, channel, status) values (?, %s ?, "
                + "'EMAIL', 'QUEUED')";
        assertThat(violationOf(String.format(insert, "", "'INVOICE',"), DEMO_TENANT, member))
                .as("an invoice without a reference").isEqualTo("23514");
        assertThat(violationOf(String.format(insert, "announcement_id,", "'INVOICE', '" + announcement + "',"),
                DEMO_TENANT, member)).as("an invoice with an announcement").isEqualTo("23514");
        assertThat(violationOf(String.format(insert, "invoice_id, invoice_version,",
                "'ANNOUNCEMENT', '" + invoice + "', 1,"), DEMO_TENANT, member))
                .as("an announcement with an invoice").isEqualTo("23514");
        assertThat(violationOf("insert into delivery (tenant_id, kind, announcement_id, member_id, channel, note, "
                + "status) values (?, 'ANNOUNCEMENT', '" + announcement + "', ?, 'PAPER', 'WHATSAPP', 'TO_PRINT')",
                DEMO_TENANT, member)).as("unknown note").isEqualTo("23514");
        inTenant(DEMO_TENANT, sql -> sql.update("insert into delivery (tenant_id, kind, announcement_id, member_id, "
                + "channel, note, status) values (?, 'ANNOUNCEMENT', ?, ?, 'PAPER', 'VIBER_UNSUPPORTED', 'TO_PRINT')",
                DEMO_TENANT, announcement, member));
    }

    // ---- telegram_link: ended once

    @Test
    void aLinkEndsOnceAndNothingElseChanges() {
        assertCheckViolation("telegram_link_guard", sql -> sql.update(
                "update telegram_link set chat_id_bidx = '\\x09'::bytea where id = ?", link));
        assertCheckViolation("telegram_link_guard", sql -> sql.update(
                "update telegram_link set member_id = ? where id = ?", UUID.randomUUID(), link));

        assertThat(run(sql -> sql.update(
                "update telegram_link set ended_at = now(), end_reason = 'BLOCKED' where id = ?", link))).isEqualTo(1);

        assertCheckViolation("telegram_link_guard", sql -> sql.update(
                "update telegram_link set end_reason = 'UNLINKED' where id = ?", link));
        assertCheckViolation("telegram_link_guard", sql -> sql.update(
                "update telegram_link set ended_at = null, end_reason = null where id = ?", link));
        assertThat(ownerJdbc().queryForObject("select end_reason from telegram_link where id = ?", String.class, link))
                .isEqualTo("BLOCKED");
        assertDenied(sql -> sql.update("delete from telegram_link where id = ?", link));
    }

    @Test
    void anEndNeedsAReasonAndAReasonAnEnd() {
        assertThat(violation("update telegram_link set ended_at = now() where id = ?", link)).isEqualTo("23514");
        assertThat(violation("update telegram_link set end_reason = 'UNLINKED' where id = ?", link))
                .isEqualTo("23514");
        assertThat(violation("update telegram_link set ended_at = now(), end_reason = 'GONE' where id = ?", link))
                .isEqualTo("23514");
    }

    @Test
    void aLinkCodeMayBeDeletedByTheApplicationRole() {
        linkCode(DEMO_TENANT, member, 5);

        assertThat(run(sql -> sql.update("delete from telegram_link_code"))).isEqualTo(1);
    }

    // ---- announcement: append-only

    @Test
    void anAnnouncementCannotBeUpdatedOrDeleted() {
        assertDenied(sql -> sql.update("update announcement set text = 'Changed' where id = ?", announcement));
        assertDenied(sql -> sql.update("delete from announcement where id = ?", announcement));
        assertDenied(sql -> {
            sql.execute("truncate announcement");
            return null;
        });
        // the table owner is stopped by the trigger as well, outside the maintenance path
        assertThatThrownBy(() -> inOwnerRole(sql -> sql.update("update announcement set text = 'Changed'")))
                .hasStackTraceContaining("append-only");
        assertThat(ownerJdbc().queryForObject("select text from announcement where id = ?", String.class, announcement))
                .isEqualTo("Meeting on Sunday");
    }

    @Test
    void theChecksOfAnAnnouncementHold() {
        String insert = "insert into announcement (tenant_id, text, channels) values (?, ?, %s)";
        assertThat(violationOf(String.format(insert, "array['EMAIL']"), DEMO_TENANT, "")).as("empty").isEqualTo("23514");
        assertThat(violationOf(String.format(insert, "array['EMAIL']"), DEMO_TENANT, "x".repeat(2001)))
                .as("too long").isEqualTo("23514");
        assertThat(violationOf(String.format(insert, "array['SMS']"), DEMO_TENANT, "Hi")).as("channel")
                .isEqualTo("23514");
        assertThat(violationOf(String.format(insert, "array[]::text[]"), DEMO_TENANT, "Hi")).as("no channel")
                .isEqualTo("23514");
        assertThat(run(sql -> sql.update(String.format(insert, "array['PAPER']"), DEMO_TENANT,
                "x".repeat(2000)))).isEqualTo(1);
    }

    // ---- unique keys: a repeated insert with ON CONFLICT DO NOTHING adds no row

    @Test
    void aRepeatedInvoiceDeliveryAddsNothing() {
        int inserted = inTenant(DEMO_TENANT, sql -> sql.update("insert into delivery (tenant_id, kind, invoice_id, "
                + "invoice_version, member_id, channel, status) values (?, 'INVOICE', ?, 2, ?, 'EMAIL', 'QUEUED') "
                + "on conflict do nothing", DEMO_TENANT, invoice, member));

        assertThat(inserted).isZero();
        assertThat(violationOf("insert into delivery (tenant_id, kind, invoice_id, invoice_version, member_id, channel, "
                + "status) values (?, 'INVOICE', '" + invoice + "', 2, ?, 'EMAIL', 'QUEUED')", DEMO_TENANT, member))
                .isEqualTo("23505");
        // another channel is another delivery
        assertThat(run(sql -> sql.update("insert into delivery (tenant_id, kind, invoice_id, "
                + "invoice_version, member_id, channel, status) values (?, 'INVOICE', ?, 1, ?, 'TELEGRAM', 'QUEUED') "
                + "on conflict do nothing", DEMO_TENANT, invoice, member))).isEqualTo(1);
    }

    @Test
    void aRepeatedPaymentDeliveryAddsNothingAndAReversalIsAnotherDelivery() {
        UUID payment = payment(DEMO_TENANT, plot);
        String insert = "insert into delivery (tenant_id, kind, payment_id, member_id, channel, status) "
                + "values (?, ?, ?, ?, 'TELEGRAM', 'QUEUED') on conflict do nothing";

        assertThat(run(sql -> sql.update(insert, DEMO_TENANT, "PAYMENT", payment, member)))
                .isEqualTo(1);
        assertThat(run(sql -> sql.update(insert, DEMO_TENANT, "PAYMENT", payment, member)))
                .isZero();
        assertThat(run(sql -> sql.update(insert, DEMO_TENANT, "REVERSAL", payment, member)))
                .isEqualTo(1);
        assertThat(run(sql -> sql.update(insert, DEMO_TENANT, "REVERSAL", payment, member)))
                .isZero();
    }

    @Test
    void aRepeatedAnnouncementDeliveryAddsNothing() {
        String insert = "insert into delivery (tenant_id, kind, announcement_id, member_id, channel, status) "
                + "values (?, 'ANNOUNCEMENT', ?, ?, 'EMAIL', 'QUEUED') on conflict do nothing";

        assertThat(run(sql -> sql.update(insert, DEMO_TENANT, announcement, member))).isEqualTo(1);
        assertThat(run(sql -> sql.update(insert, DEMO_TENANT, announcement, member))).isZero();
    }

    @Test
    void aRepeatedLinkOrLinkCodeAddsNothing() {
        String insertLink = "insert into telegram_link (tenant_id, member_id, chat_id_enc, chat_id_bidx) "
                + "values (?, ?, '\\x01'::bytea, ?) on conflict do nothing";
        // the same member (one active link per member) and the same chat (one active link per chat)
        assertThat(run(sql -> sql.update(insertLink, DEMO_TENANT, member, new byte[] {9}))).isZero();
        UUID second = newMember(DEMO_TENANT, "guards-second@notifications.local");
        assertThat(run(sql -> sql.update(insertLink, DEMO_TENANT, second, new byte[] {2, 1})))
                .isZero();
        assertThat(run(sql -> sql.update(insertLink, DEMO_TENANT, second, new byte[] {9})))
                .isEqualTo(1);

        linkCode(DEMO_TENANT, member, 7);
        assertThat(run(sql -> sql.update("insert into telegram_link_code (tenant_id, code_hash, "
                + "member_id, purpose, expires_at) values (?, ?, ?, 'PAPER', now() + interval '1 day') "
                + "on conflict do nothing", DEMO_TENANT, new byte[] {3, 7}, second))).isZero();
    }

    // ---- helpers

    private int run(Function<JdbcTemplate, Integer> work) {
        Integer result = inTenant(DEMO_TENANT, work);
        return result;
    }

    private String violation(String sql, UUID id) {
        return violationOf(sql, id);
    }

    private String violationOf(String statement, Object... args) {
        try {
            inTenant(DEMO_TENANT, sql -> sql.update(statement, args));
        } catch (DataAccessException e) {
            return sqlState(e);
        }
        return null;
    }

    private void assertCheckViolation(String constraint, Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"))
                .hasMessageContaining(constraint);
    }

    private void assertDenied(Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
    }

    private void inOwnerRole(java.util.function.Consumer<JdbcTemplate> work) {
        JdbcTemplate sql = ownerJdbc();
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(sql.getDataSource()))
                .executeWithoutResult(status -> {
                    sql.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO nadel_owner");
                    sql.execute("SET LOCAL ROLE nadel_owner");
                    work.accept(sql);
                });
    }
}
