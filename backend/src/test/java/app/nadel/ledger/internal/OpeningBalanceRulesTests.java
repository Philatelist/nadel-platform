package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import app.nadel.audit.FieldChange;
import app.nadel.money.Money;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The value rules of an opening balance (feature 011 §2.9), shared by the entry on the plot account and the Excel
 * import: amount greater than zero and within {@code numeric(14,2)}, a date not after today, a trimmed optional reason,
 * and the audit values.
 */
class OpeningBalanceRulesTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    @Test
    void theAmountIsGreaterThanZeroAndHasAtMostTwelveIntegerDigits() {
        assertThat(OpeningBalanceRules.amountProblem(Money.parse("1500.00"))).isNull();
        assertThat(OpeningBalanceRules.amountProblem(Money.parse("0.01"))).isNull();
        assertThat(OpeningBalanceRules.amountProblem(Money.parse("999999999999.99"))).isNull();
        assertThat(OpeningBalanceRules.amountProblem(Money.parse("0.00"))).isEqualTo("AMOUNT_NOT_POSITIVE");
        assertThat(OpeningBalanceRules.amountProblem(Money.parse("-5.00"))).isEqualTo("AMOUNT_NOT_POSITIVE");
        assertThat(OpeningBalanceRules.amountProblem(Money.of(new BigDecimal("1000000000000")))).isEqualTo("INVALID");
    }

    @Test
    void theDateIsNotAfterToday() {
        assertThat(OpeningBalanceRules.dateProblem(TODAY, TODAY)).isNull();
        assertThat(OpeningBalanceRules.dateProblem(TODAY.minusYears(1), TODAY)).isNull();
        assertThat(OpeningBalanceRules.dateProblem(TODAY.plusDays(1), TODAY)).isEqualTo("DATE_IN_FUTURE");
    }

    @Test
    void theReasonIsTrimmedAndABlankReasonIsNone() {
        assertThat(OpeningBalanceRules.reason("  Борг за 2025 рік ")).isEqualTo("Борг за 2025 рік");
        assertThat(OpeningBalanceRules.reason("   ")).isNull();
        assertThat(OpeningBalanceRules.reason(null)).isNull();
    }

    @Test
    void theAuditValuesAreCodesAndParametersWithTheReasonOnlyWhenGiven() {
        ChargeTypeFacts membership = new ChargeTypeFacts(UUID.randomUUID(), "MEMBERSHIP", "Membership fee", false,
                true, false, 1);
        List<FieldChange> withReason = OpeningBalanceRules.auditChanges(membership, Money.parse("1500.00"),
                LocalDate.of(2026, 9, 1), YearMonth.of(2026, 10), "Борг за 2025 рік");
        assertThat(withReason).extracting(FieldChange::field, FieldChange::newValue).containsExactly(
                tuple("chargeType", "base:MEMBERSHIP"),
                tuple("amount", "1500.00"),
                tuple("date", "2026-09-01"),
                tuple("billingMonth", "2026-10"),
                tuple("reason", "Борг за 2025 рік"));
        assertThat(OpeningBalanceRules.auditChanges(membership, Money.parse("1500.00"), LocalDate.of(2026, 9, 1),
                YearMonth.of(2026, 9), null)).extracting(FieldChange::field)
                .containsExactly("chargeType", "amount", "date", "billingMonth");
    }
}
