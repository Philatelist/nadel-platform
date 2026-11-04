package app.nadel.reports.internal;

import app.nadel.identity.Permission;
import app.nadel.money.Money;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.reports.internal.ReportResult.Total;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import app.nadel.treasury.CategoryRef;
import app.nadel.treasury.IncomeExpense;
import app.nadel.treasury.MoneyAccountKind;
import app.nadel.treasury.MovementKind;
import app.nadel.treasury.TreasuryReadModel;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportColumnType;
import nadel.contract.model.ReportPeriodBasis;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * Income and expense («Доходи та витрати», {@code income-expense}, feature 018 §2.12, example 12) for the general
 * meeting, from {@code treasury.TreasuryReadModel.incomeExpense}. Needs {@code CASHBOOK_VIEW} only; no personal data.
 * Filter: the period (days, basis {@code MOVEMENT_DATES}, default the previous month); both opening balances are
 * required (409 {@code OPENING_BALANCE_MISSING}) and the period starts on or after both dates (400
 * {@code BEFORE_OPENING_BALANCE} on {@code from}).
 * <p>
 * Sheet {@code main}, the {@link #lines} in order: {@code section} ({@code CODE},
 * {@code reports.income-expense.sections}) {@code MEMBER_BY_TYPE} one row per charge type (configured order, a type
 * netting to 0.00 left out), {@code ADVANCES}, {@code PAYMENT_REVERSALS} (zero or negative), {@code OTHER_INCOME} one
 * row per income category, {@code EXPENSE} one row per expense category (positive), {@code DIFFERENCES} a row for
 * {@code COUNT_DIFFERENCE} and one for {@code BANK_DIFFERENCE} ({@code difference}, catalog {@code cashbook.kind});
 * {@code chargeType} and {@code category} name the line, {@code amount} its amount. Totals: {@code income} (the sum of
 * the income rows), {@code expenses} (of the expense rows), {@code differences} (of the difference rows) and
 * {@code result} = income − expenses + differences, which equals the change of cash plus bank.
 * <p>
 * Sheet {@code balances}: one row per account ({@code account}, catalog {@code reports.filters.options.account}) with
 * {@code start} (the balance at the start of {@code from}), {@code end} (at the end of {@code to}) and {@code change};
 * the totals line {@code all} sums them, so its {@code change} is the result.
 */
@Component
class IncomeExpenseReport implements ReportDefinition {

    static final String MAIN = "main";
    static final String BALANCES = "balances";

    /** The catalog of the {@code section} column's codes. */
    static final String SECTIONS = "reports.income-expense.sections";

    /** The sections of the income: their rows add up to {@code income}. */
    private static final Set<Section> INCOME = Set.of(Section.MEMBER_BY_TYPE, Section.ADVANCES,
            Section.PAYMENT_REVERSALS, Section.OTHER_INCOME);

    private static final List<Column> COLUMNS = List.of(
            Column.codes("section", SECTIONS),
            Column.of("chargeType", ReportColumnType.CHARGE_TYPE),
            Column.of("category", ReportColumnType.EXPENSE_CATEGORY),
            Column.codes("difference", AccountBookSheet.KINDS),
            Column.of("amount", ReportColumnType.MONEY));

    private static final List<Column> BALANCE_COLUMNS = List.of(
            Column.codes("account", "reports.filters.options.account"),
            Column.of("start", ReportColumnType.MONEY),
            Column.of("end", ReportColumnType.MONEY),
            Column.of("change", ReportColumnType.MONEY));

    /** The sections of the sheet {@code main}, in order. */
    enum Section {
        MEMBER_BY_TYPE, ADVANCES, PAYMENT_REVERSALS, OTHER_INCOME, EXPENSE, DIFFERENCES
    }

    /**
     * One line of the income and expense: a charge type, a category or a kind of difference names it (none for the
     * advances and the payment reversals).
     */
    record Line(Section section, @Nullable UUID chargeTypeId, @Nullable UUID categoryId,
            @Nullable MovementKind difference, Money amount) {

        Line {
            Objects.requireNonNull(section, "section");
            Objects.requireNonNull(amount, "amount");
        }
    }

    private final TreasuryReadModel treasury;

    IncomeExpenseReport(TreasuryReadModel treasury) {
        this.treasury = treasury;
    }

    @Override
    public ReportCode code() {
        return ReportCode.INCOME_EXPENSE;
    }

    @Override
    public Set<Permission> requiredPermissions() {
        return Set.of(Permission.CASHBOOK_VIEW);
    }

    @Override
    public List<ReportFilterDefinition> filters() {
        return CashBookReport.FILTERS;
    }

    /** Defence in depth: the runner has already refused a caller without {@code CASHBOOK_VIEW} (403). */
    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_VIEW')")
    public ReportResult load(ReportFilters filters, ReportContext context) {
        List<CategoryRef> categories = treasury.categories();
        IncomeExpense figures = treasury.incomeExpense(filters.from(), filters.to());
        List<Line> lines = lines(figures, context.chargeTypes(), categories);

        List<Row> rows = new ArrayList<>();
        for (Line line : lines) {
            rows.add(Row.detail(List.of(Cell.of(line.section().name()), Cell.of(line.chargeTypeId()),
                    Cell.of(line.categoryId()), Cell.of(line.difference() == null ? null : line.difference().name()),
                    Cell.of(line.amount()))));
        }
        Money income = sum(lines, INCOME);
        Money expenses = sum(lines, Set.of(Section.EXPENSE));
        Money differences = sum(lines, Set.of(Section.DIFFERENCES));
        List<Total> totals = List.of(total("income", income), total("expenses", expenses),
                total("differences", differences), total("result", income.minus(expenses).plus(differences)));

        List<Row> balances = List.of(
                balance(MoneyAccountKind.CASH, figures.cashStart(), figures.cashEnd()),
                balance(MoneyAccountKind.BANK, figures.bankStart(), figures.bankEnd()));
        Money start = figures.cashStart().plus(figures.bankStart());
        Money end = figures.cashEnd().plus(figures.bankEnd());
        Total all = new Total("all", List.of(Cell.EMPTY, Cell.of(start), Cell.of(end), Cell.of(end.minus(start))));

        Set<UUID> used = new LinkedHashSet<>();
        lines.stream().map(Line::categoryId).filter(Objects::nonNull).forEach(used::add);
        return new ReportResult(List.of(new Sheet(MAIN, COLUMNS, rows, totals),
                new Sheet(BALANCES, BALANCE_COLUMNS, balances, List.of(all))), List.of(),
                categories.stream().filter(category -> used.contains(category.id())).toList());
    }

    /** The figures of a period ({@link TemplateValues}: {@code totals.cashbook.*} and the row set). */
    IncomeExpense figures(LocalDate from, LocalDate to) {
        return treasury.incomeExpense(from, to);
    }

    /** Every category of the cooperative, to name the lines. */
    List<CategoryRef> categories() {
        return treasury.categories();
    }

    /**
     * The lines of the sheet {@code main} in order: the charge types in their configured order, the categories in
     * theirs (a type or category the lists do not know comes last), the advances, the payment reversals and both
     * kinds of difference always.
     */
    static List<Line> lines(IncomeExpense figures, List<ChargeTypeFacts> chargeTypes, List<CategoryRef> categories) {
        List<Line> lines = new ArrayList<>();
        for (UUID type : ordered(figures.memberByChargeType(), chargeTypes.stream().map(ChargeTypeFacts::id)
                .toList())) {
            lines.add(new Line(Section.MEMBER_BY_TYPE, type, null, null, figures.memberByChargeType().get(type)));
        }
        lines.add(new Line(Section.ADVANCES, null, null, null, figures.advances()));
        lines.add(new Line(Section.PAYMENT_REVERSALS, null, null, null, figures.paymentReversals()));
        List<UUID> categoryOrder = categories.stream().map(CategoryRef::id).toList();
        for (UUID category : ordered(figures.otherIncomeByCategory(), categoryOrder)) {
            lines.add(new Line(Section.OTHER_INCOME, null, category, null,
                    figures.otherIncomeByCategory().get(category)));
        }
        for (UUID category : ordered(figures.expensesByCategory(), categoryOrder)) {
            lines.add(new Line(Section.EXPENSE, null, category, null, figures.expensesByCategory().get(category)));
        }
        lines.add(new Line(Section.DIFFERENCES, null, null, MovementKind.COUNT_DIFFERENCE,
                figures.countDifferences()));
        lines.add(new Line(Section.DIFFERENCES, null, null, MovementKind.BANK_DIFFERENCE,
                figures.bankDifferences()));
        return lines;
    }

    /** The keys of {@code amounts} in the order of {@code order}, any other key after them. */
    private static List<UUID> ordered(Map<UUID, Money> amounts, List<UUID> order) {
        Set<UUID> keys = new LinkedHashSet<>();
        order.stream().filter(amounts::containsKey).forEach(keys::add);
        keys.addAll(amounts.keySet());
        return List.copyOf(keys);
    }

    private static Money sum(List<Line> lines, Set<Section> sections) {
        return lines.stream().filter(line -> sections.contains(line.section())).map(Line::amount)
                .reduce(Money.ZERO, Money::plus);
    }

    private static Total total(String key, Money amount) {
        return new Total(key, List.of(Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.of(amount)));
    }

    private static Row balance(MoneyAccountKind account, Money start, Money end) {
        return Row.detail(List.of(Cell.of(account.name()), Cell.of(start), Cell.of(end), Cell.of(end.minus(start))));
    }
}
