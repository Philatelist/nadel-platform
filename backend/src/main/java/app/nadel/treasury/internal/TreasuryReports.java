package app.nadel.treasury.internal;

import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.PaymentRef;
import app.nadel.ledger.PaymentSplit;
import app.nadel.money.Money;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.tenancy.TenantContext;
import app.nadel.treasury.AccountBook;
import app.nadel.treasury.AccountBook.BankMonth;
import app.nadel.treasury.BankMonthStatus;
import app.nadel.treasury.CashBookDay;
import app.nadel.treasury.CategoryRef;
import app.nadel.treasury.ExpenseLine;
import app.nadel.treasury.IncomeExpense;
import app.nadel.treasury.MoneyAccountKind;
import app.nadel.treasury.MovementKind;
import app.nadel.treasury.MovementLine;
import app.nadel.treasury.TreasuryReadModel;
import app.nadel.treasury.internal.AccountBalances.DayTotal;
import app.nadel.treasury.internal.AccountBalances.Opening;
import app.nadel.treasury.internal.BankReconciliationQueries.MonthStatus;
import app.nadel.treasury.internal.IncomeExpenseCalculator.Balances;
import app.nadel.treasury.internal.IncomeExpenseCalculator.Line;
import app.nadel.treasury.internal.TreasuryQueries.AccountRow;
import app.nadel.treasury.internal.TreasuryReportQueries.BookLineRow;
import app.nadel.treasury.internal.TreasuryReportQueries.ExpenseReportRow;
import app.nadel.treasury.internal.TreasuryReportQueries.PeriodLineRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The treasury's read facade for the cash book reports ({@link TreasuryReadModel}, feature 018 §2.12, technical
 * considerations 2.7). Every method needs {@code CASHBOOK_VIEW} and the caller's transaction.
 * <p>
 * <b>A book</b> (cash or bank): the account's opening balance is required (409 {@code OPENING_BALANCE_MISSING}) and
 * the period starts on or after its date (400 {@code BEFORE_OPENING_BALANCE} on {@code from}); the balances are
 * {@link AccountBalances} over the day totals from the opening date ({@link TreasuryQueries#dayTotals}), so every
 * day's closing balance is the balance «Каса і банк» shows for that day; the lines are
 * {@link TreasuryReportQueries#bookLines} with the member payment references of {@link LedgerReadModel#paymentRefs}
 * and the expenses' counterparties opened ({@link TreasuryCipher}). The bank's months are every calendar month from the
 * month of {@code from} to the month of {@code to} with its reconciliation status ({@code NOT_RECONCILED} without a
 * row).
 * <p>
 * <b>The income and expense</b>: both opening balances are required (409 first for a missing one, then 400 for a
 * period that starts before either date); {@link IncomeExpenseCalculator} over every line of both accounts dated in
 * the period and the splits of the counted payments ({@link LedgerReadModel#paymentSplits}) as of the end of
 * {@code to} in Europe/Kyiv.
 */
@Component
class TreasuryReports implements TreasuryReadModel {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    private final TreasuryQueries accounts;
    private final TreasuryReportQueries queries;
    private final BankReconciliationQueries reconciliations;
    private final LedgerReadModel ledger;
    private final TreasuryCipher cipher;

    TreasuryReports(TreasuryQueries accounts, TreasuryReportQueries queries, BankReconciliationQueries reconciliations,
            LedgerReadModel ledger, TreasuryCipher cipher) {
        this.accounts = accounts;
        this.queries = queries;
        this.reconciliations = reconciliations;
        this.ledger = ledger;
        this.cipher = cipher;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_VIEW')")
    @Transactional(propagation = Propagation.MANDATORY)
    public AccountBook cashBook(LocalDate from, LocalDate to, int limit) {
        return book(MoneyAccountKind.CASH, from, to, limit);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_VIEW')")
    @Transactional(propagation = Propagation.MANDATORY)
    public AccountBook bankMovements(LocalDate from, LocalDate to, int limit) {
        return book(MoneyAccountKind.BANK, from, to, limit);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_VIEW')")
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ExpenseLine> expenses(LocalDate from, LocalDate to, @Nullable MoneyAccountKind account,
            Collection<UUID> categoryIds, int limit) {
        checkRange(from, to, limit);
        UUID tenantId = tenantId();
        List<ExpenseLine> lines = new ArrayList<>();
        for (ExpenseReportRow row : queries.expenses(from, to, account, Set.copyOf(categoryIds), limit)) {
            lines.add(new ExpenseLine(row.id(), row.date(), row.createdAt(), row.account(), row.categoryId(),
                    row.amount(), counterparty(tenantId, row.id(), row.counterpartyEnc()), row.documentNumber(),
                    row.documentDate(), row.reversed(), row.attachments()));
        }
        return lines;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_VIEW')")
    @Transactional(propagation = Propagation.MANDATORY)
    public IncomeExpense incomeExpense(LocalDate from, LocalDate to) {
        checkRange(from, to, 1);
        AccountRow cash = account(MoneyAccountKind.CASH);
        AccountRow bank = account(MoneyAccountKind.BANK);
        requireOpening(cash);
        requireOpening(bank);
        Opening cashOpening = requireOpening(cash, from);
        Opening bankOpening = requireOpening(bank, from);
        List<DayTotal> cashTotals = accounts.dayTotals(cash.id(), cashOpening.date(), to);
        List<DayTotal> bankTotals = accounts.dayTotals(bank.id(), bankOpening.date(), to);
        Balances balances = new Balances(balanceAt(cashOpening, cashTotals, from.minusDays(1)),
                balanceAt(cashOpening, cashTotals, to), balanceAt(bankOpening, bankTotals, from.minusDays(1)),
                balanceAt(bankOpening, bankTotals, to));

        List<Line> lines = new ArrayList<>();
        for (PeriodLineRow row : queries.periodLines(from, to)) {
            UUID paymentId = row.kind() != MovementKind.MEMBER_PAYMENT ? null
                    : row.reversal() ? row.originalPaymentRowId() : row.paymentRowId();
            lines.add(new Line(row.account(), row.kind(), row.amount(), paymentId, row.reversal(),
                    row.originalDate(), row.categoryId()));
        }
        Set<UUID> counted = IncomeExpenseCalculator.countedPayments(lines, from);
        Map<UUID, PaymentSplit> splits = counted.isEmpty() ? Map.of()
                : ledger.paymentSplits(counted, endOf(to));
        return IncomeExpenseCalculator.calculate(from, to, lines, splits, balances);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_VIEW')")
    @Transactional(propagation = Propagation.MANDATORY)
    public List<CategoryRef> categories() {
        return queries.categories();
    }

    /** The last moment of the day in Europe/Kyiv (a microsecond before the next day: the database's precision). */
    static Instant endOf(LocalDate day) {
        return day.plusDays(1).atStartOfDay(KYIV).toInstant().minus(1, ChronoUnit.MICROS);
    }

    private AccountBook book(MoneyAccountKind kind, LocalDate from, LocalDate to, int limit) {
        checkRange(from, to, limit);
        AccountRow account = account(kind);
        Opening opening = requireOpening(account, from);
        List<DayTotal> totals = accounts.dayTotals(account.id(), opening.date(), to);
        List<BookLineRow> rows = queries.bookLines(account.id(), from, to, limit);
        Set<UUID> paymentRows = rows.stream().map(BookLineRow::paymentRowId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, PaymentRef> refs = paymentRows.isEmpty() ? Map.of() : ledger.paymentRefs(paymentRows);
        UUID tenantId = tenantId();

        Map<LocalDate, List<MovementLine>> byDay = new LinkedHashMap<>();
        for (BookLineRow row : rows) {
            byDay.computeIfAbsent(row.movedOn(), day -> new ArrayList<>()).add(line(tenantId, row, refs));
        }
        List<CashBookDay> days = new ArrayList<>();
        byDay.forEach((day, lines) -> {
            Money receipts = Money.ZERO;
            Money spendings = Money.ZERO;
            for (MovementLine line : lines) {
                if (line.amount().signum() > 0) {
                    receipts = receipts.plus(line.amount());
                } else {
                    spendings = spendings.minus(line.amount());
                }
            }
            days.add(new CashBookDay(day, balanceAt(opening, totals, day.minusDays(1)), lines, receipts, spendings,
                    balanceAt(opening, totals, day)));
        });
        return new AccountBook(kind, from, to, balanceAt(opening, totals, from.minusDays(1)),
                balanceAt(opening, totals, to), days, kind == MoneyAccountKind.BANK ? months(from, to) : List.of());
    }

    private MovementLine line(UUID tenantId, BookLineRow row, Map<UUID, PaymentRef> refs) {
        PaymentRef ref = row.paymentRowId() == null ? null : refs.get(row.paymentRowId());
        String document = ref != null ? ref.bankReference()
                : row.handoverDocument() != null ? row.handoverDocument() : row.expenseDocument();
        String counterparty = row.expenseId() == null ? null
                : counterparty(tenantId, row.expenseId(), row.counterpartyEnc());
        return new MovementLine(row.id(), row.movedOn(), row.createdAt(), row.kind(), row.reversal(), row.amount(),
                ref == null ? null : ref.receiptNumber(), document, ref == null ? null : ref.plotId(), counterparty,
                row.categoryId());
    }

    /** Every calendar month from the month of {@code from} to the month of {@code to}, oldest first. */
    private List<BankMonth> months(LocalDate from, LocalDate to) {
        YearMonth first = YearMonth.from(from);
        YearMonth last = YearMonth.from(to);
        int count = (int) first.until(last, ChronoUnit.MONTHS) + 1;
        Map<YearMonth, BankMonthStatus> statuses = new HashMap<>();
        for (MonthStatus status : reconciliations.statuses(first, last, count)) {
            statuses.put(status.month(), status.status());
        }
        List<BankMonth> months = new ArrayList<>();
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            months.add(new BankMonth(month, statuses.getOrDefault(month, BankMonthStatus.NOT_RECONCILED)));
        }
        return months;
    }

    private @Nullable String counterparty(UUID tenantId, UUID expenseId, byte @Nullable [] sealed) {
        return cipher.open(tenantId, TreasuryCipher.ref(TreasuryCipher.EXPENSE, TreasuryCipher.COUNTERPARTY,
                expenseId), sealed);
    }

    private AccountRow account(MoneyAccountKind kind) {
        return accounts.accounts().stream().filter(account -> account.kind() == kind).findFirst()
                .orElseThrow(() -> new IllegalStateException("The cooperative has no " + kind + " account"));
    }

    /** 409 {@code OPENING_BALANCE_MISSING} without an opening balance. */
    private static Opening requireOpening(AccountRow account) {
        Opening opening = account.opening();
        if (opening == null) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.OPENING_BALANCE_MISSING,
                    "The account has no opening balance yet.");
        }
        return opening;
    }

    /** As {@link #requireOpening(AccountRow)}, and 400 {@code BEFORE_OPENING_BALANCE} for an earlier {@code from}. */
    private static Opening requireOpening(AccountRow account, LocalDate from) {
        Opening opening = requireOpening(account);
        if (from.isBefore(opening.date())) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.BEFORE_OPENING_BALANCE,
                    "The period starts before the opening balance of the account.",
                    List.of(new FieldProblem("from", ProblemCodes.BEFORE_OPENING_BALANCE)));
        }
        return opening;
    }

    /** A balance from the opening date on: always known (the period starts on or after the opening date). */
    private static Money balanceAt(Opening opening, List<DayTotal> totals, LocalDate day) {
        return Objects.requireNonNull(AccountBalances.balanceAt(opening, totals, day), "balance");
    }

    private static void checkRange(LocalDate from, LocalDate to, int limit) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to is before from");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit is below 1");
        }
    }

    private static UUID tenantId() {
        return TenantContext.currentTenantId().orElseThrow(() -> new IllegalStateException("No cooperative is bound"));
    }
}
