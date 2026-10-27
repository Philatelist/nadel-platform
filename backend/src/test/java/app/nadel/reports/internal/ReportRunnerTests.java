package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.CurrentUser;
import app.nadel.identity.Permission;
import app.nadel.problem.ApiProblemException;
import app.nadel.reports.internal.ReportAuditPublisher.Output;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportPreview;
import nadel.contract.model.ReportSheet;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The runner with a fake report of 250 rows: the preview cuts at 200 rows and keeps the totals of all, the file holds
 * every row and the same totals; the audit entry is published last, once per output, and never when the run is
 * refused (filters, cap) or writing the file fails.
 */
class ReportRunnerTests {

    private static final ReportCode CODE = ReportCode.CHARGES_BY_MONTH;

    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final ChargeTypeCatalog chargeTypes = mock(ChargeTypeCatalog.class);
    private final TenantDirectory tenants = mock(TenantDirectory.class);
    private final ReportAuditPublisher audit = mock(ReportAuditPublisher.class);
    private final XlsxReportWriter writer = spy(new XlsxReportWriter());
    private final ReportLimits limits = new ReportLimits(ReportLimits.DEFAULT_ROW_CAP);
    private final AccessProfile caller = ReportCatalogTests.caller(Permission.REPORTS_VIEW);

    private ReportRunner runner;

    @BeforeEach
    void setUp() {
        when(currentUser.profile()).thenReturn(caller);
        when(chargeTypes.all()).thenReturn(List.of());
        when(tenants.current()).thenReturn(Optional.of(new TenantSummary(UUID.randomUUID(), "СТ «Тест»", null)));
        ReportCatalog catalog = new ReportCatalog(List.of(new FakeReport(CODE, Set.of(Permission.REPORTS_VIEW), 250)));
        Clock clock = Clock.fixed(Instant.parse("2026-10-23T22:30:00Z"), ZoneId.of("UTC"));
        runner = new ReportRunner(catalog, currentUser, chargeTypes, tenants, writer, audit, limits, clock);
    }

    @Test
    void thePreviewShowsTheFirst200RowsAndTheTotalsOfAll() {
        ReportPreview preview = runner.preview(CODE, RawFilters.NONE);

        ReportSheet sheet = preview.getSheets().getFirst();
        assertThat(sheet.getRows()).hasSize(200);
        assertThat(sheet.getRowCount()).isEqualTo(250);
        assertThat(sheet.getTruncated()).isTrue();
        assertThat(sheet.getRows().getLast().getCells().get(1).getValue()).isEqualTo("2.00");
        // 0.01 + 0.02 + ... + 2.50
        assertThat(sheet.getTotals().getFirst().getCells().get(1).getValue()).isEqualTo("313.75");
        assertThat(sheet.getTotals().getFirst().getCells().getFirst().getValue()).isNull();
        // Kyiv is already on 24 October: the default month is September
        assertThat(preview.getFilters()).extracting(value -> value.getValue()).containsExactly("2026-09", "2026-09");
        assertThat(preview.getGeneratedAt().toString()).isEqualTo("2026-10-24T01:30+03:00");
        verify(audit).publish(caller, CODE, Output.PREVIEW, preview.getFilters(), 250, false);
    }

    @Test
    void theFileHoldsEveryRowAndTheSameTotals() throws Exception {
        ReportFile file = runner.file(CODE, RawFilters.NONE, ReportLanguage.EN);

        assertThat(file.fileName()).isEqualTo("charges-by-month_2026-09-01_2026-09-30.xlsx");
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file.content()))) {
            List<Row> rows = workbook.getFirstSheet().read();
            Row last = rows.getLast();
            assertThat(last.getCellText(0)).isEqualTo("Total");
            assertThat(last.getCellAsNumber(1).orElseThrow()).isEqualByComparingTo("313.75");
            // header block (5), header row, 250 rows, totals; the empty line before the header is not read
            assertThat(rows).hasSize(5 + 1 + 250 + 1);
            assertThat(rows.get(0).getCellText(0)).isEqualTo("СТ «Тест»");
            assertThat(rows.get(4).getCellText(0)).isEqualTo("Generated by: Петренко Іван");
            assertThat(rows.get(3).getCellText(0)).isEqualTo("Generated at: 24.10.2026 01:30");
        }
        verify(audit).publish(eq(caller), eq(CODE), eq(Output.XLSX), anyList(), eq(250), eq(false));
    }

    @Test
    void aFailureWhileWritingTheFileLeavesNoAuditEntry() {
        doThrow(new UncheckedIOException(new IOException("disk full"))).when(writer)
                .write(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> runner.file(CODE, RawFilters.NONE, ReportLanguage.UK))
                .isInstanceOf(UncheckedIOException.class);
        verify(audit, never()).publish(any(), any(), any(), anyList(), anyInt(), anyBoolean());
    }

    @Test
    void aReportOverTheCapIsRefusedWithoutAnAuditEntry() {
        limits.rowCap(249);

        for (Runnable run : List.<Runnable>of(() -> runner.preview(CODE, RawFilters.NONE),
                () -> runner.file(CODE, RawFilters.NONE, ReportLanguage.UK))) {
            assertThatThrownBy(run::run).isInstanceOfSatisfying(ApiProblemException.class, e -> {
                assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.code()).isEqualTo("REPORT_TOO_LARGE");
            });
        }
        verify(audit, never()).publish(any(), any(), any(), anyList(), anyInt(), anyBoolean());
        limits.rowCap(250);
        assertThat(runner.preview(CODE, RawFilters.NONE).getSheets().getFirst().getRowCount()).isEqualTo(250);
        assertThatThrownBy(() -> limits.rowCap(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filtersThatDoNotFitLeaveNoAuditEntry() {
        RawFilters reversed = new RawFilters("2025-03", "2025-01", null, null, null, null, null, null, null, null,
                null, null, null, null, null);

        assertThatThrownBy(() -> runner.preview(CODE, reversed)).isInstanceOf(ApiProblemException.class);
        verify(audit, never()).publish(any(), any(), any(), anyList(), anyInt(), anyBoolean());
    }
}
