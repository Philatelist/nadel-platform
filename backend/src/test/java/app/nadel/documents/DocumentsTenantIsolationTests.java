package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The document tables (invoice, certificate, document_counter): cooperatives are isolated (RLS with USING and WITH
 * CHECK, nothing visible without a cooperative), links never cross cooperatives (composite foreign keys) and every
 * cooperative has its own certificate counter.
 */
class DocumentsTenantIsolationTests extends DocumentTableTestSupport {

    private static final List<String> TABLES = List.of("invoice", "certificate", "document_counter");
    private static final String INVOICE_INSERT = "insert into invoice (tenant_id, plot_id, month, number, version, "
            + "snapshot, content_hash, due, as_of, issued_by) values (?, ?, date '2031-02-01', 'X-1', 1, '{}'::jsonb, "
            + "'\\x01'::bytea, 0, now(), ?)";
    private static final String CERTIFICATE_INSERT = "insert into certificate (tenant_id, year, seq, number, plot_id, "
            + "as_of, snapshot, total_debt, issued_by, annulled_at, annulled_by, annul_reason) values (?, 2031, 90, "
            + "'X-90', ?, date '2031-01-15', '{}'::jsonb, 0, ?, null, ?, null)";
    private static final String COUNTER_INSERT = "insert into document_counter (tenant_id, kind, year, last_value) "
            + "values (?, 'CERTIFICATE', 2031, 0)";

    private UUID demoPlot;
    private UUID otherPlot;
    private UUID demoInvoice;
    private UUID otherInvoice;
    private UUID demoCertificate;
    private UUID otherCertificate;

    @BeforeEach
    void createFixtures() {
        demoPlot = plot(DEMO_TENANT);
        otherPlot = plot(OTHER_TENANT);
        demoInvoice = invoice(DEMO_TENANT, demoPlot, 1, null);
        otherInvoice = invoice(OTHER_TENANT, otherPlot, 1, null);
        demoCertificate = certificate(DEMO_TENANT, demoPlot, 1, null);
        otherCertificate = certificate(OTHER_TENANT, otherPlot, 1, null);
        counter(DEMO_TENANT, 7);
        counter(OTHER_TENANT, 3);
    }

    // ---- row level security

    @Test
    void everyTableHasRowLevelSecurityWithAPolicyAndATenantIndex() {
        for (String table : TABLES) {
            assertThat(ownerJdbc().queryForObject(
                    "select relrowsecurity from pg_class where oid = ('public.' || ?)::regclass", Boolean.class, table))
                    .as("RLS on %s", table).isTrue();
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
            boolean demo = tenant.equals(DEMO_TENANT);
            assertThat(count(tenant, "invoice", "where id in ('" + demoInvoice + "', '" + otherInvoice + "')"))
                    .isEqualTo(1);
            assertThat(count(tenant, "invoice", "where id = '" + (demo ? demoInvoice : otherInvoice) + "'"))
                    .isEqualTo(1);
            assertThat(count(tenant, "certificate", "where id in ('" + demoCertificate + "', '" + otherCertificate
                    + "')")).isEqualTo(1);
            assertThat(count(tenant, "certificate", "where id = '" + (demo ? demoCertificate : otherCertificate)
                    + "'")).isEqualTo(1);
            assertThat(count(tenant, "document_counter", "where year = 2031")).isEqualTo(1);
            for (String table : TABLES) {
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
    void cannotInsertRowsForAnotherCooperative() {
        assertRowSecurity(sql -> sql.update(INVOICE_INSERT, OTHER_TENANT, otherPlot, null));
        assertRowSecurity(sql -> sql.update(CERTIFICATE_INSERT, OTHER_TENANT, otherPlot, null, null));
        assertRowSecurity(sql -> sql.update(COUNTER_INSERT, OTHER_TENANT));
        assertThat(ownerJdbc().queryForObject("select count(*) from invoice where tenant_id = ?", Integer.class,
                OTHER_TENANT)).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select count(*) from certificate where tenant_id = ?", Integer.class,
                OTHER_TENANT)).isEqualTo(1);
    }

    @Test
    void cannotUpdateTheCounterOfAnotherCooperative() {
        Integer updated = inTenant(DEMO_TENANT, sql -> sql.update(
                "update document_counter set last_value = last_value + 1 where tenant_id = ?", OTHER_TENANT));

        assertThat(updated).isZero();
        assertThat(lastValue(OTHER_TENANT)).isEqualTo(3L);
        assertThat(step(DEMO_TENANT)).isEqualTo(1);
        assertThat(lastValue(DEMO_TENANT)).isEqualTo(8L);
        assertThat(lastValue(OTHER_TENANT)).isEqualTo(3L);
    }

    @Test
    void cannotHandTheCounterToAnotherCooperative() {
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(
                "update document_counter set tenant_id = ? where tenant_id = ?", OTHER_TENANT, DEMO_TENANT)))
                .isInstanceOf(DataAccessException.class);
        assertThat(lastValue(DEMO_TENANT)).isEqualTo(7L);
    }

    @Test
    void cannotAnnulACertificateOfAnotherCooperative() {
        UUID member = newMember(DEMO_TENANT, "docs-iso@documents.local");

        Integer updated = inTenant(DEMO_TENANT, sql -> sql.update(
                "update certificate set annulled_at = now(), annulled_by = ?, annul_reason = 'Wrong' where id = ?",
                member, otherCertificate));

        assertThat(updated).isZero();
        assertThat(ownerJdbc().queryForObject("select annulled_at is null from certificate where id = ?",
                Boolean.class, otherCertificate)).isTrue();
    }

    // ---- composite foreign keys

    @Test
    void anInvoiceCannotLinkRowsOfAnotherCooperative() {
        UUID otherMember = newMember(OTHER_TENANT, "docs-iso-other@documents.local");

        assertForeignKey("invoice_plot_fk", sql -> sql.update(INVOICE_INSERT, DEMO_TENANT, otherPlot, null));
        assertForeignKey("invoice_issued_by_fk", sql -> sql.update(INVOICE_INSERT, DEMO_TENANT, demoPlot,
                otherMember));
    }

    @Test
    void aCertificateCannotLinkRowsOfAnotherCooperative() {
        UUID otherMember = newMember(OTHER_TENANT, "docs-iso-other2@documents.local");

        assertForeignKey("certificate_plot_fk", sql -> sql.update(CERTIFICATE_INSERT, DEMO_TENANT, otherPlot, null,
                null));
        assertForeignKey("certificate_issued_by_fk", sql -> sql.update(CERTIFICATE_INSERT, DEMO_TENANT, demoPlot,
                otherMember, null));
        assertForeignKey("certificate_annulled_by_fk", sql -> sql.update(
                "insert into certificate (tenant_id, year, seq, number, plot_id, as_of, snapshot, total_debt, "
                        + "annulled_at, annulled_by, annul_reason) values (?, 2031, 91, 'X-91', ?, "
                        + "date '2031-01-15', '{}'::jsonb, 0, now(), ?, 'Wrong')",
                DEMO_TENANT, demoPlot, otherMember));
    }

    // ---- uniqueness per cooperative

    @Test
    void theSameNumbersMayExistInTwoCooperatives() {
        // the certificate guard refuses to change a number, so this runs on the owner's maintenance path
        asNadelOwner(true, sql -> sql.update("update certificate set number = 'Д-2031-5', seq = 5 where id in (?, ?)",
                demoCertificate, otherCertificate));

        assertThat(count(DEMO_TENANT, "certificate", "where number = 'Д-2031-5'")).isEqualTo(1);
        assertThat(count(OTHER_TENANT, "certificate", "where number = 'Д-2031-5'")).isEqualTo(1);
    }

    @Test
    void everyCooperativeHasItsOwnIndependentCounter() {
        assertThat(step(DEMO_TENANT)).isEqualTo(1);
        assertThat(step(DEMO_TENANT)).isEqualTo(1);
        assertThat(step(OTHER_TENANT)).isEqualTo(1);

        assertThat(lastValue(DEMO_TENANT)).isEqualTo(9L);
        assertThat(lastValue(OTHER_TENANT)).isEqualTo(4L);
        Long demoVisible = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select last_value from document_counter", Long.class));
        Long otherVisible = inTenant(OTHER_TENANT, sql -> sql.queryForObject(
                "select last_value from document_counter", Long.class));
        assertThat(demoVisible).isEqualTo(9L);
        assertThat(otherVisible).isEqualTo(4L);
    }

    // ---- helpers

    private void counter(UUID tenant, long last) {
        ownerJdbc().update("insert into document_counter (tenant_id, kind, year, last_value) "
                + "values (?, 'CERTIFICATE', 2031, ?)", tenant, last);
    }

    private int step(UUID tenant) {
        return inTenant(tenant, sql -> sql.update("update document_counter set last_value = last_value + 1"));
    }

    private Long lastValue(UUID tenant) {
        return ownerJdbc().queryForObject("select last_value from document_counter where tenant_id = ? and year = 2031",
                Long.class, tenant);
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
