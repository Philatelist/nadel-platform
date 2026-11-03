package app.nadel.treasury.internal;

import app.nadel.audit.ActorNames;
import app.nadel.identity.AccessProfile;
import app.nadel.ledger.BookingMonths;
import app.nadel.money.Money;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.treasury.MoneyAccountKind;
import app.nadel.treasury.MovementKind;
import app.nadel.treasury.internal.AccountBalances.Opening;
import app.nadel.treasury.internal.Categories.CategoryRow;
import app.nadel.treasury.internal.ExpenseQueries.ExpenseRow;
import app.nadel.treasury.internal.ExpenseQueries.Filter;
import app.nadel.treasury.internal.ExpenseQueries.NewExpense;
import app.nadel.treasury.internal.ExpenseQueries.SummaryRow;
import app.nadel.treasury.internal.TreasuryAuditPublisher.ExpenseFacts;
import app.nadel.treasury.internal.TreasuryQueries.AccountRow;
import jakarta.persistence.PersistenceException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.AttachmentRef;
import nadel.contract.model.Expense;
import nadel.contract.model.ExpenseAttachment;
import nadel.contract.model.ExpenseInput;
import nadel.contract.model.ExpensePage;
import nadel.contract.model.ExpenseReversalInfo;
import nadel.contract.model.ExpenseSummary;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Expenses («Витрати», feature 018 §2.3, §2.8; technical considerations 2.5, 2.6): record with receipt files, list,
 * card, download a file, reverse. The caller holds the permission (checked on {@code TreasuryController}); the
 * cooperative is the one bound to the transaction.
 * <p>
 * <b>Recording</b>, in this order (the first refusal answers, nothing is stored): the fields (400
 * {@code VALIDATION_FAILED}: {@code amount} {@code AMOUNT_NOT_POSITIVE}/{@code INVALID}, {@code date}
 * {@code DATE_IN_FUTURE}, {@code categoryId} {@code INVALID} (unknown or an income category) or {@code ARCHIVED},
 * {@code reuseAttachments[i]} {@code INVALID}); the files ({@link AttachmentUploads}: {@code TOO_MANY},
 * {@code FILE_TOO_LARGE}, {@code FILE_TYPE_NOT_ALLOWED}), all before any lock; then the billing month
 * ({@link BookingMonths#bookFor}: a closed month books in the earliest open one, the date is kept, the month stays
 * locked {@code FOR SHARE}), then the account row {@code FOR UPDATE} (the treasury lock order: month, cash, bank), then
 * 409 {@code OPENING_BALANCE_MISSING}, 400 {@code BEFORE_OPENING_BALANCE} and, for the cash, {@link CashGuard} over the
 * day totals read under that lock (409 {@code CASH_INSUFFICIENT}). The movement ({@code EXPENSE}, the negated amount,
 * source {@code EXPENSE}), the expense row with the counterparty and description sealed ({@link TreasuryCipher}), the
 * files (reused ones copied inside the database first, then the uploads) and the audit entry {@code EXPENSE_RECORDED}
 * are written in the one transaction.
 * <p>
 * <b>Reversing</b>: a blank reason is 400 {@code reason} {@code REQUIRED}; the billing month of today, then the account
 * locked, then 409 {@code ENTRY_ALREADY_REVERSED} (the unique keys on the reversal are the backstop); a reversal row of
 * the same amount dated today pointing at the expense with the reason, its movement the negated movement of the
 * expense, and the audit entry {@code EXPENSE_REVERSED}. A reversal gives money back, so it is never refused for the
 * balance. Logs ids only.
 */
@Service
class Expenses {

    private static final Logger log = LoggerFactory.getLogger(Expenses.class);

    /** {@code numeric(14,2)}: twelve digits before the point. */
    private static final int MAX_INTEGER_DIGITS = 12;
    /** The upper end of the day totals the cash rule reads: every movement from the opening date on. */
    private static final LocalDate LAST_DAY = LocalDate.of(9999, 12, 31);
    private static final String SOURCE = "EXPENSE";
    private static final String EXPENSE_CATEGORY = "EXPENSE";
    private static final Set<String> REVERSED_ONCE = Set.of("expense_reversed_once_uq",
            "account_movement_reversed_once_uq");

    private final TreasuryQueries accounts;
    private final ExpenseQueries expenses;
    private final ExpenseAttachmentRepository attachments;
    private final ExpenseAttachmentContentRepository files;
    private final Categories categories;
    private final MovementWriter movements;
    private final BookingMonths bookingMonths;
    private final TreasuryCipher cipher;
    private final TreasuryAuditPublisher audit;
    private final ActorNames actorNames;
    private final Clock clock;

    Expenses(TreasuryQueries accounts, ExpenseQueries expenses, ExpenseAttachmentRepository attachments,
            ExpenseAttachmentContentRepository files, Categories categories, MovementWriter movements,
            BookingMonths bookingMonths, TreasuryCipher cipher, TreasuryAuditPublisher audit, ActorNames actorNames,
            Clock clock) {
        this.accounts = accounts;
        this.expenses = expenses;
        this.attachments = attachments;
        this.files = files;
        this.categories = categories;
        this.movements = movements;
        this.bookingMonths = bookingMonths;
        this.cipher = cipher;
        this.audit = audit;
        this.actorNames = actorNames;
        this.clock = clock;
    }

    /** A stored file to download. */
    record AttachmentFile(String fileName, String contentType, byte[] content) {
    }

    @Transactional
    Expense create(ExpenseInput input, @Nullable List<MultipartFile> uploads, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        UUID memberId = Objects.requireNonNull(actor.memberId(), "memberId");
        LocalDate today = LocalDate.now(clock);
        List<AttachmentRef> reuse = input.getReuseAttachments() == null ? List.of() : input.getReuseAttachments();
        List<MultipartFile> uploaded = uploads == null ? List.of() : uploads;

        Money amount = Money.parse(input.getAmount());
        List<FieldProblem> errors = new ArrayList<>();
        if (amount.signum() <= 0) {
            errors.add(new FieldProblem("amount", ProblemCodes.AMOUNT_NOT_POSITIVE));
        } else if (MoneyAccounts.integerDigits(amount) > MAX_INTEGER_DIGITS) {
            errors.add(new FieldProblem("amount", ProblemCodes.INVALID));
        }
        if (input.getDate().isAfter(today)) {
            errors.add(new FieldProblem("date", ProblemCodes.DATE_IN_FUTURE));
        }
        CategoryRow category = categories.find(input.getCategoryId()).orElse(null);
        if (category == null || !EXPENSE_CATEGORY.equals(category.kind())) {
            errors.add(new FieldProblem("categoryId", ProblemCodes.INVALID));
        } else if (category.archived()) {
            errors.add(new FieldProblem("categoryId", ProblemCodes.ARCHIVED));
        }
        for (int i = 0; i < reuse.size(); i++) {
            AttachmentRef ref = reuse.get(i);
            if (!attachments.exists(ref.getExpenseId(), ref.getN().shortValue())) {
                errors.add(new FieldProblem("reuseAttachments[" + i + "]", ProblemCodes.INVALID));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", errors);
        }
        AttachmentUploads.requireCount(reuse.size(), uploaded.size());
        List<AttachmentUploads.Prepared> prepared = AttachmentUploads.prepare(uploaded);

        LocalDate date = input.getDate();
        YearMonth month = bookingMonths.bookFor(date);
        MoneyAccountKind kind = MoneyAccountKind.valueOf(input.getAccount().getValue());
        AccountRow account = accounts.lockAccountOfKind(kind)
                .orElseThrow(() -> new IllegalStateException("The cooperative has no " + kind + " account"));
        Opening opening = requireOpening(account, date);
        if (kind == MoneyAccountKind.CASH) {
            CashGuard.check(opening, accounts.dayTotals(account.id(), opening.date(), LAST_DAY), date,
                    amount.negate());
        }

        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String counterparty = blankToNull(input.getCounterparty());
        String description = blankToNull(input.getDescription());
        UUID movement = movements.writeEntry(tenantId, account.id(), MovementKind.EXPENSE, amount.negate(), date,
                month, SOURCE, id, memberId, now);
        expenses.insert(new NewExpense(id, tenantId, account.id(), category.id(), amount, date,
                cipher.seal(tenantId, TreasuryCipher.ref(TreasuryCipher.EXPENSE, TreasuryCipher.COUNTERPARTY, id),
                        counterparty),
                cipher.seal(tenantId, TreasuryCipher.ref(TreasuryCipher.EXPENSE, TreasuryCipher.DESCRIPTION, id),
                        description),
                blankToNull(input.getDocumentNumber()), input.getDocumentDate(), movement, null, null, memberId, now));
        int position = 0;
        for (AttachmentRef ref : reuse) {
            if (!expenses.copyAttachment(ref.getExpenseId(), ref.getN(), id, ++position, memberId, now)) {
                throw new IllegalStateException("A reused file disappeared");
            }
        }
        for (AttachmentUploads.Prepared file : prepared) {
            files.save(ExpenseAttachmentContent.create(tenantId, id, ++position, file, memberId, now));
        }
        files.flush();
        audit.expenseRecorded(actor, new ExpenseFacts(id, kind, amount, category.id(), date, counterparty,
                description), now);
        log.debug("Expense {} recorded with {} files", id, position);
        return card(tenantId, expenses.card(id).orElseThrow());
    }

    @Transactional
    Expense reverse(UUID id, @Nullable String rawReason, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        UUID memberId = Objects.requireNonNull(actor.memberId(), "memberId");
        String reason = blankToNull(rawReason);
        if (reason == null) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("reason", ProblemCodes.REQUIRED)));
        }
        ExpenseRow expense = expenses.card(id).orElseThrow(ApiProblemException::notFound);
        LocalDate today = LocalDate.now(clock);
        YearMonth month = bookingMonths.bookFor(today);
        accounts.lockAccount(expense.accountId()).orElseThrow(ApiProblemException::notFound);
        if (expenses.reversed(id)) {
            throw alreadyReversed();
        }
        Instant now = clock.instant();
        UUID reversalId = UUID.randomUUID();
        try {
            UUID movement = movements.writeReversal(expense.movementId(), today, month, SOURCE, reversalId, reason,
                    memberId, now);
            expenses.insert(new NewExpense(reversalId, tenantId, expense.accountId(), expense.categoryId(),
                    expense.amount(), today, null, null, null, null, movement, id, reason, memberId, now));
        } catch (PersistenceException | DataIntegrityViolationException e) {
            if (REVERSED_ONCE.stream().anyMatch(index -> ConstraintViolations.violates(e, index))) {
                throw alreadyReversed();
            }
            throw e;
        }
        audit.expenseReversed(actor, new ExpenseFacts(id, expense.account(), expense.amount(), expense.categoryId(),
                expense.date(), counterparty(tenantId, expense), description(tenantId, expense)), today, reason, now);
        log.debug("Expense {} reversed by {}", id, reversalId);
        return card(tenantId, expenses.card(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    Expense get(UUID tenantId, UUID id) {
        return card(tenantId, expenses.card(id).orElseThrow(ApiProblemException::notFound));
    }

    @Transactional(readOnly = true)
    ExpensePage list(UUID tenantId, @Nullable LocalDate from, @Nullable LocalDate to,
            @Nullable MoneyAccountKind account, @Nullable UUID categoryId, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("from", ProblemCodes.INVALID)));
        }
        Filter filter = new Filter(from, to, account, categoryId);
        long total = expenses.count(filter);
        List<SummaryRow> rows = expenses.list(filter, (long) page * size, size);
        Set<UUID> ids = rows.stream().map(SummaryRow::id).collect(Collectors.toSet());
        Map<UUID, Long> counts = ids.isEmpty() ? Map.of() : attachments.countByExpense(ids).stream()
                .collect(Collectors.toMap(v -> (UUID) v[0], v -> ((Number) v[1]).longValue()));
        Map<UUID, CategoryRow> byId = categories.findAll(new HashSet<>(rows.stream().map(SummaryRow::categoryId)
                .toList()));
        List<ExpenseSummary> items = new ArrayList<>();
        for (SummaryRow row : rows) {
            ExpenseSummary item = new ExpenseSummary(row.id(),
                    nadel.contract.model.MoneyAccountKind.fromValue(row.account().name()), row.date(),
                    row.amount().toString(), Categories.view(byId.get(row.categoryId())),
                    counts.getOrDefault(row.id(), 0L).intValue(), row.reversed());
            item.setCounterparty(cipher.open(tenantId,
                    TreasuryCipher.ref(TreasuryCipher.EXPENSE, TreasuryCipher.COUNTERPARTY, row.id()),
                    row.counterpartyEnc()));
            items.add(item);
        }
        return new ExpensePage(items, page, size, total, (int) ((total + size - 1) / size));
    }

    /** The stored file {@code n} of the original expense; 404 when either is unknown or not visible. */
    @Transactional(readOnly = true)
    AttachmentFile download(UUID id, int n) {
        expenses.card(id).orElseThrow(ApiProblemException::notFound);
        ExpenseAttachmentContent file = files.findFile(id, (short) n).orElseThrow(ApiProblemException::notFound);
        return new AttachmentFile(file.fileName(), file.contentType(), file.content());
    }

    /** The account's opening balance; 409 without one, 400 when the entry is dated before it. */
    private static Opening requireOpening(AccountRow account, LocalDate date) {
        Opening opening = account.opening();
        if (opening == null) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.OPENING_BALANCE_MISSING,
                    "The account has no opening balance yet.");
        }
        if (date.isBefore(opening.date())) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.BEFORE_OPENING_BALANCE,
                    "The entry is dated before the opening balance of the account.",
                    List.of(new FieldProblem("date", ProblemCodes.BEFORE_OPENING_BALANCE)));
        }
        return opening;
    }

    /** The contract form of the card: the category, the opened texts, the files without content, the names. */
    private Expense card(UUID tenantId, ExpenseRow row) {
        CategoryRow category = categories.find(row.categoryId()).orElseThrow();
        List<ExpenseAttachment> stored = attachments.findByExpense(row.id()).stream()
                .map(a -> new ExpenseAttachment(a.position(), a.fileName(),
                        ExpenseAttachment.ContentTypeEnum.fromValue(a.contentType()), (long) a.sizeBytes()))
                .toList();
        Set<UUID> authors = new HashSet<>();
        if (row.createdBy() != null) {
            authors.add(row.createdBy());
        }
        if (row.reversal() != null && row.reversal().createdBy() != null) {
            authors.add(row.reversal().createdBy());
        }
        Map<UUID, String> names = authors.isEmpty() ? Map.of() : actorNames.displayNames(authors);
        Expense card = new Expense(row.id(), nadel.contract.model.MoneyAccountKind.fromValue(row.account().name()),
                row.date(), row.amount().toString(), row.bookingMonth().toString(), Categories.view(category), stored,
                row.reversal() != null, row.createdAt().atOffset(ZoneOffset.UTC));
        card.setCounterparty(counterparty(tenantId, row));
        card.setDescription(description(tenantId, row));
        card.setDocumentNumber(row.documentNumber());
        card.setDocumentDate(row.documentDate());
        card.setCreatedByName(row.createdBy() == null ? null : names.get(row.createdBy()));
        if (row.reversal() != null) {
            ExpenseReversalInfo reversal = new ExpenseReversalInfo(row.reversal().date(), row.reversal().reason(),
                    row.reversal().createdAt().atOffset(ZoneOffset.UTC));
            reversal.setCreatedByName(row.reversal().createdBy() == null ? null
                    : names.get(row.reversal().createdBy()));
            card.setReversal(reversal);
        }
        return card;
    }

    private @Nullable String counterparty(UUID tenantId, ExpenseRow row) {
        return cipher.open(tenantId, TreasuryCipher.ref(TreasuryCipher.EXPENSE, TreasuryCipher.COUNTERPARTY,
                row.id()), row.counterpartyEnc());
    }

    private @Nullable String description(UUID tenantId, ExpenseRow row) {
        return cipher.open(tenantId, TreasuryCipher.ref(TreasuryCipher.EXPENSE, TreasuryCipher.DESCRIPTION,
                row.id()), row.descriptionEnc());
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static ApiProblemException alreadyReversed() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.ENTRY_ALREADY_REVERSED,
                "The entry has already been reversed.");
    }
}
