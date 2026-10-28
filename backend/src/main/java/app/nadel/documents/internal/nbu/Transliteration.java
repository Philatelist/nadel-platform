package app.nadel.documents.internal.nbu;

/**
 * Ukrainian letters in Latin by the table of the Cabinet of Ministers of Ukraine Resolution No. 55 of 27.01.2010, for
 * the ISO 646 reference of the NBU QR code (Annex 4 table 2 row 11). Only the plot number of an invoice number can
 * hold letters, so this is the whole table and nothing more: є, ї, й, ю, я at the start of a word take their initial
 * forms (ye, yi, y, yu, ya), «зг» is {@code zgh}, the soft sign and an apostrophe after a letter are dropped, an
 * upper-case letter gives a capital first Latin letter. Other printable ISO 646 characters are kept; any other
 * character becomes {@code -}. Apostrophes are already {@code '} here ({@link NbuQrPayload#clean}).
 */
final class Transliteration {

    /** The Ukrainian alphabet in order, lower case (U+0430 а … U+044F я, with ґ є і ї in their places). */
    private static final String LETTERS = "\u0430\u0431\u0432\u0433\u0491\u0434\u0435\u0454\u0436\u0437\u0438\u0456"
            + "\u0457\u0439\u043A\u043B\u043C\u043D\u043E\u043F"
            + "\u0440\u0441\u0442\u0443\u0444\u0445\u0446\u0447"
            + "\u0448\u0449\u044C\u044E\u044F";
    /** The Latin of each letter of {@link #LETTERS} inside a word. */
    private static final String[] LATIN = {"a", "b", "v", "h", "g", "d", "e", "ie", "zh", "z", "y", "i", "i", "i",
        "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "iu", "ia"};
    /** The Latin of each letter of {@link #LETTERS} at the start of a word. */
    private static final String[] LATIN_AT_START = {"a", "b", "v", "h", "g", "d", "e", "ye", "zh", "z", "y", "i",
        "yi", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "yu",
        "ya"};
    private static final char APOSTROPHE = '\'';
    private static final char ZE = '\u0437';
    private static final char HE = '\u0433';

    private Transliteration() {
    }

    /** The text in printable ISO 646. */
    static String latin(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char lower = Character.toLowerCase(c);
            int letter = LETTERS.indexOf(lower);
            if (letter < 0) {
                boolean apostropheInWord = c == APOSTROPHE && i > 0
                        && LETTERS.indexOf(Character.toLowerCase(text.charAt(i - 1))) >= 0;
                if (!apostropheInWord) {
                    out.append(c >= 0x20 && c <= 0x7E ? c : '-');
                }
                continue;
            }
            boolean start = i == 0 || !inWord(text.charAt(i - 1));
            String latin = lower == HE && i > 0 && Character.toLowerCase(text.charAt(i - 1)) == ZE ? "gh"
                    : (start ? LATIN_AT_START : LATIN)[letter];
            if (!latin.isEmpty() && Character.isUpperCase(c)) {
                latin = Character.toUpperCase(latin.charAt(0)) + latin.substring(1);
            }
            out.append(latin);
        }
        return out.toString();
    }

    private static boolean inWord(char previous) {
        return Character.isLetter(previous) || previous == APOSTROPHE;
    }
}
