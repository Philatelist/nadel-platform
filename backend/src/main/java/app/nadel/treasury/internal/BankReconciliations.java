package app.nadel.treasury.internal;

import app.nadel.audit.ActorNames;
import app.nadel.identity.AccessProfile;
import app.nadel.ledger.BookingMonths;
import app.nadel.money.Money;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.treasury.BankMonthStatus;
import app.nadel.treasury.MoneyAccountKind;
import app.nadel.treasury.MovementKind;
import app.nadel.treasury.internal.AccountBalances.Opening;
import app.nadel.treasury.internal.BankReconciliationQueries.MonthStatus;
import app.nadel.treasury.internal.BankReconciliationQueries.NewReconciliation;
import app.nadel.treasury.internal.BankReconciliationQueries.ReconciliationRow;
import app.nadel.treasury.internal.StatementRules.Figures;
import app.nadel.treasury.internal.TreasuryQueries.AccountRow;
import app.nadel.treasury.internal.TreasuryQueries.MovementRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import nadel.contract.model.BankDifferenceInput;
import nadel.contract.model.BankFigures;
import nadel.contract.model.BankMonth;
import nadel.contract.model.BankReconciliation;
import nadel.contract.model.BankReopening;
import nadel.contract.model.BankStatementInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The monthly bank reconciliation («Звірка з банком», feature 018 §2.7, §2.11, §2.14; technical considerations 2.5,
 * 2.9, 2.10): the comparison of a calendar month, the statement, the bank difference, the confirmation, and the month
 * statuses of the bank card. The books come from {@link BankComparison}, the statement rules from
 * {@link StatementRules}; the server computes every amount. The caller holds the permission (checked on
 * {@code TreasuryController}); the cooperative is the one bound to the transaction.
 * <p>
 * <b>Every request</b>, in this order (the first refusal answers, nothing is stored): the month ({@code YYYY-MM}, not
 * after the current month in Europe/Kyiv, else 400 {@code VALIDATION_FAILED} {@code month} {@code INVALID}); the
 * request's fields; on a write the bank account locked {@code FOR UPDATE} (a bank difference first books its billing
 * month, {@link BookingMonths#bookFor}: the treasury lock order is billing month → cash → bank →
 * {@code bank_reconciliation}); 409 {@code OPENING_BALANCE_MISSING} without the bank's opening balance, 400
 * {@code BEFORE_OPENING_BALANCE} ({@code month}) for a month that ends before the opening date. A write under the bank
 * lock sees every bank movement committed before it, and no bank movement (whose writer takes the same lock) commits
 * between its reading of the books and its commit, so a confirmation never misses a line and a line entered after it
 * always reopens the month ({@link MovementWriter}).
 * <p>
 * <b>Statement</b> ({@link #enterStatement}): 400 from {@link StatementRules}; the first statement of a month creates
 * its row ({@code NOT_RECONCILED}); a later one replaces the figures while {@code NOT_RECONCILED} or
 * {@code NEEDS_RECHECK}; a {@code RECONCILED} month is 409 {@code BANK_MONTH_RECONCILED}. Audit
 * {@code BANK_STATEMENT_ENTERED}.
 * <p>
 * <b>Bank difference</b> ({@link #bookDifference}): a signed amount other than 0.00 with at most 12 integer digits
 * ({@code amount} {@code INVALID}) and a reason ({@code REQUIRED}); 409 {@code BANK_STATEMENT_MISSING} before the
 * statement, {@code BANK_MONTH_RECONCILED} for a reconciled month; a {@code BANK_DIFFERENCE} line on the bank (source
 * {@code BANK_RECONCILIATION}, the month's row) dated the month's last day or today when earlier, in the billing month
 * {@code bookFor} gives, its reason in {@code bank_difference}. The bank is never guarded (it may go negative). Audit
 * {@code BANK_DIFFERENCE_BOOKED}. A booked line is reversed through the journal ({@link CashCounts#reverseMovement}).
 * <p>
 * <b>Confirmation</b> ({@link #confirm}): the books computed under the lock; 409 {@code BANK_MONTH_NOT_BALANCED}
 * without a statement or while the closing difference is not 0.00 (the opening, incoming and outgoing differences do
 * not block, {@link BankComparison#balanced}), {@code BANK_MONTH_RECONCILED} when already reconciled;
 * {@code NOT_RECONCILED} or {@code NEEDS_RECHECK} → {@code RECONCILED} with who and when (the reopening stays as the
 * month's last one). Audit {@code BANK_MONTH_RECONCILED}. Logs ids only.
 */
@Service
class BankReconciliations {

    private static final Logger log = LoggerFactory.getLogger(BankReconciliations.class);

    /** The most bank lines a month lists. */
    static final int MOVEMENT_LIMIT = 1000;
    /** The most months the bank card lists. */
    static final int MONTHS_LIMIT = 12;
    private static final String SOURCE = "BANK_RECONCILIATION";

    private final TreasuryQueries accounts;
    private final BankReconciliationQueries reconciliations;
    private final MovementWriter movements;
    private final BookingMonths bookingMonths;
    private final JournalLines journalLines;
    private final TreasuryAuditPublisher audit;
    private final ActorNames actorNames;
    private final Clock clock;

    BankReconciliations(TreasuryQueries accounts, BankReconciliationQueries reconciliations, MovementWriter movements,
            BookingMonths bookingMonths, JournalLines journalLines, TreasuryAuditPublisher audit,
            ActorNames actorNames, Clock clock) {
        this.accounts = accounts;
        this.reconciliations = reconciliations;
        this.movements = movements;
        this.bookingMonths = bookingMonths;
        this.journalLines = journalLines;
        this.audit = audit;
        this.actorNames = actorNames;
        this.clock = clock;
    }

    /** The reconciliation of the month as it stands. */
    @Transactional(readOnly = true)
    BankReconciliation get(String monthText) {
        YearMonth month = month(monthText);
        AccountRow bank = accounts.accounts().stream().filter(account -> account.kind() == MoneyAccountKind.BANK)
                .findFirst().orElseThrow(() -> new IllegalStateException("The cooperative has no BANK account"));
        return view(month, bank, opening(bank, month));
    }

    /** Stores the month's statement figures (the first time, the month's row). */
    @Transactional
    BankReconciliation enterStatement(String monthText, BankStatementInput input, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        UUID memberId = Objects.requireNonNull(actor.memberId(), "memberId");
        YearMonth month = month(monthText);
        Figures statement = new Figures(Money.parse(input.getOpening()), Money.parse(input.getIncoming()),
                Money.parse(input.getOutgoing()), Money.parse(input.getClosing()));
        StatementRules.check(statement);
        AccountRow bank = lockBank();
        Opening opening = opening(bank, month);
        ReconciliationRow row = reconciliations.find(month).orElse(null);
        Instant now = clock.instant();
        UUID id;
        if (row == null) {
            id = UUID.randomUUID();
            reconciliations.insert(new NewReconciliation(id, tenantId, month, statement, memberId, now));
        } else {
            if (row.status() == BankMonthStatus.RECONCILED) {
                throw reconciled();
            }
            id = row.id();
            reconciliations.updateStatement(id, statement);
        }
        audit.bankStatementEntered(actor, id, month, row == null ? null : row.statement(), statement, now);
        log.debug("Bank statement of reconciliation {} entered", id);
        return view(month, bank, opening);
    }

    /** Books a bank difference of the month, dated its last day or today when earlier. */
    @Transactional
    BankReconciliation bookDifference(String monthText, BankDifferenceInput input, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        UUID memberId = Objects.requireNonNull(actor.memberId(), "memberId");
        YearMonth month = month(monthText);
        Money amount = Money.parse(input.getAmount());
        String reason = EntryChecks.blankToNull(input.getReason());
        List<FieldProblem> errors = new ArrayList<>();
        if (amount.signum() == 0 || MoneyAccounts.integerDigits(amount) > EntryChecks.MAX_INTEGER_DIGITS) {
            errors.add(new FieldProblem("amount", ProblemCodes.INVALID));
        }
        if (reason == null) {
            errors.add(new FieldProblem("reason", ProblemCodes.REQUIRED));
        }
        EntryChecks.refuse(errors);
        LocalDate today = LocalDate.now(clock);
        LocalDate date = month.atEndOfMonth().isAfter(today) ? today : month.atEndOfMonth();
        YearMonth bookingMonth = bookingMonths.bookFor(date);
        AccountRow bank = lockBank();
        Opening opening = opening(bank, month);
        ReconciliationRow row = reconciliations.find(month).orElseThrow(() -> new ApiProblemException(
                HttpStatus.CONFLICT, ProblemCodes.BANK_STATEMENT_MISSING,
                "The bank statement of the month is not entered yet."));
        if (row.status() == BankMonthStatus.RECONCILED) {
            throw reconciled();
        }
        Instant now = clock.instant();
        UUID movement = movements.writeEntry(tenantId, bank.id(), MovementKind.BANK_DIFFERENCE, amount, date,
                bookingMonth, SOURCE, row.id(), memberId, now);
        reconciliations.insertDifference(tenantId, row.id(), movement, Objects.requireNonNull(reason), memberId, now);
        audit.bankDifferenceBooked(actor, row.id(), month, amount, date, reason, now);
        log.debug("Bank difference of reconciliation {} booked as movement {}", row.id(), movement);
        return view(month, bank, opening);
    }

    /** Marks the month reconciled when the statement's closing balance agrees with the books. */
    @Transactional
    BankReconciliation confirm(String monthText, AccessProfile actor) {
        UUID memberId = Objects.requireNonNull(actor.memberId(), "memberId");
        YearMonth month = month(monthText);
        AccountRow bank = lockBank();
        Opening opening = opening(bank, month);
        ReconciliationRow row = reconciliations.find(month).orElseThrow(BankReconciliations::notBalanced);
        if (row.status() == BankMonthStatus.RECONCILED) {
            throw reconciled();
        }
        Figures books = books(month, bank, opening);
        if (!BankComparison.balanced(BankComparison.differences(row.statement(), books))) {
            throw notBalanced();
        }
        Instant now = clock.instant();
        reconciliations.confirm(row.id(), memberId, now);
        audit.bankMonthReconciled(actor, row.id(), month, row.status(), row.statement(), now);
        log.debug("Bank reconciliation {} confirmed", row.id());
        return view(month, bank, opening);
    }

    /**
     * The months of the bank card, newest first, at most {@link #MONTHS_LIMIT}: from the month of the opening date to
     * the month of {@code day}, every month with a reconciliation or with bank lines dated from the opening date on,
     * and the month of {@code day} itself; empty for the cash and without an opening balance.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<BankMonth> months(AccountRow account, LocalDate day) {
        Opening opening = account.opening();
        if (account.kind() != MoneyAccountKind.BANK || opening == null) {
            return List.of();
        }
        YearMonth from = YearMonth.from(opening.date());
        YearMonth to = YearMonth.from(day);
        if (to.isBefore(from)) {
            return List.of();
        }
        Map<YearMonth, BankMonthStatus> statuses = new HashMap<>();
        for (MonthStatus status : reconciliations.statuses(from, to, MONTHS_LIMIT)) {
            statuses.put(status.month(), status.status());
        }
        TreeSet<YearMonth> months = new TreeSet<>(Comparator.reverseOrder());
        months.add(to);
        months.addAll(statuses.keySet());
        months.addAll(reconciliations.movementMonths(account.id(), opening.date(), to.atEndOfMonth(), MONTHS_LIMIT));
        return months.stream().limit(MONTHS_LIMIT)
                .map(month -> new BankMonth(month.toString(), contract(
                        statuses.getOrDefault(month, BankMonthStatus.NOT_RECONCILED))))
                .toList();
    }

    /** The contract form of the month. */
    private BankReconciliation view(YearMonth month, AccountRow bank, Opening opening) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        Figures books = books(month, bank, opening);
        ReconciliationRow row = reconciliations.find(month).orElse(null);
        Figures differences = row == null ? null : BankComparison.differences(row.statement(), books);
        List<MovementRow> rows = accounts.movements(bank.id(), first, last, 0, MOVEMENT_LIMIT);
        long total = rows.size() < MOVEMENT_LIMIT ? rows.size() : accounts.countMovements(bank.id(), first, last);
        BankReconciliation view = new BankReconciliation(month.toString(), bank.id(),
                contract(row == null ? BankMonthStatus.NOT_RECONCILED : row.status()), contract(books),
                BankComparison.balanced(differences), journalLines.items(rows, opening), total);
        if (row == null) {
            return view;
        }
        view.setStatement(contract(row.statement()));
        view.setDifferences(contract(Objects.requireNonNull(differences)));
        if (row.reconciledBy() != null) {
            view.setReconciledByName(actorNames.displayNames(List.of(row.reconciledBy())).get(row.reconciledBy()));
        }
        if (row.reconciledAt() != null) {
            view.setReconciledAt(row.reconciledAt().atOffset(ZoneOffset.UTC));
        }
        if (row.reopenedMovementId() != null && row.reopenedAt() != null) {
            accounts.movement(row.reopenedMovementId()).ifPresent(line -> view.setReopened(new BankReopening(
                    line.id(), nadel.contract.model.MovementKind.fromValue(line.kind().name()), line.movedOn(),
                    line.amount().toString(), row.reopenedAt().atOffset(ZoneOffset.UTC))));
        }
        return view;
    }

    private Figures books(YearMonth month, AccountRow bank, Opening opening) {
        return BankComparison.books(opening, month,
                reconciliations.flows(bank.id(), opening.date(), month.atEndOfMonth()));
    }

    private AccountRow lockBank() {
        return accounts.lockAccountOfKind(MoneyAccountKind.BANK)
                .orElseThrow(() -> new IllegalStateException("The cooperative has no BANK account"));
    }

    /** The month of the path; 400 {@code month} {@code INVALID} when malformed or after the current month. */
    private YearMonth month(String text) {
        YearMonth month;
        try {
            month = YearMonth.parse(text);
        } catch (DateTimeParseException e) {
            throw invalidMonth();
        }
        if (month.isAfter(YearMonth.now(clock))) {
            throw invalidMonth();
        }
        return month;
    }

    /** The bank's opening balance; 409 without it, 400 for a month that ends before it. */
    private static Opening opening(AccountRow bank, YearMonth month) {
        return EntryChecks.requireOpening(bank, month.atEndOfMonth(), "month");
    }

    private static ApiProblemException invalidMonth() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                "The request is not valid.", List.of(new FieldProblem("month", ProblemCodes.INVALID)));
    }

    private static ApiProblemException reconciled() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.BANK_MONTH_RECONCILED,
                "The bank month is reconciled.");
    }

    private static ApiProblemException notBalanced() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.BANK_MONTH_NOT_BALANCED,
                "The bank statement of the month is missing or its closing balance differs from the books.");
    }

    private static BankFigures contract(Figures figures) {
        return new BankFigures(figures.opening().toString(), figures.incoming().toString(),
                figures.outgoing().toString(), figures.closing().toString());
    }

    private static nadel.contract.model.BankMonthStatus contract(BankMonthStatus status) {
        return nadel.contract.model.BankMonthStatus.fromValue(status.name());
    }
}
