package app.nadel.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import app.nadel.DemoLedgerData;
import app.nadel.IntegrationTest;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.members.RoleHolders;
import app.nadel.plots.PlotCatalog;
import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
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
 * The demo documents (feature 016, technical considerations 2.9): with the demo ledger loaded, {@link DemoDocuments}
 * builds the invoices of the demo closed month (by the system, no member; with nothing due included, so the demo
 * member's plots 7 and 15 get one at 0,00) and one certificate of plot 9 as of the
 * month's last day in the demo chairman's name; a second run writes nothing, so the rows stay the same. The runner is
 * not registered in the tests (the demo ledger placeholder is off), so it is built here from the module's beans.
 */
class DemoDocumentsTests extends IntegrationTest {

    /** The demo chairman of {@code R__demo_data.sql}. */
    private static final UUID DEMO_CHAIRMAN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private final JdbcTemplate owner = ownerJdbc();

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private LedgerReadModel ledger;

    @Autowired
    private PlotCatalog plots;

    @Autowired
    private RoleHolders roleHolders;

    @Autowired
    private InvoiceRepository invoices;

    @Autowired
    private CertificateRepository certificates;

    @Autowired
    private InvoiceBuilder builder;

    @Autowired
    private Certificates certificateService;

    @Autowired
    private Clock clock;

    private DemoDocuments demo() {
        return new DemoDocuments(transactionManager, ledger, plots, roleHolders, invoices, certificates, builder,
                certificateService, clock);
    }

    @BeforeEach
    void loadTheDemoLedger() {
        DemoLedgerData.restore(owner);
    }

    @AfterEach
    void removeTheDemoDocumentsAndLedger() {
        await().atMost(Duration.ofSeconds(10)).until(() -> owner.queryForObject(
                "select count(*) from event_publication where completion_date is null", Integer.class) == 0);
        DemoLedgerData.asLedgerOwner(owner, sql -> {
            sql.update("delete from audit_entry where tenant_id = ? and action in "
                    + "('INVOICES_ISSUED', 'CERTIFICATE_ISSUED')", DEMO_TENANT);
            sql.update("delete from invoice where tenant_id = ?", DEMO_TENANT);
            sql.update("delete from certificate where tenant_id = ?", DEMO_TENANT);
            sql.update("delete from document_counter where tenant_id = ?", DEMO_TENANT);
        });
        DemoLedgerData.clear(owner);
    }

    @Test
    void theRunnerIsNotRegisteredInTheTests() {
        assertThat(context.getBeansOfType(DemoDocuments.class)).isEmpty();
    }

    @Test
    void theClosedMonthGetsItsInvoicesAndPlotNineACertificateOnce() {
        YearMonth closed = YearMonth.from(owner.queryForObject("select month from billing_month "
                + "where tenant_id = ? and status = 'CLOSED'", LocalDate.class, DEMO_TENANT));

        DemoDocuments.Seeded first = demo().seed();

        assertThat(first.month()).isEqualTo(closed);
        assertThat(first.invoices()).isPositive();
        assertThat(first.certificate()).isTrue();
        List<Map<String, Object>> invoiceRows = invoiceRows();
        assertThat(invoiceRows).hasSize(first.invoices());
        assertThat(invoiceRows).allSatisfy(row -> {
            assertThat(row.get("month")).isEqualTo(Date.valueOf(closed.atDay(1)));
            assertThat(row.get("version")).isEqualTo(1);
            assertThat(row.get("issued_by")).isNull();
        });
        assertThat(owner.queryForList("select p.number, i.due from invoice i join plot p on p.id = i.plot_id "
                + "where i.tenant_id = ? and p.number in ('7', '15') order by p.number", DEMO_TENANT))
                .extracting(row -> row.get("number"), row -> row.get("due").toString())
                .containsExactly(tuple("15", "0.00"), tuple("7", "0.00"));
        List<Map<String, Object>> certificateRows = certificateRows();
        assertThat(certificateRows).hasSize(1);
        Map<String, Object> certificate = certificateRows.getFirst();
        assertThat(certificate.get("plot_number")).isEqualTo("9");
        assertThat(certificate.get("as_of")).isEqualTo(Date.valueOf(closed.atEndOfMonth()));
        assertThat(certificate.get("issued_by")).hasToString(DEMO_CHAIRMAN.toString());
        assertThat(certificate.get("seq")).isEqualTo(1);

        DemoDocuments.Seeded second = demo().seed();

        assertThat(second).isEqualTo(new DemoDocuments.Seeded(closed, 0, false));
        assertThat(invoiceRows()).isEqualTo(invoiceRows);
        assertThat(certificateRows()).isEqualTo(certificateRows);
        assertThat(owner.queryForObject("select last_value from document_counter where tenant_id = ?", Long.class,
                DEMO_TENANT)).isEqualTo(1L);
    }

    @Test
    void withoutAClosedMonthNothingIsBuilt() {
        DemoLedgerData.clear(owner);

        DemoDocuments.Seeded seeded = demo().seed();

        assertThat(seeded).isEqualTo(new DemoDocuments.Seeded(null, 0, false));
        assertThat(invoiceRows()).isEmpty();
        assertThat(certificateRows()).isEmpty();
    }

    private List<Map<String, Object>> invoiceRows() {
        return owner.queryForList("select id, plot_id, month, version, issued_by from invoice where tenant_id = ? "
                + "order by id", DEMO_TENANT);
    }

    private List<Map<String, Object>> certificateRows() {
        return owner.queryForList("select c.id, c.number, c.seq, c.as_of, c.issued_by, p.number as plot_number "
                + "from certificate c join plot p on p.id = c.plot_id where c.tenant_id = ? order by c.id",
                DEMO_TENANT);
    }
}
