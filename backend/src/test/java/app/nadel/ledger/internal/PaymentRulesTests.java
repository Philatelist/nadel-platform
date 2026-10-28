package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.ledger.PaymentSource;
import app.nadel.money.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The value rules of a payment and a payment reversal (feature 015 §2.1, §2.5; technical considerations 2.1): an
 * amount above zero within {@code numeric(14,2)}, a date not after today in Kyiv, a payer note of at most 200
 * characters, a bank reference of at most 64 characters on bank payments only, a reason of 1 to 500 characters.
 */
class PaymentRulesTests {

    /** 22:30 UTC on 27.10 is already 00:30 on 28.10 in Kyiv (UTC+2 in winter time). */
    private static final Clock LATE_EVENING_UTC = Clock.fixed(Instant.parse("2026-10-27T22:30:00Z"), ZoneOffset.UTC);

    @Test
    void theAmountIsAboveZeroAndFitsTheColumn() {
        assertThat(PaymentRules.amountProblem(Money.parse("700.00"))).isNull();
        assertThat(PaymentRules.amountProblem(Money.parse("0.01"))).isNull();
        assertThat(PaymentRules.amountProblem(Money.parse("999999999999.99"))).isNull();
        assertThat(PaymentRules.amountProblem(Money.parse("0.00"))).isEqualTo("AMOUNT_NOT_POSITIVE");
        assertThat(PaymentRules.amountProblem(Money.parse("-5.00"))).isEqualTo("AMOUNT_NOT_POSITIVE");
        assertThat(PaymentRules.amountProblem(Money.of(new BigDecimal("1000000000000")))).isEqualTo("INVALID");
    }

    @Test
    void todayIsTheDayInKyivWhateverTheZoneOfTheClock() {
        assertThat(PaymentRules.today(LATE_EVENING_UTC)).isEqualTo(LocalDate.parse("2026-10-28"));
    }

    @Test
    void theDateIsNotAfterTodayInKyiv() {
        assertThat(PaymentRules.dateProblem(LocalDate.parse("2026-10-28"), LATE_EVENING_UTC)).isNull();
        assertThat(PaymentRules.dateProblem(LocalDate.parse("2026-01-31"), LATE_EVENING_UTC)).isNull();
        assertThat(PaymentRules.dateProblem(LocalDate.parse("2026-10-29"), LATE_EVENING_UTC))
                .isEqualTo("DATE_IN_FUTURE");
    }

    @Test
    void aTextIsTrimmedAndABlankTextIsNone() {
        assertThat(PaymentRules.text("  Приніс син  ")).isEqualTo("Приніс син");
        assertThat(PaymentRules.text("   ")).isNull();
        assertThat(PaymentRules.text(null)).isNull();
    }

    @Test
    void thePayerNoteHasAtMost200Characters() {
        assertThat(PaymentRules.payerNoteProblem(null)).isNull();
        assertThat(PaymentRules.payerNoteProblem("ж".repeat(200))).isNull();
        assertThat(PaymentRules.payerNoteProblem("  " + "ж".repeat(200) + "  ")).isNull();
        assertThat(PaymentRules.payerNoteProblem("ж".repeat(201))).isEqualTo("TOO_LONG");
        // a character outside the basic plane is one character, as char_length counts it
        assertThat(PaymentRules.payerNoteProblem("🌻".repeat(200))).isNull();
    }

    @Test
    void aBankReferenceHasAtMost64CharactersAndOnlyABankPaymentHasOne() {
        assertThat(PaymentRules.bankReferenceProblem("ПЛ-2026/10-0042", PaymentSource.BANK)).isNull();
        assertThat(PaymentRules.bankReferenceProblem("7".repeat(64), PaymentSource.BANK)).isNull();
        assertThat(PaymentRules.bankReferenceProblem("7".repeat(65), PaymentSource.BANK)).isEqualTo("TOO_LONG");
        assertThat(PaymentRules.bankReferenceProblem(null, PaymentSource.BANK)).isNull();
        assertThat(PaymentRules.bankReferenceProblem("  ", PaymentSource.CASH)).isNull();
        assertThat(PaymentRules.bankReferenceProblem("42", PaymentSource.CASH)).isEqualTo("INVALID");
    }

    @Test
    void aReversalNeedsAReasonOfAtMost500Characters() {
        assertThat(PaymentRules.reasonProblem("Помилкова ділянка")).isNull();
        assertThat(PaymentRules.reasonProblem("р".repeat(500))).isNull();
        assertThat(PaymentRules.reasonProblem("р".repeat(501))).isEqualTo("TOO_LONG");
        assertThat(PaymentRules.reasonProblem("   ")).isEqualTo("REQUIRED");
        assertThat(PaymentRules.reasonProblem(null)).isEqualTo("REQUIRED");
    }
}
