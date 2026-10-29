package app.nadel.documents;

import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who gets a paper copy of a plot's invoice (feature 017 §2.2, §2.3): an SPI the documents module calls for the print
 * set ({@link DocumentsFacade#printPdf}, {@code GET /billing-months/{month}/invoices/print.pdf}, scope
 * {@link PrintScope#PAPER}), implemented by the notifications module, which knows each owner's channel, Telegram link
 * and the cooperative's bot. The print set then holds one copy per entry, each with its recipient's name and, when
 * given, the small QR code «Отримувати рахунки в Telegram».
 * <p>
 * Without an implementation the print set keeps one copy per plot with at least one current owner who chose paper
 * (feature 016). Called in the print's transaction, bound to the cooperative; it may write (the notifications module
 * creates a one-time link code per copy, so the print PDF has that side effect).
 */
public interface PaperRecipients {

    /**
     * The paper copies of each given plot's invoice of the month: every plot id is a key, a plot nobody gets on paper
     * has an empty list.
     */
    Map<UUID, List<PaperCopy>> copiesFor(YearMonth month, Collection<UUID> plotIds);
}
