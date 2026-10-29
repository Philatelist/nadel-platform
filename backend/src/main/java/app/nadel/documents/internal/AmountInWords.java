package app.nadel.documents.internal;

import app.nadel.money.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An amount of hryvnias in Ukrainian words, as a cash receipt prints it (feature 016 §2.8, technical considerations
 * 2.7): «сто гривень 00 копійок». Pure: the words come from the document bundle ({@link DocumentTexts}).
 * <ul>
 * <li>The amount is split exactly into hryvnias and kopecks ({@link Money} has two decimals); nothing is rounded.</li>
 * <li>Hryvnias are written by triads: units, thousands (feminine: «одна тисяча», «дві тисячі»), millions and
 * billions (masculine); the hryvnia itself is feminine («одна гривня», «дві гривні»).</li>
 * <li>Every scale word and the currency agree with the number: one (ending in 1), few (ending in 2-4) or many
 * (ending in 0, 5-9), and 11-14 always many.</li>
 * <li>Zero hryvnias are «нуль гривень»; kopecks are always two digits with the agreeing word («05 копійок»,
 * «21 копійка»).</li>
 * <li>Range 0.00 to 999 999 999 999.99; a negative or larger amount is refused.</li>
 * </ul>
 */
final class AmountInWords {

    /** The largest amount that has words: 999 999 999 999.99. */
    static final BigDecimal MAX = new BigDecimal("999999999999.99");

    private static final long THOUSAND = 1000;
    /** The scale words of the triads, lowest first; null for the units triad. */
    private static final String[] SCALES = {null, "words.thousand", "words.million", "words.billion"};

    private AmountInWords() {
    }

    /**
     * The amount in words.
     *
     * @throws IllegalArgumentException when the amount is negative or above {@link #MAX}
     */
    static String of(Money amount) {
        Objects.requireNonNull(amount, "amount");
        BigDecimal value = amount.value();
        if (value.signum() < 0 || value.compareTo(MAX) > 0) {
            throw new IllegalArgumentException("An amount in words is between 0.00 and " + MAX);
        }
        DocumentTexts texts = DocumentTexts.uk();
        BigInteger hryvnias = value.toBigInteger();
        int kopecks = value.subtract(new BigDecimal(hryvnias)).movePointRight(2).intValueExact();
        long whole = hryvnias.longValueExact();
        String words = whole == 0 ? texts.text("words.zero") : number(whole, texts);
        return texts.text("words.amount", Map.of(
                "hryvnias", words,
                "hryvniaWord", texts.text("words.hryvnia." + form(whole)),
                "kopecks", String.format("%02d", kopecks),
                "kopeckWord", texts.text("words.kopeck." + form(kopecks))));
    }

    /** The words of a number above zero below 10^12, units feminine (a hryvnia). */
    private static String number(long value, DocumentTexts texts) {
        List<String> triads = new ArrayList<>();
        long rest = value;
        for (int scale = 0; rest > 0; scale++) {
            int triad = (int) (rest % THOUSAND);
            rest /= THOUSAND;
            if (triad == 0) {
                continue;
            }
            boolean feminine = scale <= 1; // hryvnias and thousands
            String words = triad(triad, feminine, texts);
            String scaleKey = SCALES[scale];
            triads.addFirst(scaleKey == null ? words : words + " " + texts.text(scaleKey + "." + form(triad)));
        }
        return String.join(" ", triads);
    }

    /** The words of 1-999. */
    private static String triad(int value, boolean feminine, DocumentTexts texts) {
        List<String> words = new ArrayList<>();
        int hundreds = value / 100;
        int below = value % 100;
        if (hundreds > 0) {
            words.add(texts.text("words.hundreds." + hundreds));
        }
        if (below >= 10 && below <= 19) {
            words.add(texts.text("words.teens." + below));
        } else {
            int tens = below / 10;
            int ones = below % 10;
            if (tens > 0) {
                words.add(texts.text("words.tens." + tens));
            }
            if (ones > 0) {
                words.add(texts.text(feminine && ones <= 2 ? "words.onesFeminine." + ones : "words.ones." + ones));
            }
        }
        return String.join(" ", words);
    }

    /** The agreeing form of a word after the number: {@code one}, {@code few} or {@code many}. */
    static String form(long number) {
        long lastTwo = number % 100;
        long last = number % 10;
        if (lastTwo >= 11 && lastTwo <= 14) {
            return "many";
        }
        if (last == 1) {
            return "one";
        }
        if (last >= 2 && last <= 4) {
            return "few";
        }
        return "many";
    }
}
