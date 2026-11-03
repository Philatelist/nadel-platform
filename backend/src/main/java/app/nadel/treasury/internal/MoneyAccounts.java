package app.nadel.treasury.internal;

import app.nadel.audit.ActorNames;
import app.nadel.identity.AccessProfile;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.PaymentRef;
import app.nadel.money.Money;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.settings.CooperativeSettings;
import app.nadel.treasury.MoneyAccountKind;
import app.nadel.treasury.internal.AccountBalances.DayTotal;
import app.nadel.treasury.internal.AccountBalances.Opening;
import app.nadel.treasury.internal.TreasuryQueries.AccountRow;
import app.nadel.treasury.internal.TreasuryQueries.MovementRow;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.AccountMovement;
import nadel.contract.model.AccountMovementPage;
import nadel.contract.model.MoneyAccount;
import nadel.contract.model.MovementKind;
import nadel.contract.model.MovementSource;
import nadel.contract.model.MovementSourceType;
import nadel.contract.model.OpeningBalance;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The money accounts of the cooperative (feature 018 §2.1, §2.2, §2.11; technical considerations 2.5, 2.10): the list
 * with the balance on a day, the opening balance and the journal of a period. Balances follow {@link AccountBalances}
 * over the day totals of {@link TreasuryQueries}; the server computes every amount. The caller holds the permission
 * (checked on {@code TreasuryController}); the cooperative is the one bound to the transaction.
 * <p>
 * <b>Opening balance</b>: the values ({@code amount} 0.00 or more with at most 12 integer digits, else 400
 * {@code VALIDATION_FAILED} {@code amount} {@code INVALID}; {@code date} not after today in Europe/Kyiv, else
 * {@code date} {@code DATE_IN_FUTURE}); the account locked {@code FOR UPDATE} (404 {@code NOT_FOUND}); an account that
 * has one is 409 {@code OPENING_BALANCE_ALREADY_SET} (two requests at once: the second waits on the lock and then sees
 * it; the trigger {@code money_account_guard} is the database's backstop). Only the granted {@code opening_*} columns
 * are written. Audit {@code OPENING_BALANCE_SET}. Logs ids only.
 */
@Service
class MoneyAccounts {

    private static final Logger log = LoggerFactory.getLogger(MoneyAccounts.class);

    /** {@code numeric(14,2)}: twelve digits before the point. */
    private static final int MAX_INTEGER_DIGITS = 12;

    private static final String SET_OPENING = """
            update money_account
            set opening_amount = :amount, opening_date = :date, opening_set_by = cast(:by as uuid), opening_set_at = :at
            where id = :id
            """;

    private final TreasuryQueries queries;
    private final LedgerReadModel ledger;
    private final CooperativeSettings settings;
    private final ActorNames actorNames;
    private final TreasuryAuditPublisher audit;
    private final EntityManager entityManager;
    private final Clock clock;

    MoneyAccounts(TreasuryQueries queries, LedgerReadModel ledger, CooperativeSettings settings,
            ActorNames actorNames, TreasuryAuditPublisher audit, EntityManager entityManager, Clock clock) {
        this.queries = queries;
        this.ledger = ledger;
        this.settings = settings;
        this.actorNames = actorNames;
        this.audit = audit;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** Both accounts, cash first, with their balances at the end of {@code asOf} (today when null). */
    @Transactional(readOnly = true)
    List<MoneyAccount> list(@Nullable LocalDate asOf) {
        LocalDate today = LocalDate.now(clock);
        LocalDate day = asOf == null ? today : asOf;
        if (day.isAfter(today)) {
            throw invalid("asOf", ProblemCodes.INVALID);
        }
        String iban = settings.requisites().iban();
        return queries.accounts().stream().map(account -> view(account, day, iban)).toList();
    }

    /** Sets the opening balance of the account once; answers the account with its balance today. */
    @Transactional
    MoneyAccount setOpening(UUID accountId, String amountText, LocalDate date, AccessProfile actor) {
        Money amount = Money.parse(amountText);
        LocalDate today = LocalDate.now(clock);
        List<FieldProblem> errors = new ArrayList<>();
        if (amount.signum() < 0 || integerDigits(amount) > MAX_INTEGER_DIGITS) {
            errors.add(new FieldProblem("amount", ProblemCodes.INVALID));
        }
        if (date.isAfter(today)) {
            errors.add(new FieldProblem("date", ProblemCodes.DATE_IN_FUTURE));
        }
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", errors);
        }
        AccountRow account = queries.lockAccount(accountId).orElseThrow(ApiProblemException::notFound);
        if (account.opening() != null) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.OPENING_BALANCE_ALREADY_SET,
                    "The opening balance of the account is already set.");
        }
        Instant now = clock.instant();
        entityManager.createNativeQuery(SET_OPENING)
                .setParameter("amount", amount.value())
                .setParameter("date", date)
                .setParameter("by", actor.memberId())
                .setParameter("at", now)
                .setParameter("id", accountId)
                .executeUpdate();
        audit.openingBalanceSet(actor, accountId, account.kind(), amount, date, now);
        log.debug("Opening balance of account {} set", accountId);
        AccountRow saved = new AccountRow(account.id(), account.kind(), new Opening(amount, date));
        return view(saved, today, settings.requisites().iban());
    }

    /** A page of the account's journal from {@code from} to {@code to} with the balances at both ends. */
    @Transactional(readOnly = true)
    AccountMovementPage movements(UUID accountId, @Nullable LocalDate from, @Nullable LocalDate to, int page,
            int size) {
        LocalDate today = LocalDate.now(clock);
        LocalDate first = from == null ? today.withDayOfMonth(1) : from;
        LocalDate last = to == null ? today : to;
        if (first.isAfter(last)) {
            throw invalid("from", ProblemCodes.INVALID);
        }
        AccountRow account = queries.account(accountId).orElseThrow(ApiProblemException::notFound);
        Opening opening = account.opening();
        Money openingBalance = null;
        Money closingBalance = null;
        if (opening != null) {
            List<DayTotal> totals = queries.dayTotals(accountId, opening.date(), last);
            openingBalance = AccountBalances.balanceAt(opening, totals, first.minusDays(1));
            closingBalance = AccountBalances.balanceAt(opening, totals, last);
        }
        long total = queries.countMovements(accountId, first, last);
        List<MovementRow> rows = queries.movements(accountId, first, last, (long) page * size, size);
        List<AccountMovement> items = items(rows, opening);
        AccountMovementPage result = new AccountMovementPage(accountId,
                nadel.contract.model.MoneyAccountKind.fromValue(account.kind().name()), first, last,
                AccountBalances.negative(closingBalance), items, page, size, total,
                (int) ((total + size - 1) / size));
        result.setOpeningBalance(openingBalance == null ? null : openingBalance.toString());
        result.setClosingBalance(closingBalance == null ? null : closingBalance.toString());
        return result;
    }

    /** The contract form of the lines, with the payment refs and the authors' current names. */
    private List<AccountMovement> items(List<MovementRow> rows, @Nullable Opening opening) {
        Set<UUID> paymentRows = rows.stream().map(MovementRow::paymentRowId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, PaymentRef> refs = paymentRows.isEmpty() ? Map.of() : ledger.paymentRefs(paymentRows);
        Set<UUID> authors = rows.stream().map(MovementRow::createdBy).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> names = authors.isEmpty() ? Map.of() : actorNames.displayNames(authors);
        List<AccountMovement> items = new ArrayList<>();
        for (MovementRow row : rows) {
            AccountMovement item = new AccountMovement(row.id(), row.movedOn(), MovementKind.fromValue(row.kind().name()),
                    row.amount().toString(), row.bookingMonth().toString(),
                    AccountBalances.beforeOpening(opening, row.movedOn()),
                    new MovementSource(MovementSourceType.fromValue(row.sourceType()), row.sourceId()), row.reversed(),
                    row.createdAt().atOffset(ZoneOffset.UTC));
            PaymentRef ref = row.paymentRowId() == null ? null : refs.get(row.paymentRowId());
            if (ref != null) {
                item.setPlotId(ref.plotId());
                item.setReceiptNumber(ref.receiptNumber());
                item.setDocumentNumber(ref.bankReference());
            }
            item.setReversesMovementId(row.reversesMovementId());
            item.setReason(row.reason());
            item.setCreatedByName(row.createdBy() == null ? null : names.get(row.createdBy()));
            items.add(item);
        }
        return items;
    }

    /** The contract form of an account with its balance at the end of {@code day}. */
    private MoneyAccount view(AccountRow account, LocalDate day, @Nullable String iban) {
        MoneyAccount view = new MoneyAccount(account.id(),
                nadel.contract.model.MoneyAccountKind.fromValue(account.kind().name()), day, false);
        if (account.kind() == MoneyAccountKind.BANK) {
            view.setIban(iban);
        }
        Opening opening = account.opening();
        if (opening != null) {
            view.setOpening(new OpeningBalance(opening.amount().toString(), opening.date()));
            Money balance = AccountBalances.balanceAt(opening, queries.dayTotals(account.id(), opening.date(), day),
                    day);
            view.setBalance(balance == null ? null : balance.toString());
            view.setNegative(AccountBalances.negative(balance));
        }
        return view;
    }

    static int integerDigits(Money amount) {
        BigDecimal value = amount.value().abs();
        return value.precision() - value.scale();
    }

    private static ApiProblemException invalid(String field, String code) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                "The request is not valid.", List.of(new FieldProblem(field, code)));
    }
}
