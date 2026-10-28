package app.nadel.documents.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.RoleRef;
import app.nadel.ledger.InvoiceLedger;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.MonthStatus;
import app.nadel.members.MemberRef;
import app.nadel.members.RoleHolders;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.tenancy.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds the demo cooperative's documents (feature 016, technical considerations 2.9) once the application has
 * started: the invoices of the demo closed month and one debt certificate for plot 9. {@code R__demo_data.sql} cannot
 * write them, because a snapshot seals names with the Java cipher (ADR 0016); its demo ledger block wipes
 * {@code invoice}, {@code certificate} and {@code document_counter} of the demo cooperative whenever it re-runs, and
 * this runner builds them again on the next start.
 * <p>
 * <b>When.</b> Registered only when the {@code db/demo} location is active and the demo ledger placeholder
 * {@code demoledger} is {@code on}: the {@code dev} profile of {@code make up}. Production has neither; the integration
 * tests turn the placeholder off, so the runner never starts there ({@code DemoDocumentsTests} calls {@link #seed}).
 * <p>
 * <b>What</b>, under {@code TenantContext.runAs} for the demo cooperative, through the module's own services:
 * <ul>
 *   <li>the closed month is the demo cooperative's first billing month (the demo ledger books nothing before it), and
 *       only while it is {@code CLOSED};</li>
 *   <li>its invoices with {@link InvoiceBuilder#forMonth} (not a preview, with {@code includeNothingDue}, so every
 *       invoiceable plot gets one, a plot with nothing due shows 0,00 without a QR code: the demo member's plots 7 and
 *       15 have nothing due in that month, and their member account needs an invoice to show), issued by the system
 *       (no member, subject {@value DocumentsFacadeService#SYSTEM}), as the board's build would; skipped when the
 *       month already has an invoice;</li>
 *   <li>the certificate of plot 9 with {@link Certificates#issue} as of the last day of the closed month (a fixed day,
 *       so it shows «Станом на» a day before the issue), in the name of the demo chairman (the first active holder of
 *       the chairman's base role), so it prints «Голова» and a name; skipped when plot 9 has any certificate, or when
 *       the cooperative has no chairman.</li>
 * </ul>
 * Idempotent: a second run finds both and writes nothing. A refusal (requisites missing, a running build) or any other
 * failure is logged with its problem code or class only and never stops the application. The log has counts only.
 */
@Component
@ConditionalOnExpression("'${spring.flyway.locations:}'.contains('db/demo') "
        + "and '${spring.flyway.placeholders.demoledger:off}' == 'on'")
class DemoDocuments implements ApplicationRunner {

    /** The demo cooperative of {@code R__demo_data.sql}. */
    static final UUID DEMO_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** The plot whose certificate the demo shows. */
    static final String CERTIFICATE_PLOT = "9";

    /** The chairman's role as {@link Certificates#issuerRole} reads it: the base code only. */
    private static final RoleRef CHAIRMAN = new RoleRef(new UUID(0L, 0L), "", "CHAIRMAN", false);

    private static final Logger log = LoggerFactory.getLogger(DemoDocuments.class);

    /**
     * What one run did: the closed month it found (null when there is none), the invoice versions the build wrote
     * ({@code built + newVersions}; 0 when skipped) and whether it issued the certificate.
     */
    record Seeded(@Nullable YearMonth month, int invoices, boolean certificate) {
    }

    /** What the run reads first, in one read-only transaction. */
    private record State(@Nullable YearMonth closedMonth, boolean monthHasInvoices, @Nullable UUID plot,
            boolean plotHasCertificate, @Nullable UUID chairman) {
    }

    private final TransactionTemplate readOnly;
    private final LedgerReadModel ledger;
    private final PlotCatalog plots;
    private final RoleHolders roleHolders;
    private final InvoiceRepository invoices;
    private final CertificateRepository certificates;
    private final InvoiceBuilder builder;
    private final Certificates certificateService;
    private final Clock clock;

    DemoDocuments(PlatformTransactionManager transactionManager, LedgerReadModel ledger, PlotCatalog plots,
            RoleHolders roleHolders, InvoiceRepository invoices, CertificateRepository certificates,
            InvoiceBuilder builder, Certificates certificateService, Clock clock) {
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.ledger = ledger;
        this.plots = plots;
        this.roleHolders = roleHolders;
        this.invoices = invoices;
        this.certificates = certificates;
        this.builder = builder;
        this.certificateService = certificateService;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Seeded seeded = seed();
            log.info("Demo documents: month {}, {} invoice versions built, certificate issued: {}",
                    seeded.month(), seeded.invoices(), seeded.certificate());
        } catch (ApiProblemException e) {
            log.warn("Demo documents not built: {}", e.code());
        } catch (RuntimeException e) {
            log.warn("Demo documents not built: {}", e.getClass().getName());
        }
    }

    /** Builds what is missing of the demo documents (see the class comment). */
    Seeded seed() {
        return TenantContext.runAs(DEMO_TENANT, this::seedDemo);
    }

    private Seeded seedDemo() {
        State state = Objects.requireNonNull(readOnly.execute(status -> state()));
        YearMonth month = state.closedMonth();
        if (month == null) {
            return new Seeded(null, 0, false);
        }
        AccessProfile system = new AccessProfile(DEMO_TENANT, null, DocumentsFacadeService.SYSTEM, "", Set.of(),
                List.of(), false);
        int built = 0;
        if (!state.monthHasInvoices()) {
            InvoiceBuilder.MonthBuild build = builder.forMonth(month, true, false, system);
            built = build.built() + build.newVersions();
        }
        boolean issued = false;
        if (state.plot() != null && !state.plotHasCertificate() && state.chairman() != null) {
            AccessProfile chairman = new AccessProfile(DEMO_TENANT, state.chairman(), DocumentsFacadeService.SYSTEM,
                    "", Set.of(), List.of(CHAIRMAN), false);
            LocalDate asOf = month.atEndOfMonth();
            LocalDate today = DocumentFormat.dayOf(clock.instant());
            certificateService.issue(state.plot(), asOf.isAfter(today) ? today : asOf, chairman);
            issued = true;
        }
        return new Seeded(month, built, issued);
    }

    private State state() {
        UUID plotId = plots.findActiveByNumbers(List.of(CERTIFICATE_PLOT)).stream().findFirst().map(PlotFacts::id)
                .orElse(null);
        YearMonth closed = plotId == null ? null
                : ledger.firstBillingMonth().filter(month -> closed(month, plotId)).orElse(null);
        boolean monthHasInvoices = closed != null && !invoices.plotsInvoicedIn(closed.atDay(1)).isEmpty();
        boolean plotHasCertificate = plotId != null && certificates.existsByPlotId(plotId);
        UUID chairman = roleHolders.activeHolders("CHAIRMAN").stream().findFirst().map(MemberRef::id).orElse(null);
        return new State(closed, monthHasInvoices, plotId, plotHasCertificate, chairman);
    }

    /** Whether the month is closed: its status as the invoice ledger of plot 9 reads it. */
    private boolean closed(YearMonth month, UUID plotId) {
        InvoiceLedger read = ledger.invoiceLedgers(List.of(plotId), month, clock.instant()).get(plotId);
        return read != null && read.status() == MonthStatus.CLOSED;
    }
}
