package app.nadel.reports.internal;

/**
 * The one rule for text and attribute values the template filler writes: the JDK's StAX writer escapes {@code & < >}
 * and quotes but writes characters XML 1.0 forbids as they are, and such a part makes the whole workbook unreadable.
 * Values are often user-entered free text (a street, a charge type, the cooperative's requisites, the template name), so
 * every value passes {@link #clean} before it is written.
 *
 * <p>A character outside the XML 1.0 {@code Char} production ({@code #x9 | #xA | #xD | [#x20-#xD7FF] |
 * [#xE000-#xFFFD] | [#x10000-#x10FFFF]}) becomes a space, so a vertical tab or form feed pasted between two words keeps
 * them apart. A valid surrogate pair (a supplementary character) is kept; an unpaired surrogate is replaced like any
 * other forbidden character.
 */
final class XmlText {

    private XmlText() {
    }

    /** The text with every character XML 1.0 forbids replaced by a space; the same instance when there is none. */
    static String clean(String text) {
        StringBuilder cleaned = null;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            int width = 1;
            boolean valid;
            if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
                valid = true; // every supplementary code point is a Char
                width = 2;
            } else {
                valid = c == 0x9 || c == 0xA || c == 0xD || c >= 0x20 && c <= 0xD7FF || c >= 0xE000 && c <= 0xFFFD;
            }
            if (!valid && cleaned == null) {
                cleaned = new StringBuilder(length).append(text, 0, i);
            }
            if (cleaned != null) {
                if (valid) {
                    cleaned.append(text, i, i + width);
                } else {
                    cleaned.append(' ');
                }
            }
            i += width - 1;
        }
        return cleaned == null ? text : cleaned.toString();
    }
}
