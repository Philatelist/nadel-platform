package app.nadel.documents;

import app.nadel.documents.internal.DocumentFormat;
import app.nadel.money.Money;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * How documents and messages write amounts, days and months, for the other modules' texts (feature 017: invoice and
 * payment messages say it as the invoices do): {@code 1 234,56 ₴} with no-break spaces, {@code 28.10.2026},
 * «жовтень 2026». Pure.
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

    /** {@code 28.10.2026}. */
    public static String day(LocalDate day) {
        return DocumentFormat.day(day);
    }
}
