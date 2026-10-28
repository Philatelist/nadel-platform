package app.nadel.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.nadel.ledger.LedgerReadModel;
import app.nadel.members.RoleHolders;
import app.nadel.plots.PlotCatalog;
import app.nadel.problem.ApiProblemException;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The start of the demo documents runner (feature 016, technical considerations 2.9): without the demo plot or a
 * closed month it builds nothing and says so; a failure of the build never stops the application from starting and
 * logs only the problem code or the exception class, never its message (which may hold personal data).
 */
@ExtendWith(OutputCaptureExtension.class)
class DemoDocumentsRunTests {

    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final LedgerReadModel ledger = mock(LedgerReadModel.class);
    private final PlotCatalog plots = mock(PlotCatalog.class);
    private final RoleHolders roleHolders = mock(RoleHolders.class);
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final CertificateRepository certificates = mock(CertificateRepository.class);
    private final InvoiceBuilder builder = mock(InvoiceBuilder.class);
    private final Certificates certificateService = mock(Certificates.class);

    private final DemoDocuments demo = new DemoDocuments(transactionManager, ledger, plots, roleHolders, invoices,
            certificates, builder, certificateService, Clock.systemUTC());

    @BeforeEach
    void aTransactionStarts() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void withoutTheDemoPlotNothingIsBuilt(CapturedOutput output) {
        when(plots.findActiveByNumbers(List.of(DemoDocuments.CERTIFICATE_PLOT))).thenReturn(List.of());
        when(roleHolders.activeHolders("CHAIRMAN")).thenReturn(List.of());

        demo.run(null);

        assertThat(demo.seed()).isEqualTo(new DemoDocuments.Seeded(null, 0, false));
        assertThat(output.getAll())
                .contains("Demo documents: month null, 0 invoice versions built, certificate issued: false");
        verifyNoInteractions(ledger, builder, certificateService);
    }

    @Test
    void aProblemOfTheBuildIsLoggedByItsCodeAndTheStartGoesOn(CapturedOutput output) {
        when(plots.findActiveByNumbers(List.of(DemoDocuments.CERTIFICATE_PLOT))).thenThrow(
                new ApiProblemException(HttpStatus.CONFLICT, "MONTH_NOT_CLOSED", "Шевченко Тарас Григорович"));

        assertThatCode(() -> demo.run(null)).doesNotThrowAnyException();

        assertThat(output.getAll()).contains("Demo documents not built: MONTH_NOT_CLOSED")
                .doesNotContain("Шевченко");
    }

    @Test
    void anyOtherFailureIsLoggedByItsClassOnly(CapturedOutput output) {
        when(plots.findActiveByNumbers(List.of(DemoDocuments.CERTIFICATE_PLOT)))
                .thenThrow(new IllegalStateException("owner Шевченко Тарас Григорович"));

        assertThatCode(() -> demo.run(null)).doesNotThrowAnyException();

        assertThat(output.getAll()).contains("Demo documents not built: java.lang.IllegalStateException")
                .doesNotContain("Шевченко");
    }
}
