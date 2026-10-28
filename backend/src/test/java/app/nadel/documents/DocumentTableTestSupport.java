package app.nadel.documents;

import app.nadel.IntegrationTest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fixtures for the document table tests: a throw-away plot per cooperative and rows of invoice, certificate and
 * document_counter, created as the container superuser (which bypasses RLS) and removed after the test. The document
 * tables refuse DELETE even to the owner, so clean-up runs as {@code nadel_owner} with the maintenance setting, the
 * path the demo data uses. The container has no {@code nadel_owner} role, so this class creates it as the ledger
 * tests do.
 */
abstract class DocumentTableTestSupport extends IntegrationTest {

    private final List<UUID> plots = new ArrayList<>();

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

    @AfterEach
    void removeDocumentFixtures() {
        String plotArray = "{" + String.join(",", plots.stream().map(UUID::toString).toList()) + "}";
        asNadelOwner(true, sql -> {
            sql.update("delete from invoice where plot_id = any(?::uuid[])", plotArray);
            sql.update("delete from certificate where plot_id = any(?::uuid[])", plotArray);
            sql.update("delete from document_counter where year = 2031");
        });
        for (UUID plot : plots) {
            ownerJdbc().update("delete from plot where id = ?", plot);
        }
        plots.clear();
    }

    /** A plot of the cooperative, removed after the test. */
    UUID plot(UUID tenant) {
        UUID plot = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 100)",
                plot, tenant, "D-" + plot.toString().substring(0, 8));
        plots.add(plot);
        return plot;
    }

    /** An invoice row (version 1 of January 2031) as the superuser. */
    UUID invoice(UUID tenant, UUID plot, int version, UUID issuedBy) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into invoice (id, tenant_id, plot_id, month, number, version, snapshot, "
                + "content_hash, due, as_of, issued_by) values (?, ?, ?, date '2031-01-01', ?, ?, '{\"schema\":1}'::jsonb, "
                + "'\\x01'::bytea, 12.50, now(), ?)", id, tenant, plot, "2031-01/" + id.toString().substring(0, 4),
                version, issuedBy);
        return id;
    }

    /** A certificate row of 2031 as the superuser. */
    UUID certificate(UUID tenant, UUID plot, int seq, UUID issuedBy) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into certificate (id, tenant_id, year, seq, number, plot_id, as_of, snapshot, "
                + "total_debt, issued_by) values (?, ?, 2031, ?, ?, ?, date '2031-01-15', '{\"schema\":1}'::jsonb, "
                + "100.00, ?)", id, tenant, seq, "T-2031-" + seq + "-" + id.toString().substring(0, 4), plot,
                issuedBy);
        return id;
    }

    /** Runs the work as nadel_owner, with or without the maintenance setting, in one transaction. */
    static void asNadelOwner(boolean maintenance, Consumer<JdbcTemplate> work) {
        JdbcTemplate sql = ownerJdbc();
        new TransactionTemplate(new DataSourceTransactionManager(sql.getDataSource())).executeWithoutResult(status -> {
            // the tables exist only after the context ran the migrations, so the grant is made here
            sql.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO nadel_owner");
            sql.execute("SET LOCAL ROLE nadel_owner");
            if (maintenance) {
                sql.execute("SET LOCAL app.ledger_maintenance = 'on'");
            }
            work.accept(sql);
        });
    }

    static String sqlState(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
