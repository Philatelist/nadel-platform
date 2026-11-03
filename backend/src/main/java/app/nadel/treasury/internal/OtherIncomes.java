package app.nadel.treasury.internal;

import app.nadel.audit.ActorNames;
import app.nadel.identity.AccessProfile;
import app.nadel.ledger.BookingMonths;
import app.nadel.money.Money;
import app.nadel.privacy.FieldRef;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.treasury.MoneyAccountKind;
import app.nadel.treasury.MovementKind;
import app.nadel.treasury.internal.AccountBalances.Opening;
import app.nadel.treasury.internal.Categories.CategoryRow;
import app.nadel.treasury.internal.OtherIncomeQueries.NewOtherIncome;
import app.nadel.treasury.internal.OtherIncomeQueries.OtherIncomeRow;
import app.nadel.treasury.internal.TreasuryAuditPublisher.OtherIncomeFacts;
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
import nadel.contract.model.ExpenseReversalInfo;
import nadel.contract.model.OtherIncome;
import nadel.contract.model.OtherIncomeInput;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Other income («Інший прихід», feature 018 §2.5, §2.8; technical considerations 2.5): money received that is not a
 * member payment (bank interest, a grant, a sale); record and reverse. The caller holds the permission (checked on
 * {@code TreasuryController}); the cooperative is the one bound to the transaction.
 * <p>
 * <b>Recording</b>, in this order (the first refusal answers, nothing is stored): the fields (400
 * {@code VALIDATION_FAILED}: {@code amount} {@code AMOUNT_NOT_POSITIVE}/{@code INVALID}, {@code date}
 * {@code DATE_IN_FUTURE}, {@code categoryId} {@code INVALID} (unknown, another cooperative's or an expense category: the
 * database does not enforce the kind) or {@code ARCHIVED}); the billing month ({@link BookingMonths#bookFor}), then the
 * account row {@code FOR UPDATE}, then 409 {@code OPENING_BALANCE_MISSING} and 400 {@code BEFORE_OPENING_BALANCE}. Money
 * comes in, so no balance rule applies. The movement ({@code OTHER_INCOME}, the amount, source {@code OTHER_INCOME}), the
 * income row with the description sealed ({@link TreasuryCipher}) and the audit entry {@code OTHER_INCOME_RECORDED} are
 * written in the one transaction.
 * <p>
 * <b>Reversing</b>: a blank reason is 400 {@code reason} {@code REQUIRED}; the billing month of today, then the account
 * locked, then 409 {@code ENTRY_ALREADY_REVERSED} (the unique keys on the reversal are the backstop); the reversal takes
 * the money out again, so for the cash {@link CashGuard} checks it dated today over the day totals read under the lock
 * (409 {@code CASH_INSUFFICIENT}); the bank may go below zero. Then the negated movement dated today, a reversal row of
 * the same amount and category pointing at the income with the reason, and the audit entry
 * {@code OTHER_INCOME_REVERSED}. Logs ids only.
 */
@Service
class OtherIncomes {

    private static final Logger log = LoggerFactory.getLogger(OtherIncomes.class);

    private static final String SOURCE = "OTHER_INCOME";
    private static final String INCOME_CATEGORY = "INCOME";
    private static final Set<String> REVERSED_ONCE = Set.of("other_income_reversed_once_uq",
            "account_movement_reversed_once_uq");

    private final TreasuryQueries accounts;
    private final OtherIncomeQueries incomes;
    private final Categories categories;
    private final MovementWriter movements;
    private final BookingMonths bookingMonths;
    private final TreasuryCipher cipher;
    private final TreasuryAuditPublisher audit;
    private final ActorNames actorNames;
    private final Clock clock;

    OtherIncomes(TreasuryQueries accounts, OtherIncomeQueries incomes, Categories categories, MovementWriter movements,
            BookingMonths bookingMonths, TreasuryCipher cipher, TreasuryAuditPublisher audit, ActorNames actorNames,
            Clock clock) {
        this.accounts = accounts;
        this.incomes = incomes;
        this.categories = categories;
        this.movements = movements;
        this.bookingMonths = bookingMonths;
        this.cipher = cipher;
        this.audit = audit;
        this.actorNames = actorNames;
        this.clock = clock;
    }

    @Transactional
    OtherIncome create(OtherIncomeInput input, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        UUID memberId = Objects.requireNonNull(actor.memberId(), "memberId");
        LocalDate today = LocalDate.now(clock);

        Money amount = Money.parse(input.getAmount());
        List<FieldProblem> errors = new ArrayList<>();
        FieldProblem amountProblem = EntryChecks.amountProblem(amount);
        if (amountProblem != null) {
            errors.add(amountProblem);
        }
        if (input.getDate().isAfter(today)) {
            errors.add(new FieldProblem("date", ProblemCodes.DATE_IN_FUTURE));
        }
        CategoryRow category = categories.find(input.getCategoryId()).orElse(null);
        if (category == null || !INCOME_CATEGORY.equals(category.kind())) {
            errors.add(new FieldProblem("categoryId", ProblemCodes.INVALID));
        } else if (category.archived()) {
            errors.add(new FieldProblem("categoryId", ProblemCodes.ARCHIVED));
        }
        EntryChecks.refuse(errors);

        LocalDate date = input.getDate();
        YearMonth month = bookingMonths.bookFor(date);
        MoneyAccountKind kind = MoneyAccountKind.valueOf(input.getAccount().getValue());
        AccountRow account = accounts.lockAccountOfKind(kind)
                .orElseThrow(() -> new IllegalStateException("The cooperative has no " + kind + " account"));
        EntryChecks.requireOpening(account, date);

        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String description = EntryChecks.blankToNull(input.getDescription());
        UUID movement = movements.writeEntry(tenantId, account.id(), MovementKind.OTHER_INCOME, amount, date, month,
                SOURCE, id, memberId, now);
        incomes.insert(new NewOtherIncome(id, tenantId, account.id(), category.id(), amount, date,
                cipher.seal(tenantId, descriptionRef(id), description), movement, null, null, memberId, now));
        audit.otherIncomeRecorded(actor, new OtherIncomeFacts(id, kind, amount, category.id(), date, description),
                now);
        log.debug("Other income {} recorded", id);
        return view(tenantId, incomes.card(id).orElseThrow());
    }

    @Transactional
    OtherIncome reverse(UUID id, @Nullable String rawReason, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        UUID memberId = Objects.requireNonNull(actor.memberId(), "memberId");
        String reason = EntryChecks.requireReason(rawReason);
        OtherIncomeRow income = incomes.card(id).orElseThrow(ApiProblemException::notFound);
        LocalDate today = LocalDate.now(clock);
        YearMonth month = bookingMonths.bookFor(today);
        AccountRow account = accounts.lockAccount(income.accountId()).orElseThrow(ApiProblemException::notFound);
        if (incomes.reversed(id)) {
            throw EntryChecks.alreadyReversed();
        }
        if (account.kind() == MoneyAccountKind.CASH) {
            Opening opening = EntryChecks.requireOpening(account, today);
            CashGuard.check(opening, accounts.dayTotals(account.id(), opening.date(), EntryChecks.LAST_DAY), today,
                    income.amount().negate());
        }
        Instant now = clock.instant();
        UUID reversalId = UUID.randomUUID();
        try {
            UUID movement = movements.writeReversal(income.movementId(), today, month, SOURCE, reversalId, reason,
                    memberId, now);
            incomes.insert(new NewOtherIncome(reversalId, tenantId, income.accountId(), income.categoryId(),
                    income.amount(), today, null, movement, id, reason, memberId, now));
        } catch (PersistenceException | DataIntegrityViolationException e) {
            if (REVERSED_ONCE.stream().anyMatch(index -> ConstraintViolations.violates(e, index))) {
                throw EntryChecks.alreadyReversed();
            }
            throw e;
        }
        audit.otherIncomeReversed(actor, new OtherIncomeFacts(id, income.account(), income.amount(),
                income.categoryId(), income.date(), description(tenantId, income)), today, reason, now);
        log.debug("Other income {} reversed by {}", id, reversalId);
        return view(tenantId, incomes.card(id).orElseThrow());
    }

    /** The contract form of an income: the category, the opened description, the authors' current names. */
    private OtherIncome view(UUID tenantId, OtherIncomeRow row) {
        CategoryRow category = categories.find(row.categoryId()).orElseThrow();
        Set<UUID> authors = new HashSet<>();
        if (row.createdBy() != null) {
            authors.add(row.createdBy());
        }
        if (row.reversal() != null && row.reversal().createdBy() != null) {
            authors.add(row.reversal().createdBy());
        }
        Map<UUID, String> names = authors.isEmpty() ? Map.of() : actorNames.displayNames(authors);
        OtherIncome view = new OtherIncome(row.id(), nadel.contract.model.MoneyAccountKind.fromValue(
                row.account().name()), row.date(), row.amount().toString(), row.bookingMonth().toString(),
                Categories.view(category), row.reversal() != null, row.createdAt().atOffset(ZoneOffset.UTC));
        view.setDescription(description(tenantId, row));
        view.setCreatedByName(row.createdBy() == null ? null : names.get(row.createdBy()));
        if (row.reversal() != null) {
            ExpenseReversalInfo reversal = new ExpenseReversalInfo(row.reversal().date(), row.reversal().reason(),
                    row.reversal().createdAt().atOffset(ZoneOffset.UTC));
            reversal.setCreatedByName(row.reversal().createdBy() == null ? null
                    : names.get(row.reversal().createdBy()));
            view.setReversal(reversal);
        }
        return view;
    }

    private @Nullable String description(UUID tenantId, OtherIncomeRow row) {
        return cipher.open(tenantId, descriptionRef(row.id()), row.descriptionEnc());
    }

    private static FieldRef descriptionRef(UUID id) {
        return TreasuryCipher.ref(TreasuryCipher.OTHER_INCOME, TreasuryCipher.DESCRIPTION, id);
    }
}
