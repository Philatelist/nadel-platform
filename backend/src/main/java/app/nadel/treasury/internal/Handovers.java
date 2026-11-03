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
import app.nadel.treasury.internal.HandoverQueries.HandoverRow;
import app.nadel.treasury.internal.HandoverQueries.NewHandover;
import app.nadel.treasury.internal.TreasuryAuditPublisher.HandoverFacts;
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
import nadel.contract.model.Handover;
import nadel.contract.model.HandoverInput;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handovers of cash to the bank («Здати в банк», feature 018 §2.4, §2.8; technical considerations 2.5): record and
 * reverse. The caller holds the permission (checked on {@code TreasuryController}); the cooperative is the one bound
 * to the transaction.
 * <p>
 * <b>Recording</b>, in this order (the first refusal answers, nothing is stored): the fields (400
 * {@code VALIDATION_FAILED}: {@code amount} {@code AMOUNT_NOT_POSITIVE}/{@code INVALID}, {@code date}
 * {@code DATE_IN_FUTURE}, {@code documentNumber} {@code REQUIRED} when blank); the billing month
 * ({@link BookingMonths#bookFor}: a closed month books in the earliest open one, the date is kept), then the cash and the
 * bank account rows {@code FOR UPDATE} (the treasury lock order: month, cash, bank), then for each account 409
 * {@code OPENING_BALANCE_MISSING} and 400 {@code BEFORE_OPENING_BALANCE}, then {@link CashGuard} over the cash day
 * totals read under the lock (409 {@code CASH_INSUFFICIENT}). The two movements ({@code HANDOVER_OUT} the negated amount
 * on the cash, {@code HANDOVER_IN} the amount on the bank, both source {@code HANDOVER}, the same date and month), the
 * handover row with the document number and the audit entry {@code HANDOVER_RECORDED} are written in the one
 * transaction.
 * <p>
 * <b>Reversing</b>: a blank reason is 400 {@code reason} {@code REQUIRED}; the billing month of today, then the cash and
 * the bank locked, then 409 {@code ENTRY_ALREADY_REVERSED} (the unique keys on the reversal are the backstop); the
 * reversal of each movement dated today (the cash gets the money back, the bank gives it: the bank may go below zero,
 * so nothing is refused for a balance), a reversal row of the same amount and document number pointing at the handover
 * with the reason, and the audit entry {@code HANDOVER_REVERSED}. Logs ids only.
 */
@Service
class Handovers {

    private static final Logger log = LoggerFactory.getLogger(Handovers.class);

    private static final String SOURCE = "HANDOVER";
    private static final Set<String> REVERSED_ONCE = Set.of("handover_reversed_once_uq",
            "account_movement_reversed_once_uq");

    private final TreasuryQueries accounts;
    private final HandoverQueries handovers;
    private final MovementWriter movements;
    private final BookingMonths bookingMonths;
    private final TreasuryAuditPublisher audit;
    private final ActorNames actorNames;
    private final Clock clock;

    Handovers(TreasuryQueries accounts, HandoverQueries handovers, MovementWriter movements,
            BookingMonths bookingMonths, TreasuryAuditPublisher audit, ActorNames actorNames, Clock clock) {
        this.accounts = accounts;
        this.handovers = handovers;
        this.movements = movements;
        this.bookingMonths = bookingMonths;
        this.audit = audit;
        this.actorNames = actorNames;
        this.clock = clock;
    }

    @Transactional
    Handover create(HandoverInput input, AccessProfile actor) {
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
        String documentNumber = EntryChecks.blankToNull(input.getDocumentNumber());
        if (documentNumber == null) {
            errors.add(new FieldProblem("documentNumber", ProblemCodes.REQUIRED));
        }
        EntryChecks.refuse(errors);

        LocalDate date = input.getDate();
        YearMonth month = bookingMonths.bookFor(date);
        AccountRow cash = lock(MoneyAccountKind.CASH);
        AccountRow bank = lock(MoneyAccountKind.BANK);
        Opening cashOpening = EntryChecks.requireOpening(cash, date);
        EntryChecks.requireOpening(bank, date);
        CashGuard.check(cashOpening, accounts.dayTotals(cash.id(), cashOpening.date(), EntryChecks.LAST_DAY), date,
                amount.negate());

        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        UUID out = movements.writeEntry(tenantId, cash.id(), MovementKind.HANDOVER_OUT, amount.negate(), date, month,
                SOURCE, id, memberId, now);
        UUID in = movements.writeEntry(tenantId, bank.id(), MovementKind.HANDOVER_IN, amount, date, month, SOURCE, id,
                memberId, now);
        handovers.insert(new NewHandover(id, tenantId, amount, date, Objects.requireNonNull(documentNumber), out, in,
                null, null, memberId, now));
        audit.handoverRecorded(actor, new HandoverFacts(id, amount, date, documentNumber), now);
        log.debug("Handover {} recorded", id);
        return view(handovers.card(id).orElseThrow());
    }

    @Transactional
    Handover reverse(UUID id, @Nullable String rawReason, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        UUID memberId = Objects.requireNonNull(actor.memberId(), "memberId");
        String reason = EntryChecks.requireReason(rawReason);
        HandoverRow handover = handovers.card(id).orElseThrow(ApiProblemException::notFound);
        LocalDate today = LocalDate.now(clock);
        YearMonth month = bookingMonths.bookFor(today);
        lock(MoneyAccountKind.CASH);
        lock(MoneyAccountKind.BANK);
        if (handovers.reversed(id)) {
            throw EntryChecks.alreadyReversed();
        }
        Instant now = clock.instant();
        UUID reversalId = UUID.randomUUID();
        try {
            UUID out = movements.writeReversal(handover.outMovementId(), today, month, SOURCE, reversalId, reason,
                    memberId, now);
            UUID in = movements.writeReversal(handover.inMovementId(), today, month, SOURCE, reversalId, reason,
                    memberId, now);
            handovers.insert(new NewHandover(reversalId, tenantId, handover.amount(), today,
                    handover.documentNumber(), out, in, id, reason, memberId, now));
        } catch (PersistenceException | DataIntegrityViolationException e) {
            if (REVERSED_ONCE.stream().anyMatch(index -> ConstraintViolations.violates(e, index))) {
                throw EntryChecks.alreadyReversed();
            }
            throw e;
        }
        audit.handoverReversed(actor, new HandoverFacts(id, handover.amount(), handover.date(),
                handover.documentNumber()), today, reason, now);
        log.debug("Handover {} reversed by {}", id, reversalId);
        return view(handovers.card(id).orElseThrow());
    }

    private AccountRow lock(MoneyAccountKind kind) {
        return accounts.lockAccountOfKind(kind)
                .orElseThrow(() -> new IllegalStateException("The cooperative has no " + kind + " account"));
    }

    /** The contract form of a handover with the authors' current names. */
    private Handover view(HandoverRow row) {
        Set<UUID> authors = new HashSet<>();
        if (row.createdBy() != null) {
            authors.add(row.createdBy());
        }
        if (row.reversal() != null && row.reversal().createdBy() != null) {
            authors.add(row.reversal().createdBy());
        }
        Map<UUID, String> names = authors.isEmpty() ? Map.of() : actorNames.displayNames(authors);
        Handover view = new Handover(row.id(), row.date(), row.amount().toString(), row.bookingMonth().toString(),
                row.documentNumber(), row.reversal() != null, row.createdAt().atOffset(ZoneOffset.UTC));
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
}
