package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.nadel.identity.Permission;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.money.Money;
import app.nadel.settings.CooperativeSettings;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.treasury.CategoryRef;
import app.nadel.treasury.IncomeExpense;
import app.nadel.treasury.TreasuryReadModel;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.ReportTemplateFieldType;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * The cash book fields of report templates (feature 018 §2.12, technical considerations 2.7): {@code totals.cashbook.*}
 * and the row set {@code incomeExpense} are the income and expense report of the period, with the names of the lines
 * in the file's language; they need {@code CASHBOOK_VIEW} besides {@code REPORTS_VIEW}.
 */
class CashBookTemplateValuesTests {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);
    private static final ChargeTypeFacts MEMBERSHIP = new ChargeTypeFacts(UUID.randomUUID(), "MEMBERSHIP",
            "Membership fee", false, true, false, 1);
    private static final CategoryRef MATERIALS = new CategoryRef(UUID.randomUUID(), CategoryRef.Kind.EXPENSE,
            "MATERIALS", null, 1, false);
    private static final CategoryRef OWN = new CategoryRef(UUID.randomUUID(), CategoryRef.Kind.INCOME, null,
            "Оренда сцени", 5, false);

    @Test
    void theTotalsAndTheLinesAreTheIncomeAndExpenseOfThePeriod() {
        TemplateValues.Resolver resolver = resolver(Set.of(Permission.REPORTS_VIEW, Permission.CASHBOOK_VIEW));

        assertThat(money(resolver, "totals.cashbook.cashStart")).isEqualTo("1500.00");
        assertThat(money(resolver, "totals.cashbook.cashEnd")).isEqualTo("430.00");
        assertThat(money(resolver, "totals.cashbook.bankStart")).isEqualTo("20000.00");
        assertThat(money(resolver, "totals.cashbook.bankEnd")).isEqualTo("22967.50");
        assertThat(money(resolver, "totals.cashbook.income")).isEqualTo("2362.50");
        assertThat(money(resolver, "totals.cashbook.expenses")).isEqualTo("450.00");
        assertThat(money(resolver, "totals.cashbook.differences")).isEqualTo("-15.00");
        assertThat(money(resolver, "totals.cashbook.result")).isEqualTo("1897.50");

        List<Map<String, TemplateValue>> rows = resolver.rows(TemplateRowSet.INCOME_EXPENSE);
        assertThat(rows).extracting(row -> row.get("rows.incomeExpense.name").value()).containsExactly(
                "Членський внесок", "Аванси", "Сторнування платежів попередніх періодів", "Оренда сцени",
                "Матеріали", "Різниця перерахунку", "Банківська різниця");
        assertThat(rows).extracting(row -> row.get("rows.incomeExpense.section").value()).containsExactly(
                "Платежі членів", "Аванси", "Сторнування платежів попередніх періодів", "Інші доходи", "Витрати",
                "Різниці", "Різниці");
        assertThat(rows.getFirst().get("rows.incomeExpense.amount"))
                .isEqualTo(TemplateValue.of(ReportTemplateFieldType.MONEY, Money.parse("2200.00")));
    }

    @Test
    void theCashBookFieldsNeedTheCashBookView() {
        TemplateValues.Resolver resolver = resolver(Set.of(Permission.REPORTS_VIEW));

        assertThatThrownBy(() -> resolver.single("totals.cashbook.result")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> resolver.rows(TemplateRowSet.INCOME_EXPENSE))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(TemplateField.TOTALS_CASHBOOK_RESULT.permission()).isEqualTo(Permission.CASHBOOK_VIEW);
        assertThat(TemplateRowSet.of("rows.incomeExpense.amount")).contains(TemplateRowSet.INCOME_EXPENSE);
    }

    private static String money(TemplateValues.Resolver resolver, String key) {
        return resolver.single(key).orElseThrow().value().toString();
    }

    /** The worked October of {@code IncomeExpenseCalculatorTests}, with one charge type and two categories. */
    private static TemplateValues.Resolver resolver(Set<Permission> permissions) {
        Map<UUID, Money> byType = new LinkedHashMap<>();
        byType.put(MEMBERSHIP.id(), Money.parse("2200.00"));
        IncomeExpense figures = new IncomeExpense(FROM, TO, byType, Money.parse("400.00"), Money.parse("-250.00"),
                Map.of(OWN.id(), Money.parse("12.50")), Map.of(MATERIALS.id(), Money.parse("450.00")),
                Money.parse("-20.00"), Money.parse("5.00"), Money.parse("1500.00"), Money.parse("430.00"),
                Money.parse("20000.00"), Money.parse("22967.50"));
        TreasuryReadModel treasury = mock(TreasuryReadModel.class);
        when(treasury.incomeExpense(any(), any())).thenReturn(figures);
        when(treasury.categories()).thenReturn(List.of(MATERIALS, OWN));
        ChargeTypeCatalog chargeTypes = mock(ChargeTypeCatalog.class);
        when(chargeTypes.all()).thenReturn(List.of(MEMBERSHIP));
        TemplateValues values = new TemplateValues(mock(TenantDirectory.class), mock(CooperativeSettings.class),
                chargeTypes, mock(LedgerReadModel.class), mock(PlotBalancesReport.class), mock(LandTaxReport.class),
                mock(ChargesByMonthReport.class), mock(ElectricityTreeReport.class), mock(WaterReport.class),
                mock(PaymentsReport.class), new IncomeExpenseReport(treasury), new ReportLimits(20_000));
        return values.resolver(FROM, TO, ReportLanguage.UK, "Тест", Instant.parse("2026-11-04T10:00:00Z"),
                permissions);
    }
}
