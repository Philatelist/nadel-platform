package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.nadel.ledger.ChargedRowKind;
import app.nadel.ledger.ChargedSource;
import app.nadel.ledger.CommonConsumerRunLineOutcome;
import app.nadel.ledger.CorrectionCause;
import app.nadel.ledger.CorrectionKind;
import app.nadel.ledger.CorrectionSource;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.LossesRunNodeOutcome;
import app.nadel.ledger.MonthStatus;
import app.nadel.ledger.RunStatus;
import app.nadel.members.MemberDirectory;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterReports;
import app.nadel.meters.MeterTreeReport;
import app.nadel.meters.MeterValueKind;
import app.nadel.money.Money;
import app.nadel.money.Rate;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.plots.PlotOwners;
import app.nadel.plots.PlotStatuses;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.settings.CommonConsumerSplit;
import app.nadel.settings.CooperativeSettings;
import app.nadel.settings.LossesMode;
import app.nadel.tariffs.Basis;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import app.nadel.tariffs.ExpectedAmounts;
import app.nadel.tariffs.Frequency;
import app.nadel.tariffs.VersionShare;
import app.nadel.tariffs.YearExpectation;
import java.math.BigDecimal;
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
        // the corrections' code columns and their filter
        keys.add("reports.filters.params.year");
        keys.add("reports.filters.params.correctionKinds");
        for (CorrectionKind kind : CorrectionKind.values()) {
            keys.add("reports.filters.options.correctionKinds." + kind.name());
        }
        for (CorrectionSource source : CorrectionSource.values()) {
            keys.add("reports.corrections.sources." + source.name());
        }
        for (CorrectionCause cause : CorrectionCause.values()) {
            keys.add("recalculation.causeKind." + cause.name());
        }
        // the electricity report's code columns and per-source totals (a run on empty facades has no totals)
        String electricity = "reports.electricity-tree.";
        for (MeterKind kind : MeterKind.values()) {
            keys.add("reports.filters.options.meterKinds." + kind.name());
        }
        for (MeterValueKind kind : MeterValueKind.values()) {
            keys.add(electricity + "valueKinds." + kind.name());
        }
        for (ChargedSource source : ChargedSource.values()) {
            keys.add(electricity + "sources." + source.name());
            keys.add("reports.totals." + source.name());
        }
        for (ChargedRowKind kind : ChargedRowKind.values()) {
            keys.add(electricity + "rowKinds." + kind.name());
        }
        keys.add(electricity + "rowKinds." + ElectricityTreeReport.RUN);
        for (RunStatus status : RunStatus.values()) {
            keys.add(electricity + "runStatuses." + status.name());
        }
        for (LossesRunNodeOutcome outcome : LossesRunNodeOutcome.values()) {
            keys.add(electricity + "outcomes." + outcome.name());
        }
        for (CommonConsumerRunLineOutcome outcome : CommonConsumerRunLineOutcome.values()) {
            keys.add(electricity + "outcomes." + outcome.name());
        }
        for (CommonConsumerSplit split : CommonConsumerSplit.values()) {
            keys.add(electricity + "splits." + split.name());
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
     * a past day with one of two charge types and every status chosen; land tax with one plot charged nothing of its
     * expected 210.00; the electricity tree once in each losses mode.
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
        ReportDefinition landTax = landTaxWithAMismatch();
        ReportDefinition corrections = new CorrectionsReport(mock(LedgerReadModel.class), mock(PlotCatalog.class),
                mock(MemberDirectory.class));
        ReportDefinition fixedLosses = electricityTree(LossesMode.FIXED_PERCENT);
        ReportDefinition proportionalLosses = electricityTree(LossesMode.PROPORTIONAL);
        return List.of(new Run(fixedLosses, ReportFilters.parse(fixedLosses.filters(), RawFilters.NONE, CONTEXT)),
                new Run(proportionalLosses, ReportFilters.parse(proportionalLosses.filters(), RawFilters.NONE,
                        CONTEXT)),
                new Run(chargesByMonth, ReportFilters.parse(chargesByMonth.filters(), RawFilters.NONE, CONTEXT)),
                new Run(plotBalances, ReportFilters.parse(plotBalances.filters(), pastWithOneType, CONTEXT)),
                new Run(debtors, ReportFilters.parse(debtors.filters(), pastWithOneType, CONTEXT)),
                new Run(landTax, ReportFilters.parse(landTax.filters(), RawFilters.NONE, CONTEXT)),
                new Run(corrections, ReportFilters.parse(corrections.filters(), RawFilters.NONE, CONTEXT)));
    }

    private static ReportDefinition electricityTree(LossesMode mode) {
        MeterReports meters = mock(MeterReports.class);
        when(meters.tree(any(), any())).thenAnswer(call -> new MeterTreeReport(call.getArgument(0),
                call.getArgument(1), List.of()));
        CooperativeSettings settings = mock(CooperativeSettings.class);
        when(settings.lossesMode()).thenReturn(mode);
        when(settings.lossesPercent()).thenReturn(new BigDecimal("5.00"));
        return new ElectricityTreeReport(meters, mock(LedgerReadModel.class), mock(PlotCatalog.class), settings);
    }

    private static ReportDefinition landTaxWithAMismatch() {
        UUID plot = UUID.randomUUID();
        PlotCatalog plots = mock(PlotCatalog.class);
        when(plots.findActive(null)).thenReturn(List.of(new PlotFacts(plot, "1", null, false, false,
                new BigDecimal("600.00"))));
        ExpectedAmounts expected = mock(ExpectedAmounts.class);
        Money year = Money.parse("210.00");
        when(expected.yearByVersion(anyString(), anyInt(), any())).thenReturn(Map.of(plot, new YearExpectation(plot,
                new BigDecimal("600.00"), new BigDecimal("6.0000"), List.of(new VersionShare(UUID.randomUUID(),
                        UUID.randomUUID(), Basis.PER_AREA_SOTKA, Frequency.YEARLY, Rate.parse("35"),
                        TODAY.withDayOfYear(1), TODAY.withMonth(12).withDayOfMonth(31), 365, year)), year)));
        return new LandTaxReport(plots, expected, mock(LedgerReadModel.class));
    }
}
