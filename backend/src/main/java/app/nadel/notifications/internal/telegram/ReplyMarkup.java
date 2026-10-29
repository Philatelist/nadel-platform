package app.nadel.notifications.internal.telegram;

import java.util.List;

/** The buttons a bot message carries (Bot API {@code reply_markup}). */
public sealed interface ReplyMarkup {

    /**
     * A keyboard of text buttons under the message box ({@code ReplyKeyboardMarkup}, resized to fit): pressing a
     * button sends its text.
     *
     * @param rows the button texts, row by row
     */
    record Keyboard(List<List<String>> rows) implements ReplyMarkup {

        public Keyboard {
            rows = rows.stream().map(List::copyOf).toList();
        }
    }

    /** Takes a keyboard sent earlier away ({@code ReplyKeyboardRemove}). */
    record RemoveKeyboard() implements ReplyMarkup {
    }
}
