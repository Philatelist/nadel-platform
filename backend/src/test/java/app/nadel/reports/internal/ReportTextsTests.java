package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import static org.mockito.Mockito.when;

import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.MonthStatus;
import app.nadel.members.MemberDirectory;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotOwners;
import app.nadel.plots.PlotStatuses;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.settings.CooperativeSettings;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import nadel.contract.model.ChargeTypeCode;
import nadel.contract.model.PlotStatus;
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

    @Test
    void everyFilterOfEveryReportNamesItselfInBothLanguages() {
        for (Run run : runs()) {
            for (ReportTexts texts : List.of(UK, EN)) {
                for (ReportFilterDefinition filter : run.definition().filters()) {
                    assertThat(filter.describe(run.filters(), texts, id -> "type")).as(filter.key()).contains(": ");
                }
            }
        }
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
        for (PlotStatus status : PlotStatus.values()) {
            keys.add("reports.filters.options.statuses." + status.getValue());
        }
        for (Run run : runs()) {
            ReportResult result = run.definition().load(run.filters(), CONTEXT);
            String code = run.definition().code().getValue();
            keys.add("reports." + code + ".title");
            result.notes().forEach(note -> keys.add("reports.notes." + note.code().getValue()));
            for (Sheet sheet : result.sheets()) {
                keys.add("reports." + code + ".sheets." + sheet.key());
                for (Column column : sheet.columns()) {
                    // a column per charge type is headed by the charge type's name, not by a key
                    if (column.chargeTypeId() == null) {
                        keys.add("reports." + code + ".columns." + column.key());
                    }
                }
                sheet.totals().forEach(total -> keys.add("reports.totals." + total.key()));
            }
        }
        return keys;
    }

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private static final ChargeTypeFacts MEMBERSHIP = new ChargeTypeFacts(UUID.randomUUID(), "MEMBERSHIP",
            "Membership fee", false, true, false, 1);
    private static final ChargeTypeFacts WATER = new ChargeTypeFacts(UUID.randomUUID(), "WATER", "Water", false, true,
            false, 2);
    private static final ReportContext CONTEXT = new ReportContext(TODAY, 20_001, List.of(MEMBERSHIP, WATER));

    /** One definition with filters that make it say every note it can. */
    private record Run(ReportDefinition definition, ReportFilters filters) {
    }

    /**
     * Every definition on facades that return nothing, with filters that make it say all its notes: plot balances for
     * a past day with one of two charge types and every status chosen.
     */
    private static List<Run> runs() {
        ReportDefinition chargesByMonth = new ChargesByMonthReport(mock(LedgerReadModel.class),
                mock(PlotCatalog.class));
        ReportDefinition plotBalances = new PlotBalancesReport(mock(PlotCatalog.class), mock(PlotOwners.class),
                mock(PlotStatuses.class), mock(LedgerReadModel.class));
        CooperativeSettings settings = mock(CooperativeSettings.class);
        when(settings.redAmount()).thenReturn(Money.parse("1000.00"));
        when(settings.redDays()).thenReturn(90);
        ReportDefinition debtors = new DebtorsReport(mock(PlotCatalog.class), mock(PlotOwners.class),
                mock(MemberDirectory.class), mock(LedgerReadModel.class), settings);
        RawFilters pastWithOneType = new RawFilters(null, null, null, null, TODAY.minusDays(30), null,
                List.of(MEMBERSHIP.id()), "Лісова", List.of(PlotStatus.values()), null, null, null, null, null,
                null);
        return List.of(new Run(chargesByMonth, ReportFilters.parse(chargesByMonth.filters(), RawFilters.NONE, CONTEXT)),
                new Run(plotBalances, ReportFilters.parse(plotBalances.filters(), pastWithOneType, CONTEXT)),
                new Run(debtors, ReportFilters.parse(debtors.filters(), pastWithOneType, CONTEXT)));
    }
}
