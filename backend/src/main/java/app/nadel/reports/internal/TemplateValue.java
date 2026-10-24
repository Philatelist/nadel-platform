package app.nadel.reports.internal;

import app.nadel.money.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import nadel.contract.model.ReportTemplateFieldType;
import nadel.contract.model.ReportTemplateValueReason;
import org.jspecify.annotations.Nullable;

/**
 * The value of one placeholder for one fill ({@link TemplateValues}): a typed value, nothing (written empty, no
 * warning: a plot without a street, a debt without an oldest date) or a value that is not known (written empty and
 * listed as {@code VALUE_UNKNOWN} with the reason, never {@code 0}).
 * <p>
 * The Java type of a value follows its {@link ReportTemplateFieldType}: {@code TEXT} a {@link String} (codes already in
 * the file's language), {@code NUMBER} and {@code QUANTITY} a {@link BigDecimal} with its scale, {@code MONEY} a
 * {@link Money}, {@code INT} a {@link Long}, {@code DATE} a {@link LocalDate}, {@code MONTH} a {@link YearMonth} and
 * {@code DATE_TIME} a {@link LocalDateTime} in Europe/Kyiv.
 *
 * @param value  the value; null when empty or not known
 * @param reason why the value is not known; null when it is known or empty
 */
record TemplateValue(@Nullable ReportTemplateFieldType type, @Nullable Object value,
        @Nullable ReportTemplateValueReason reason) {

    static final TemplateValue EMPTY = new TemplateValue(null, null, null);

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final LocalDate EPOCH_1900 = LocalDate.of(1899, 12, 30);
    private static final LocalDate EPOCH_1904 = LocalDate.of(1904, 1, 1);
    private static final BigDecimal SECONDS_A_DAY = BigDecimal.valueOf(86_400);

    TemplateValue {
        if (value != null) {
            Objects.requireNonNull(type, "type");
            if (reason != null) {
                throw new IllegalArgumentException("A known value has no reason");
            }
            if (!javaType(type).isInstance(value)) {
                throw new IllegalArgumentException("A " + type + " value is a " + javaType(type).getSimpleName());
            }
        } else if (type != null) {
            throw new IllegalArgumentException("An empty value has no type");
        }
    }

    /** The value, or {@link #EMPTY} for null. */
    static TemplateValue of(ReportTemplateFieldType type, @Nullable Object value) {
        return value == null ? EMPTY : new TemplateValue(type, value, null);
    }

    static TemplateValue unknown(ReportTemplateValueReason reason) {
        return new TemplateValue(null, null, Objects.requireNonNull(reason, "reason"));
    }

    /** True when the value is not known: the cell is written empty and the warning {@code VALUE_UNKNOWN} listed. */
    boolean isUnknown() {
        return reason != null;
    }

    /** True when there is a value to write. */
    boolean isPresent() {
        return value != null;
    }

    /**
     * The value as text inside a cell of a template (feature 013 §4.1): amounts, numbers and quantities with the
     * language's separators ({@code 1 250,50} with a no-break space in Ukrainian, {@code 1,250.50} in English) and
     * their scale as given, whole numbers without separators (a year is {@code 2025}), days {@code dd.MM.yyyy},
     * months {@code MM.yyyy}, a time {@code dd.MM.yyyy HH:mm}; nothing for an empty or unknown value.
     */
    String text(ReportLanguage language) {
        if (value == null) {
            return "";
        }
        return switch (Objects.requireNonNull(type)) {
            case TEXT -> (String) value;
            case MONEY -> number(((Money) value).value(), language);
            case NUMBER, QUANTITY -> number((BigDecimal) value, language);
            case INT -> value.toString(); // a year or a count: no separators
            case DATE -> ReportTexts.day((LocalDate) value);
            case MONTH -> ReportTexts.month((YearMonth) value);
            case DATE_TIME -> DATE_TIME.format((LocalDateTime) value);
        };
    }

    /**
     * A day, month (its first day) or time as an Excel date serial: days since 30.12.1899, or since 01.01.1904 in a
     * workbook of the 1904 date system; a time of day is the fraction of the day (a time is not money: the money
     * rounding does not apply).
     */
    String serial(boolean date1904) {
        LocalDate day = value instanceof LocalDateTime time ? time.toLocalDate()
                : value instanceof YearMonth month ? month.atDay(1) : (LocalDate) Objects.requireNonNull(value);
        long days = ChronoUnit.DAYS.between(date1904 ? EPOCH_1904 : EPOCH_1900, day);
        if (!(value instanceof LocalDateTime time)) {
            return Long.toString(days);
        }
        BigDecimal fraction = BigDecimal.valueOf(time.toLocalTime().toSecondOfDay())
                .divide(SECONDS_A_DAY, MathContext.DECIMAL64);
        return BigDecimal.valueOf(days).add(fraction).stripTrailingZeros().toPlainString();
    }

    private static String number(BigDecimal number, ReportLanguage language) {
        String plain = number.abs().toPlainString();
        int point = plain.indexOf('.');
        String whole = point < 0 ? plain : plain.substring(0, point);
        StringBuilder text = new StringBuilder(number.signum() < 0 ? "-" : "");
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) {
                text.append(language == ReportLanguage.UK ? '\u00a0' : ',');
            }
            text.append(whole.charAt(i));
        }
        if (point >= 0) {
            text.append(language == ReportLanguage.UK ? ',' : '.').append(plain, point + 1, plain.length());
        }
        return text.toString();
    }

    static Class<?> javaType(ReportTemplateFieldType type) {
        return switch (type) {
            case TEXT -> String.class;
            case NUMBER, QUANTITY -> BigDecimal.class;
            case MONEY -> Money.class;
            case INT -> Long.class;
            case DATE -> LocalDate.class;
            case MONTH -> YearMonth.class;
            case DATE_TIME -> LocalDateTime.class;
        };
    }
}
