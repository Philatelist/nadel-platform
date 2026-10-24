package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.MonthStatus;
import app.nadel.plots.PlotCatalog;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Sheet;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import nadel.contract.model.ChargeTypeCode;
import nadel.contract.model.ReportCellReason;
import nadel.contract.model.ReportPeriodBasis;
import org.junit.jupiter.api.Test;

/**
 * The bundles of the report files: both languages have the same keys, every text a definition or the writer asks for
 * exists, and the English bundle holds no Ukrainian letter. (That the texts equal the web catalogs is checked on the
 * web side.)
 */
class ReportTextsTests {

    private static final ReportTexts UK = ReportTexts.of(ReportLanguage.UK);
    private static final ReportTexts EN = ReportTexts.of(ReportLanguage.EN);

    @Test
    void bothLanguagesHaveTheSameKeys() {
        assertThat(UK.keys()).isNotEmpty().containsExactlyInAnyOrderElementsOf(EN.keys());
        assertThat(UK.language()).isEqualTo(ReportLanguage.UK);
    }

    @Test
    void everyTextTheReportsAskForExistsInBothLanguages() {
        for (String key : neededKeys()) {
            assertThat(UK.keys()).as("uk").contains(key);
            assertThat(EN.keys()).as("en").contains(key);
        }
    }

    @Test
    void theEnglishBundleHasNoUkrainianText() {
        EN.keys().forEach(key -> assertThat(EN.text(key)).as(key).doesNotContainPattern("\\p{IsCyrillic}"));
        assertThat(UK.text("reports.charges-by-month.sheets.byServicePeriod")).isEqualTo("За період послуг");
    }

    @Test
    void placeholdersAreFilledAndAMissingKeyIsAnError() {
        assertThat(UK.text("reports.totals.all", Map.of("unused", "x"))).isEqualTo("Усього");
        assertThatThrownBy(() -> UK.text("reports.nothing")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reports.nothing");
        assertThat(ReportTexts.day(LocalDate.of(2026, 1, 31))).isEqualTo("31.01.2026");
        assertThat(ReportTexts.month(YearMonth.of(2026, 1))).isEqualTo("01.2026");
    }

    /** The keys of the header block, the lines, the reasons, the filters and of every report definition. */
    private static List<String> neededKeys() {
        List<String> keys = new ArrayList<>(List.of("reports.file.filters", "reports.file.generatedAt",
                "reports.file.generatedBy", "reports.filters.all", "reports.filters.chargeTypes",
                "reports.filters.street", "reports.rows.SUBTOTAL", "reports.totals.all"));
        for (ReportPeriodBasis basis : ReportPeriodBasis.values()) {
            keys.add("reports.filters.period." + basis.getValue());
        }
        for (ReportCellReason reason : ReportCellReason.values()) {
            keys.add("reports.reasons." + reason.getValue());
        }
        for (ChargeTypeCode code : ChargeTypeCode.values()) {
            keys.add("chargeTypes.defaultNames." + code.getValue());
        }
        for (MonthStatus status : MonthStatus.values()) {
            keys.add("billing.status." + status.name());
        }
        ReportDefinition chargesByMonth = new ChargesByMonthReport(mock(LedgerReadModel.class),
                mock(PlotCatalog.class));
        ReportFilters filters = ReportFilters.parse(chargesByMonth.filters(), RawFilters.NONE,
                new ReportContext(LocalDate.of(2026, 10, 24), 20_001, List.of()));
        ReportResult result = chargesByMonth.load(filters, new ReportContext(LocalDate.of(2026, 10, 24), 20_001,
                List.of()));
        String code = chargesByMonth.code().getValue();
        keys.add("reports." + code + ".title");
        result.notes().forEach(note -> keys.add("reports.notes." + note.code().getValue()));
        for (Sheet sheet : result.sheets()) {
            keys.add("reports." + code + ".sheets." + sheet.key());
            for (Column column : sheet.columns()) {
                keys.add("reports." + code + ".columns." + column.key());
            }
            sheet.totals().forEach(total -> keys.add("reports.totals." + total.key()));
        }
        return keys;
    }
}
