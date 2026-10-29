package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The document tables are registers: invoice is append-only (privileges, trigger, maintenance bypass), a certificate
 * only gets its annulment, once, document_counter only moves forward by one, and the CHECKs hold.
 */
class DocumentsAppendOnlyTests extends DocumentTableTestSupport {

    private static final String ANNUL = "update certificate set annulled_at = now(), annulled_by = ?, "
            + "annul_reason = ? where id = ?";

    private UUID plot;
    private UUID member;
    private UUID invoice;
    private UUID certificate;

    @BeforeEach
    void createFixtures() {
        plot = plot(DEMO_TENANT);
        member = newMember(DEMO_TENANT, "docs-append@documents.local");
        invoice = invoice(DEMO_TENANT, plot, 1, member);
        certificate = certificate(DEMO_TENANT, plot, 1, member);
    }

    // ---- the application role: privileges

    @Test
    void theApplicationRoleCannotUpdateDeleteOrTruncateInvoices() {
        assertDenied("invoice", sql -> sql.update("update invoice set due = 0"));
        assertDenied("invoice", sql -> sql.update("delete from invoice"));
        assertDenied("invoice", sql -> {
            sql.execute("truncate invoice");
            return null;
        });
        assertRowsUnchanged();
    }

    @Test
    void theApplicationRoleCannotDeleteOrTruncateCertificatesAndCounters() {
        for (String table : new String[] {"certificate", "document_counter"}) {
            assertDenied(table, sql -> sql.update("delete from " + table));
            assertDenied(table, sql -> {
                sql.execute("truncate " + table);
                return null;
            });
        }
        assertRowsUnchanged();
    }

    @Test
    void theApplicationRoleMayInsertAndReadAndAnnulOnce() {
        inTenant(DEMO_TENANT, sql -> {
            sql.update("insert into document_counter (tenant_id, kind, year, last_value) values (?, 'CERTIFICATE', "
                    + "2031, 0)", DEMO_TENANT);
            sql.update("update document_counter set last_value = last_value + 1");
            sql.update("insert into invoice (tenant_id, plot_id, month, number, version, snapshot, content_hash, due, "
                    + "as_of) values (?, ?, date '2031-01-01', 'N-2', 2, '{}'::jsonb, '\\x02'::bytea, 0, now())",
                    DEMO_TENANT, plot);
            return sql.update(ANNUL, member, "Issued by mistake", certificate);
        });

        Integer invoices = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from invoice where plot_id = ?", Integer.class, plot));
        assertThat(invoices).isEqualTo(2);
        Long last = inTenant(DEMO_TENANT, sql -> sql.queryForObject("select last_value from document_counter",
                Long.class));
        assertThat(last).isEqualTo(1L);
        assertThat(ownerJdbc().queryForObject("select annul_reason from certificate where id = ?", String.class,
                certificate)).isEqualTo("Issued by mistake");
    }

    // ---- the owner: trigger

    @Test
    void theOwnerIsRefusedByTheInvoiceTrigger() {
        assertTrigger("invoice", "UPDATE", () -> ownerJdbc().update("update invoice set due = 0"));
        assertTrigger("invoice", "DELETE", () -> ownerJdbc().update("delete from invoice"));
        assertTrigger("invoice", "TRUNCATE", () -> ownerJdbc().execute("truncate invoice"));
        assertRowsUnchanged();
    }

    @Test
    void theOwnerCannotDeleteOrTruncateCertificates() {
        assertTrigger("certificate", "DELETE", () -> ownerJdbc().update("delete from certificate"));
        assertTrigger("certificate", "TRUNCATE", () -> ownerJdbc().execute("truncate certificate"));
        assertRowsUnchanged();
    }

    @Test
    void nadelOwnerWithoutTheSettingIsRefused() {
        assertThatThrownBy(() -> asNadelOwner(false, sql -> sql.update("delete from invoice where id = ?", invoice)))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> asNadelOwner(false, sql -> sql.update("update invoice set due = 1 where id = ?",
                invoice))).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> asNadelOwner(false, sql -> sql.update("delete from certificate where id = ?",
                certificate))).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> asNadelOwner(false, sql -> sql.update("update certificate set total_debt = 1 "
                + "where id = ?", certificate))).hasStackTraceContaining("certificate_guard");
        assertRowsUnchanged();
    }

    @Test
    void nadelOwnerWithTheSettingMayChangeAndDeleteRows() {
        asNadelOwner(true, sql -> {
            assertThat(sql.update("update invoice set due = 1.00 where id = ?", invoice)).isEqualTo(1);
            assertThat(sql.update("update certificate set total_debt = 1.00 where id = ?", certificate)).isEqualTo(1);
            assertThat(sql.update("delete from invoice where id = ?", invoice)).isEqualTo(1);
            assertThat(sql.update("delete from certificate where id = ?", certificate)).isEqualTo(1);
        });

        assertThat(ownerJdbc().queryForObject("select count(*) from invoice where id = ?", Integer.class, invoice))
                .isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from certificate where id = ?", Integer.class,
                certificate)).isZero();
    }

    @Test
    void theApplicationRoleIsRefusedEvenWithTheSetting() {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> {
            sql.execute("set local app.ledger_maintenance = 'on'");
            return sql.update("delete from invoice where id = ?", invoice);
        })).isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied for table invoice");
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> {
            sql.execute("set local app.ledger_maintenance = 'on'");
            return sql.update("delete from certificate where id = ?", certificate);
        })).isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied for table certificate");
        assertRowsUnchanged();
    }

    // ---- invoice: CHECKs and uniqueness

    @Test
    void anInvoiceMonthIsTheFirstDayAndTheVersionAndDueAreBounded() {
        assertCheck("invoice_month_ck", () -> insertInvoice("2031-01-15", 5, "1.00"));
        assertCheck("invoice_version_ck", () -> insertInvoice("2031-02-01", 0, "1.00"));
        assertCheck("invoice_due_ck", () -> insertInvoice("2031-02-01", 5, "-0.01"));
        insertInvoice("2031-02-01", 5, "0.00");
    }

    @Test
    void anInvoiceVersionExistsOncePerPlotAndMonth() {
        assertThatThrownBy(() -> invoice(DEMO_TENANT, plot, 1, member))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("invoice_version_uq");
        invoice(DEMO_TENANT, plot, 2, member);
    }

    // ---- certificate: CHECKs, uniqueness and the guard

    @Test
    void aCertificateNumberAndSequenceExistOncePerCooperative() {
        assertThatThrownBy(() -> ownerJdbc().update("insert into certificate (tenant_id, year, seq, number, plot_id, "
                + "as_of, snapshot, total_debt) values (?, 2031, 1, 'other-number', ?, date '2031-01-15', '{}', 0)",
                DEMO_TENANT, plot)).satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("certificate_seq_uq");
        String number = ownerJdbc().queryForObject("select number from certificate where id = ?", String.class,
                certificate);
        assertThatThrownBy(() -> ownerJdbc().update("insert into certificate (tenant_id, year, seq, number, plot_id, "
                + "as_of, snapshot, total_debt) values (?, 2031, 2, ?, ?, date '2031-01-15', '{}', 0)",
                DEMO_TENANT, number, plot)).satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"))
                .hasMessageContaining("certificate_number_uq");
    }

    @Test
    void theAnnulColumnsAreAllNullOrAllSetWithAReasonOfOneToFiveHundredCharacters() {
        assertCheck("certificate_annul_ck", () -> ownerJdbc().update(
                "update certificate set annulled_at = now() where id = ?", certificate));
        assertCheck("certificate_annul_ck", () -> ownerJdbc().update(ANNUL, null, "Reason", certificate));
        assertCheck("certificate_annul_ck", () -> ownerJdbc().update(ANNUL, member, "   ", certificate));
        assertCheck("certificate_annul_ck", () -> ownerJdbc().update(ANNUL, member, "x".repeat(501), certificate));
        assertThat(ownerJdbc().update(ANNUL, member, "x".repeat(500), certificate)).isEqualTo(1);
    }

    @Test
    void aCertificateIsAnnulledOnceAndOtherColumnsNeverChange() {
        assertCheck("certificate_guard", () -> ownerJdbc().update(
                "update certificate set total_debt = 5.00 where id = ?", certificate));
        assertCheck("certificate_guard", () -> ownerJdbc().update(
                "update certificate set number = 'changed' where id = ?", certificate));
        assertCheck("certificate_guard", () -> ownerJdbc().update(
                "update certificate set snapshot = '{\"schema\":2}' where id = ?", certificate));
        // a column change together with the annulment is refused too
        assertCheck("certificate_guard", () -> ownerJdbc().update(
                "update certificate set total_debt = 5.00, annulled_at = now(), annulled_by = ?, "
                        + "annul_reason = 'Wrong' where id = ?", member, certificate));
        assertThat(ownerJdbc().queryForObject("select annulled_at is null from certificate where id = ?",
                Boolean.class, certificate)).isTrue();

        assertThat(ownerJdbc().update(ANNUL, member, "First", certificate)).isEqualTo(1);

        assertCheck("certificate_guard", () -> ownerJdbc().update(ANNUL, member, "Second", certificate));
        assertCheck("certificate_guard", () -> ownerJdbc().update(
                "update certificate set annulled_at = null, annulled_by = null, annul_reason = null where id = ?",
                certificate));
        assertThat(ownerJdbc().queryForObject("select annul_reason from certificate where id = ?", String.class,
                certificate)).isEqualTo("First");
    }

    // ---- document_counter

    @Test
    void theCounterMovesForwardByOneOnly() {
        ownerJdbc().update("insert into document_counter (tenant_id, kind, year, last_value) values (?, "
                + "'CERTIFICATE', 2031, 5)", DEMO_TENANT);
        String update = "update document_counter set last_value = ? where tenant_id = ? and year = 2031";

        for (int wrong : new int[] {7, 5, 4, 0}) {
            assertCheck("document_counter_step", () -> ownerJdbc().update(update, wrong, DEMO_TENANT));
        }
        assertThat(ownerJdbc().update(update, 6, DEMO_TENANT)).isEqualTo(1);
        assertCheck("document_counter_step", () -> ownerJdbc().update(
                "update document_counter set year = 2032 where tenant_id = ?", DEMO_TENANT));
        assertCheck("document_counter_last_value_ck", () -> ownerJdbc().update(
                "insert into document_counter (tenant_id, kind, year, last_value) values (?, 'CERTIFICATE', 2032, -1)",
                DEMO_TENANT));
        assertCheck("document_counter_kind_ck", () -> ownerJdbc().update(
                "insert into document_counter (tenant_id, kind, year, last_value) values (?, 'INVOICE', 2031, 0)",
                DEMO_TENANT));
    }

    // ---- helpers

    private int insertInvoice(String month, int version, String due) {
        return ownerJdbc().update("insert into invoice (tenant_id, plot_id, month, number, version, snapshot, "
                + "content_hash, due, as_of) values (?, ?, ?::date, 'N-9', ?, '{}'::jsonb, '\\x03'::bytea, ?::numeric, "
                + "now())", DEMO_TENANT, plot, month, version, due);
    }

    private void assertRowsUnchanged() {
        assertThat(ownerJdbc().queryForObject("select count(*) from invoice where id = ? and due = 12.50",
                Integer.class, invoice)).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select count(*) from certificate where id = ? and total_debt = 100.00 "
                + "and annulled_at is null", Integer.class, certificate)).isEqualTo(1);
    }

    private void assertTrigger(String table, String operation, Runnable statement) {
        assertThatThrownBy(statement::run)
                .as("%s on %s", operation, table)
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ledger table " + table + " is append-only: " + operation);
    }

    private void assertDenied(String table, Function<JdbcTemplate, Object> statement) {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, statement))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied for table " + table);
    }

    private void assertCheck(String constraint, Supplier<?> statement) {
        assertThatThrownBy(statement::get)
                .as(constraint)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"))
                .hasMessageContaining(constraint);
    }
}
