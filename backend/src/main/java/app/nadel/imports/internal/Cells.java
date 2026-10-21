package app.nadel.imports.internal;

import app.nadel.problem.ProblemCodes;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads the value of a cell in the form a column expects. Each method returns a {@link Result}: the value (null for an
 * empty cell) or the code of the problem. Nothing here knows about sheets or columns.
 */
final class Cells {

    /** Excel stores numbers as binary floating point; 15 significant digits is what Excel itself shows and keeps. */
    private static final MathContext EXCEL_PRECISION = new MathContext(15, RoundingMode.HALF_UP);
    private static final Pattern DECIMAL = Pattern.compile("-?(\\d+([.]\\d+)?|[.]\\d+)");
    private static final Pattern DOTTED_DATE = Pattern.compile("(\\d{1,2})[.](\\d{1,2})[.](\\d{4})");
    /** Excel's day 0 in the 1900 date system, which counts the non-existent 29 February 1900 as day 60. */
    private static final LocalDate EPOCH_1900 = LocalDate.of(1899, 12, 30);
    private static final LocalDate EPOCH_1904 = LocalDate.of(1904, 1, 1);
    private static final int FIRST_SERIAL_AFTER_LEAP_BUG = 61;
    /** 31 December 9999, the last date Excel can show. */
    private static final BigDecimal LAST_SERIAL = BigDecimal.valueOf(2_958_465);
    private static final int AREA_SOTKA_TO_M2_SHIFT = 2;

    private Cells() {
    }

    /** The value or a problem code; both null for an empty cell. */
    record Result<T>(@Nullable T value, @Nullable String problem) {

        static <T> Result<T> of(@Nullable T value) {
            return new Result<>(value, null);
        }

        static <T> Result<T> problem(String code) {
            return new Result<>(null, code);
        }
    }

    /** An area in square metres. */
    record Area(BigDecimal squareMetres) {
    }

    /** Trimmed text; empty text is an empty cell. A number is read as its plain decimal text. */
    static Result<String> text(@Nullable RawCell cell) {
        String text = switch (cell) {
            case null -> null;
            case RawCell.Text value -> value.value().strip();
            case RawCell.Number number -> plain(number.value());
            case RawCell.Bool bool -> String.valueOf(bool.value());
        };
        return Result.of(text == null || text.isEmpty() ? null : text);
    }

    /**
     * A value word of either language ({@code ДІЛЯНКА}, {@code ЕЛ. ПОШТА}) as the canonical English word the other
     * modules know ({@code PLOT}, {@code EMAIL}); a word that is not in the vocabulary is returned as typed, so the
     * owning module reports it.
     *
     * @param kind one of {@link ImportVocabulary#KIND}, {@link ImportVocabulary#CHANNEL} and
     *             {@link ImportVocabulary#RESOURCE}
     */
    static Result<String> word(@Nullable RawCell cell, String kind) {
        String text = text(cell).value();
        if (text == null) {
            return Result.of(null);
        }
        String canonical = ImportVocabulary.get().canonicalValue(kind, text);
        return Result.of(canonical == null ? text : canonical);
    }

    /** Yes or no: yes/no, так/ні, true/false in any letter case, or a boolean cell. */
    static Result<Boolean> yesNo(@Nullable RawCell cell) {
        if (cell instanceof RawCell.Bool bool) {
            return Result.of(bool.value());
        }
        if (cell instanceof RawCell.Number) {
            return Result.problem(ProblemCodes.YES_NO_INVALID);
        }
        String text = text(cell).value();
        if (text == null) {
            return Result.of(null);
        }
        String canonical = ImportVocabulary.get().canonicalValue(ImportVocabulary.YES_NO, text);
        if (canonical == null) {
            return Result.problem(ProblemCodes.YES_NO_INVALID);
        }
        return Result.of(Boolean.valueOf(canonical));
    }

    /**
     * A date: an Excel date (a number in a cell formatted as a date) or text {@code DD.MM.YYYY} (also {@code D.M.YYYY}).
     * A number in a cell that is not formatted as a date is refused: it could be anything.
     *
     * @param date1904 whether the workbook counts days from 1904 (the old Mac default)
     */
    static Result<LocalDate> date(@Nullable RawCell cell, boolean date1904) {
        if (cell instanceof RawCell.Number number) {
            return number.dateFormat() ? serialDate(number.value(), date1904) : Result.problem(ProblemCodes.DATE_INVALID);
        }
        if (cell instanceof RawCell.Bool) {
            return Result.problem(ProblemCodes.DATE_INVALID);
        }
        String text = text(cell).value();
        if (text == null) {
            return Result.of(null);
        }
        Matcher matcher = DOTTED_DATE.matcher(text);
        if (!matcher.matches()) {
            return Result.problem(ProblemCodes.DATE_INVALID);
        }
        try {
            return Result.of(LocalDate.of(Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(1))));
        } catch (DateTimeException e) {
            return Result.problem(ProblemCodes.DATE_INVALID);
        }
    }

    /**
     * A decimal number. A numeric cell is cleaned of binary floating point noise; a text cell is taken digit by digit,
     * with a comma accepted as the decimal separator and spaces ignored.
     */
    static Result<BigDecimal> number(@Nullable RawCell cell) {
        if (cell instanceof RawCell.Number number) {
            return Result.of(decode(number.value()));
        }
        if (cell instanceof RawCell.Bool) {
            return Result.problem(ProblemCodes.NUMBER_INVALID);
        }
        String text = text(cell).value();
        if (text == null) {
            return Result.of(null);
        }
        String cleaned = text.replaceAll("[\\s\\u00A0]", "").replace(',', '.');
        return DECIMAL.matcher(cleaned).matches()
                ? Result.of(new BigDecimal(cleaned))
                : Result.problem(ProblemCodes.NUMBER_INVALID);
    }

    /**
     * The plot area in square metres from the two area columns. Both filled is {@code AREA_BOTH_OR_NONE}; neither
     * filled is an empty result (whether that is a problem depends on the plot). A sotka is exactly 100 square metres.
     */
    static AreaResult area(@Nullable RawCell squareMetres, @Nullable RawCell sotky) {
        Result<BigDecimal> m2 = number(squareMetres);
        Result<BigDecimal> sot = number(sotky);
        if (m2.problem() != null) {
            return new AreaResult(null, m2.problem(), false);
        }
        if (sot.problem() != null) {
            return new AreaResult(null, sot.problem(), true);
        }
        if (m2.value() != null && sot.value() != null) {
            return new AreaResult(null, ProblemCodes.AREA_BOTH_OR_NONE, false);
        }
        if (sot.value() != null) {
            return new AreaResult(new Area(sot.value().movePointRight(AREA_SOTKA_TO_M2_SHIFT)), null, true);
        }
        return new AreaResult(m2.value() == null ? null : new Area(m2.value()), null, false);
    }

    /**
     * @param area        the area, null when neither column is filled or there is a problem
     * @param problem     the problem code, or null
     * @param sotkyColumn true when the value or the problem belongs to the sotky column
     */
    record AreaResult(@Nullable Area area, @Nullable String problem, boolean sotkyColumn) {
    }

    /**
     * Decodes the binary floating point value Excel stored: {@code 0.059999999999999998} was typed as {@code 0.06}.
     * This undoes the representation error of the file format; it is not rounding of a domain value.
     */
    private static BigDecimal decode(BigDecimal stored) {
        BigDecimal decoded = stored.round(EXCEL_PRECISION).stripTrailingZeros();
        return decoded.scale() < 0 ? decoded.setScale(0) : decoded;
    }

    private static String plain(BigDecimal number) {
        return decode(number).toPlainString();
    }

    private static Result<LocalDate> serialDate(BigDecimal serial, boolean date1904) {
        if (serial.signum() < 0 || serial.compareTo(LAST_SERIAL) > 0) {
            return Result.problem(ProblemCodes.DATE_INVALID);
        }
        long days = serial.toBigInteger().longValue(); // the time of day is dropped
        if (date1904) {
            return Result.of(EPOCH_1904.plusDays(days));
        }
        return days < FIRST_SERIAL_AFTER_LEAP_BUG
                ? Result.problem(ProblemCodes.DATE_INVALID)
                : Result.of(EPOCH_1900.plusDays(days));
    }
}
