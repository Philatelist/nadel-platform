package app.nadel.reports.internal;

import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.reports.internal.ReportResult.Total;
import app.nadel.treasury.AccountBook;
import app.nadel.treasury.CashBookDay;
import app.nadel.treasury.CategoryRef;
import app.nadel.treasury.MovementLine;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.ReportColumnType;
import org.jspecify.annotations.Nullable;

/**
 * The sheet {@code main} of an account's book ({@code cash-book}, {@code bank-movements}; feature 018 §2.12), from
 * {@code treasury.TreasuryReadModel}. Rows in order:
 * <ul>
 * <li>{@code OPENING} ({@code line}): the first day of the period with the balance at its start in {@code opening};</li>
 * <li>per day that has lines: each journal line (date, time entered in Europe/Kyiv {@code HH:mm}, kind, whether it is
 * a reversal, the receipt number of a cash payment or the document number, the plot of a member payment, the
 * counterparty of an expense, the category, the amount in {@code receipt} or {@code spending}, both positive), then
 * the line {@code DAY} with the day's balance at its start, its receipts, its spendings and its balance at its end
 * (the balance «Каса і банк» shows for the day);</li>
 * <li>{@code CLOSING}: the last day of the period with the balance at its end in {@code closing}.</li>
 * </ul>
 * A day without lines is left out. The totals line {@code all} sums {@code receipt} and {@code spending} over the
 * journal lines (the {@code DAY} lines repeat them and are not counted). Every row is a {@code DETAIL} row: the
 * {@code line} column names the balance rows.
 */
final class AccountBookSheet {

    static final String MAIN = "main";

    /** The catalog of the {@code line} column's codes. */
    static final String LINES = "reports.accountBook.lines";

    /** The catalog of the {@code kind} column's codes (the movement kinds of «Каса і банк»). */
    static final String KINDS = "cashbook.kind";

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static final List<Column> COLUMNS = List.of(
            Column.of("date", ReportColumnType.DATE),
            Column.codes("line", LINES),
            Column.of("time", ReportColumnType.TEXT),
            Column.codes("kind", KINDS),
            Column.of("reversal", ReportColumnType.BOOLEAN),
            Column.of("document", ReportColumnType.TEXT),
            Column.of("plot", ReportColumnType.TEXT),
            Column.of("counterparty", ReportColumnType.TEXT),
            Column.of("category", ReportColumnType.EXPENSE_CATEGORY),
            Column.of("opening", ReportColumnType.MONEY),
            Column.of("receipt", ReportColumnType.MONEY),
            Column.of("spending", ReportColumnType.MONEY),
            Column.of("closing", ReportColumnType.MONEY));

    private static final int RECEIPT = 10;
    private static final int SPENDING = 11;

    private AccountBookSheet() {
    }

    /** The sheet of the book; plot numbers come from {@code plots}. */
    static Sheet sheet(AccountBook book, PlotCatalog plots) {
        Set<UUID> plotIds = book.days().stream().flatMap(day -> day.lines().stream()).map(MovementLine::plotId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, PlotFacts> plotFacts = plotIds.isEmpty() ? Map.of() : plots.findAll(plotIds);

        List<Row> rows = new ArrayList<>();
        Money receipts = Money.ZERO;
        Money spendings = Money.ZERO;
        rows.add(balanceRow(book.from(), "OPENING", book.opening(), null, null, null));
        for (CashBookDay day : book.days()) {
            for (MovementLine line : day.lines()) {
                PlotFacts plot = line.plotId() == null ? null : plotFacts.get(line.plotId());
                boolean in = line.amount().signum() > 0;
                Cell[] cells = empty();
                cells[0] = Cell.of(line.date());
                cells[2] = Cell.of(TIME.format(line.createdAt().atZone(KYIV)));
                cells[3] = Cell.of(line.kind().name());
                cells[4] = Cell.of(line.reversal());
                cells[5] = Cell.of(document(line));
                cells[6] = Cell.of(plot == null ? null : plot.number());
                cells[7] = Cell.of(line.counterparty());
                cells[8] = Cell.of(line.categoryId());
                cells[RECEIPT] = in ? Cell.of(line.amount()) : Cell.EMPTY;
                cells[SPENDING] = in ? Cell.EMPTY : Cell.of(line.amount().negate());
                rows.add(Row.detail(Arrays.asList(cells)));
                if (in) {
                    receipts = receipts.plus(line.amount());
                } else {
                    spendings = spendings.minus(line.amount());
                }
            }
            rows.add(balanceRow(day.day(), "DAY", day.opening(), day.receipts(), day.spendings(), day.closing()));
        }
        rows.add(balanceRow(book.to(), "CLOSING", null, null, null, book.closing()));

        Cell[] total = empty();
        total[RECEIPT] = Cell.of(receipts);
        total[SPENDING] = Cell.of(spendings);
        return new Sheet(MAIN, COLUMNS, rows, List.of(new Total("all", Arrays.asList(total))));
    }

    /** The categories the book's lines name, in the order of {@code all}. */
    static List<CategoryRef> categories(AccountBook book, List<CategoryRef> all) {
        Set<UUID> used = book.days().stream().flatMap(day -> day.lines().stream()).map(MovementLine::categoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        return all.stream().filter(category -> used.contains(category.id())).toList();
    }

    private static Row balanceRow(LocalDate day, String line, @Nullable Money opening,
            @Nullable Money receipts, @Nullable Money spendings, @Nullable Money closing) {
        Cell[] cells = empty();
        cells[0] = Cell.of(day);
        cells[1] = Cell.of(line);
        cells[9] = Cell.of(opening);
        cells[RECEIPT] = Cell.of(receipts);
        cells[SPENDING] = Cell.of(spendings);
        cells[12] = Cell.of(closing);
        return Row.detail(Arrays.asList(cells));
    }

    /** The receipt number of a cash member payment, otherwise the document number (null when none). */
    private static @Nullable String document(MovementLine line) {
        return line.receiptNumber() != null ? line.receiptNumber().toString() : line.documentNumber();
    }

    private static Cell[] empty() {
        Cell[] cells = new Cell[COLUMNS.size()];
        Arrays.fill(cells, Cell.EMPTY);
        return cells;
    }
}
