package app.nadel.documents.internal;

import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * How the documents write amounts, days and months (feature 016 §2): {@code 1 234,56 ₴} with no-break spaces
 * (U+00A0) between the thousands and before the sign, a decimal comma and always two decimals; days as
 * {@code 05.11.2026} in {@code Europe/Kyiv}; months as «жовтень 2026» (nominative) and «жовтня» (genitive), from the
 * document bundle. Pure: an amount is written from its exact value, never rounded again.
 */
final class DocumentFormat {

    /** The cooperatives' zone: a day of a document is a day in Kyiv. */
    static final ZoneId ZONE = ZoneId.of("Europe/Kyiv");

    static final char NBSP = ' ';

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private DocumentFormat() {
    }

    /** {@code 1 234,56 ₴}: the amount with the hryvnia sign. */
    static String money(Money amount) {
        return amount(amount) + NBSP + DocumentTexts.uk().text("money.currency");
    }

    /** {@code 1 234,56}: the amount without the sign; a negative amount starts with {@code -}. */
    static String amount(Money amount) {
        String plain = Objects.requireNonNull(amount, "amount").value().toPlainString();
        boolean negative = plain.startsWith("-");
        String digits = negative ? plain.substring(1) : plain;
        int point = digits.indexOf('.');
        String whole = digits.substring(0, point);
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) {
                grouped.append(NBSP);
            }
            grouped.append(whole.charAt(i));
        }
        return (negative ? "-" : "") + grouped + "," + digits.substring(point + 1);
    }

    /** {@code 05.11.2026}. */
    static String day(LocalDate day) {
        return DAY.format(day);
    }

    /** The day of the instant in Kyiv, {@code 05.11.2026}. */
    static String day(Instant instant) {
        return day(dayOf(instant));
    }

    /** The day of the instant in Kyiv. */
    static LocalDate dayOf(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    /** «жовтень 2026». */
    static String month(YearMonth month) {
        return DocumentTexts.uk().text("month.nominative." + month.getMonthValue()) + " " + month.getYear();
    }

    /** «жовтня»: the month after a day number («5 жовтня»). */
    static String monthGenitive(YearMonth month) {
        return DocumentTexts.uk().text("month.genitive." + month.getMonthValue());
    }

    /**
     * A service period: «жовтень 2026» when it is one whole month, otherwise {@code 05.09.2026 – 04.10.2026} (one day:
     * that day).
     */
    static String period(LocalDate from, LocalDate to) {
        if (from.getDayOfMonth() == 1 && YearMonth.from(from).equals(YearMonth.from(to))
                && to.getDayOfMonth() == to.lengthOfMonth()) {
            return month(YearMonth.from(from));
        }
        return from.equals(to) ? day(from) : day(from) + " – " + day(to);
    }
}
