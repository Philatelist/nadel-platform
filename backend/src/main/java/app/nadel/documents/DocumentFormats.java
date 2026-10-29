package app.nadel.documents;

import app.nadel.documents.internal.DocumentFormat;
import app.nadel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * How documents and messages write amounts, days and months, for the other modules' texts (feature 017: invoice and
 * payment messages say it as the invoices do): {@code 1 234,56 ₴} with no-break spaces, {@code 28.10.2026},
 * «жовтень 2026», a reading {@code 12 345}. Pure.
 */
public final class DocumentFormats {

    private DocumentFormats() {
    }

    /** {@code 1 234,56 ₴}. */
    public static String money(Money amount) {
        return DocumentFormat.money(amount);
    }

    /** «жовтень 2026». */
    public static String month(YearMonth month) {
        return DocumentFormat.month(month);
    }

    /**
     * A decimal that is not money (a meter reading {@code 12345.000}): trailing zeros dropped, a decimal comma and
     * no-break spaces between the thousands ({@code 12 345}, {@code 12 345,5}). Nothing is rounded.
     */
    public static String number(BigDecimal value) {
        return DocumentFormat.decimal(value.toPlainString(), 0);
    }

    /** {@code 28.10.2026}. */
    public static String day(LocalDate day) {
        return DocumentFormat.day(day);
    }
}
