package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.ledger.CorrectionKind;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import nadel.contract.model.ReportCorrectionKind;
import nadel.contract.model.ReportFilter;
import nadel.contract.model.ReportFilterValue;
import nadel.contract.model.ReportPeriodBasis;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Reading the filters of a report: defaults, the field errors of {@code REPORT_FILTER_INVALID} ({@code REQUIRED},
 * {@code INVALID}, {@code NOT_FOUND}) naming the parameter, the values echoed and audited, the period of the file name
 * and the header block's description.
 */
class ReportFiltersTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private static final ChargeTypeFacts MEMBERSHIP = new ChargeTypeFacts(UUID.randomUUID(), "MEMBERSHIP",
            "Membership fee", false, true, false, 0);
    private static final ChargeTypeFacts CUSTOM = new ChargeTypeFacts(UUID.randomUUID(), null, "Охорона", true,
            true, true, 9);
    private static final ReportContext CONTEXT = new ReportContext(TODAY, 20_001, List.of(MEMBERSHIP, CUSTOM));
    private static final List<ReportFilterDefinition> CHARGES_BY_MONTH = List.of(
            MonthRangeFilter.previousMonth(ReportPeriodBasis.BILLING_MONTHS), new ChargeTypesFilter(),
            new StreetFilter());

    @Test
    void leftOutFiltersTakeTheirDefaults() {
        ReportFilters filters = ReportFilters.parse(CHARGES_BY_MONTH, RawFilters.NONE, CONTEXT);

        assertThat(filters.fromMonth()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(filters.toMonth()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(filters.chargeTypeIds()).isEmpty();
        assertThat(filters.street()).isNull();
        assertThat(values(filters)).containsExactly("fromMonth=2026-09", "toMonth=2026-09");
        assertThat(filters.period()).isEqualTo(new ReportFilters.Period(LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30)));
    }

    @Test
    void givenValuesAreReadAndEchoedInTheOrderOfTheFilters() {
        RawFilters raw = raw("2025-01", null, List.of(CUSTOM.id(), MEMBERSHIP.id(), CUSTOM.id()), "  Лісова ");

        ReportFilters filters = ReportFilters.parse(CHARGES_BY_MONTH, raw, CONTEXT);

        // the missing end of the range takes its default
        assertThat(filters.period()).isEqualTo(new ReportFilters.Period(LocalDate.of(2025, 1, 1),
                LocalDate.of(2026, 9, 30)));
        assertThat(filters.chargeTypeIds()).containsExactly(CUSTOM.id(), MEMBERSHIP.id());
        assertThat(filters.street()).isEqualTo("Лісова");
        assertThat(values(filters)).containsExactly("fromMonth=2025-01", "toMonth=2026-09",
                "chargeTypeIds=" + CUSTOM.id(), "chargeTypeIds=" + MEMBERSHIP.id(), "street=Лісова");
        // a blank street and an empty list mean all
        ReportFilters all = ReportFilters.parse(CHARGES_BY_MONTH, raw(null, null, List.of(), "   "), CONTEXT);
        assertThat(all.street()).isNull();
        assertThat(all.chargeTypeIds()).isEmpty();
    }

    @Test
    void aRangeThatEndsBeforeItStartsIsInvalidOnToMonth() {
        assertThat(problems(raw("2025-03", "2025-01", null, null), CHARGES_BY_MONTH))
                .containsExactly(new FieldProblem("toMonth", "INVALID"));
    }

    @Test
    void anUnknownChargeTypeIsNotFoundAtItsIndexAndEveryErrorIsReported() {
        UUID unknown = UUID.randomUUID();

        assertThat(problems(raw("2025-03", "2025-01", List.of(MEMBERSHIP.id(), unknown, unknown), null),
                CHARGES_BY_MONTH)).containsExactly(new FieldProblem("toMonth", "INVALID"),
                new FieldProblem("chargeTypeIds[1]", "NOT_FOUND"), new FieldProblem("chargeTypeIds[2]", "NOT_FOUND"));
    }

    @Test
    void aRequiredFilterWithoutADefaultIsRequired() {
        List<ReportFilterDefinition> definitions = List.of(
                MonthRangeFilter.withoutDefault(ReportPeriodBasis.BILLING_MONTHS));

        assertThat(problems(RawFilters.NONE, definitions)).containsExactly(new FieldProblem("fromMonth", "REQUIRED"),
                new FieldProblem("toMonth", "REQUIRED"));
        assertThat(problems(raw("2025-01", null, null, null), definitions))
                .containsExactly(new FieldProblem("toMonth", "REQUIRED"));
        assertThat(definitions.getFirst().defaults(TODAY)).isEmpty();
    }

    @Test
    void aReportWithoutAMonthRangeHasNoMonths() {
        ReportFilters filters = ReportFilters.parse(List.of(new StreetFilter()), RawFilters.NONE, CONTEXT);

        assertThatThrownBy(filters::fromMonth).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(filters::period).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theListDescribesEachFilterWithItsDefaults() {
        List<ReportFilter> infos = CHARGES_BY_MONTH.stream().map(filter -> filter.info(TODAY)).toList();

        assertThat(infos).extracting(ReportFilter::getKey).containsExactly("period", "chargeTypes", "street");
        assertThat(infos.get(0).getBasis()).isEqualTo(ReportPeriodBasis.BILLING_MONTHS);
        assertThat(infos.get(0).getDefaults()).extracting(ReportFilterValue::getValue)
                .containsExactly("2026-09", "2026-09");
        assertThat(infos.get(1).getBasis()).isNull();
        assertThat(infos.get(2).getRequired()).isFalse();
    }

    @Test
    void theHeaderBlockDescribesTheFiltersInTheLanguage() {
        ReportFilters filters = ReportFilters.parse(CHARGES_BY_MONTH, raw("2025-01", "2025-03",
                List.of(MEMBERSHIP.id(), CUSTOM.id()), "Лісова"), CONTEXT);
        ReportTexts uk = ReportTexts.of(ReportLanguage.UK);
        Map<UUID, String> names = Map.of(MEMBERSHIP.id(), "Членський внесок", CUSTOM.id(), "Охорона");

        assertThat(CHARGES_BY_MONTH.stream().map(filter -> filter.describe(filters, uk, names::get)).toList())
                .containsExactly("Місяці нарахувань: 01.2025 – 03.2025", "Види нарахувань: Членський внесок, Охорона",
                        "Вулиця: Лісова");
        ReportFilters all = ReportFilters.parse(CHARGES_BY_MONTH, RawFilters.NONE, CONTEXT);
        ReportTexts en = ReportTexts.of(ReportLanguage.EN);
        assertThat(CHARGES_BY_MONTH.stream().map(filter -> filter.describe(all, en, names::get)).toList())
                .containsExactly("Billing months: 09.2026 – 09.2026", "Charge types: all", "Street: all");
    }

    @Test
    void theYearIsTheCurrentOneByDefaultAndAYearAfterItIsInvalid() {
        List<ReportFilterDefinition> landTax = List.of(new YearFilter());

        ReportFilters current = ReportFilters.parse(landTax, RawFilters.NONE, CONTEXT);
        assertThat(current.year()).isEqualTo(2026);
        assertThat(values(current)).containsExactly("year=2026");
        assertThat(landTax.getFirst().info(TODAY).getDefaults()).extracting(ReportFilterValue::getValue)
                .containsExactly("2026");
        ReportFilters past = ReportFilters.parse(landTax, year(2024), CONTEXT);
        assertThat(past.year()).isEqualTo(2024);
        assertThat(landTax.getFirst().describe(past, ReportTexts.of(ReportLanguage.UK), id -> "")).isEqualTo(
                "Рік: 2024");
        assertThat(problems(year(2027), landTax)).containsExactly(new FieldProblem("year", "INVALID"));
        assertThatThrownBy(ReportFilters.parse(CHARGES_BY_MONTH, RawFilters.NONE, CONTEXT)::year)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theKindsOfCorrectionAreBothByDefaultAndARepeatedKindCountsOnce() {
        List<ReportFilterDefinition> kinds = List.of(new CorrectionKindsFilter());

        ReportFilters both = ReportFilters.parse(kinds, RawFilters.NONE, CONTEXT);
        assertThat(both.correctionKinds()).isEmpty();
        assertThat(values(both)).isEmpty();
        assertThat(kinds.getFirst().describe(both, ReportTexts.of(ReportLanguage.EN), id -> ""))
                .isEqualTo("Kinds of correction: all");

        ReportFilters reversals = ReportFilters.parse(kinds, new RawFilters(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, List.of(ReportCorrectionKind.REVERSAL,
                        ReportCorrectionKind.ADJUSTMENT, ReportCorrectionKind.REVERSAL)), CONTEXT);
        assertThat(reversals.correctionKinds()).containsExactly(CorrectionKind.REVERSAL, CorrectionKind.ADJUSTMENT);
        assertThat(values(reversals)).containsExactly("correctionKinds=REVERSAL", "correctionKinds=ADJUSTMENT");
        assertThat(kinds.getFirst().describe(reversals, ReportTexts.of(ReportLanguage.UK), id -> ""))
                .isEqualTo("Види виправлень: Сторно, Коригування");
    }

    private static RawFilters year(int year) {
        return new RawFilters(null, null, null, null, null, year, null, null, null, null, null, null, null, null,
                null);
    }

    private static List<FieldProblem> problems(RawFilters raw, List<ReportFilterDefinition> definitions) {
        try {
            ReportFilters.parse(definitions, raw, CONTEXT);
        } catch (ApiProblemException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo("REPORT_FILTER_INVALID");
            return e.errors();
        }
        throw new AssertionError("The filters were accepted");
    }

    private static List<String> values(ReportFilters filters) {
        return filters.values().stream().map(value -> value.getParam().getValue() + "=" + value.getValue()).toList();
    }

    private static RawFilters raw(String fromMonth, String toMonth, List<UUID> chargeTypeIds, String street) {
        return new RawFilters(fromMonth, toMonth, null, null, null, null, chargeTypeIds, street, null, null, null,
                null, null, null, null);
    }
}
