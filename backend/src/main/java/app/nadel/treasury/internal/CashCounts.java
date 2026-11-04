package app.nadel.treasury.internal;

import app.nadel.audit.ActorNames;
import app.nadel.identity.AccessProfile;
import app.nadel.ledger.BookingMonths;
import app.nadel.money.Money;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.treasury.CashCountAct;
import app.nadel.treasury.CashCountAct.Status;
import app.nadel.treasury.CashCountActs;
import app.nadel.treasury.MoneyAccountKind;
import app.nadel.treasury.MovementKind;
import app.nadel.treasury.internal.AccountBalances.Opening;
import app.nadel.treasury.internal.CashCountFigures.Figures;
import app.nadel.treasury.internal.CashCountQueries.BookingRow;
import app.nadel.treasury.internal.CashCountQueries.CountRow;
import app.nadel.treasury.internal.CashCountQueries.NewCount;
import app.nadel.treasury.internal.TreasuryAuditPublisher.CountFacts;
import app.nadel.treasury.internal.TreasuryQueries.AccountRow;
import app.nadel.treasury.internal.TreasuryQueries.MovementFacts;
import app.nadel.treasury.internal.TreasuryQueries.MovementRow;
import app.nadel.treasury.internal.TreasuryQueries.SinceCount;
import jakarta.persistence.PersistenceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.AccountMovement;
import nadel.contract.model.CashCount;
import nadel.contract.model.CashCountBooking;
import nadel.contract.model.CashCountDifferenceKind;
import nadel.contract.model.CashCountInput;
import nadel.contract.model.CashCountPage;
import nadel.contract.model.CashCountRow;
import nadel.contract.model.CashCountStatus;
import nadel.contract.model.ExpenseReversalInfo;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash counts («Перерахунок каси», feature 018 §2.6; technical considerations 2.5, 2.8): the preview, the save, the
 * list, the card, the booking of a difference, the direct reversal of a booked difference line and the act's figures
 * ({@link CashCountActs}). The caller holds the permission (checked on {@code TreasuryController}; the act on the
 * documents endpoint); the cooperative is the one bound to the transaction.
 * <p>
 * <b>A count</b> ({@link #preview}, {@link #save}), in this order (the first refusal answers, nothing is stored): the
 * fields (400 {@code VALIDATION_FAILED}: {@code counted} below 0.00 or more than 12 integer digits {@code INVALID};
 * {@code countedAt} more than {@link #CLOCK_ALLOWANCE} after now, or on a Kyiv day after today,
 * {@code DATE_IN_FUTURE}; {@code explanation} over 500 characters {@code TOO_LONG}); the cash account ({@code FOR UPDATE}
 * on a save; a preview takes no lock); 409 {@code OPENING_BALANCE_MISSING}, 400 {@code BEFORE_OPENING_BALANCE} on
 * {@code countedAt}; the figures ({@link CashCountFigures}: the balance at the end of the count's day over the day
 * totals read now, under the lock on a save, so a line entered between the preview and the save counts); on a save a
 * difference without an explanation is 400 {@code explanation} {@code REQUIRED}. The saved row keeps that book balance
 * for good. Audit {@code CASH_COUNTED}. The explanation is user content stored in the clear ({@code
 * cash_count.explanation}, V32 has no sealed column): it describes the cash, like a reversal's reason, and is not
 * treated as personal data.
 * <p>
 * <b>Lines since the previous count</b>: the previous count is the newest saved one at or before {@code countedAt}
 * (for a saved count: the one just before it in the list order); the lines are those the book balance counts and the
 * previous count's did not ({@link TreasuryQueries#movementsSince}): dated from the opening date to the count's day,
 * entered up to now (a saved count: up to its save), and dated after the previous count's day or entered after it was
 * saved. At most {@link #SINCE_LIMIT}, oldest first, with the total.
 * <p>
 * <b>Booking a difference</b> ({@link #bookDifference}): a blank reason is 400 {@code reason} {@code REQUIRED}; 404 for
 * an unknown count; the billing month of the count's day ({@link BookingMonths#bookFor}), then the cash locked, then
 * (read again under the lock) 409 {@code NO_DIFFERENCE} for a matching count and 409 {@code DIFFERENCE_ALREADY_BOOKED}
 * for a booked one (two at once: the second waits on the lock and then sees the first; the trigger
 * {@code cash_count_guard} is the database's backstop); a shortage goes through {@link CashGuard} on the count's day
 * (409 {@code CASH_INSUFFICIENT}). A {@code COUNT_DIFFERENCE} line of the difference (source {@code CASH_COUNT}) dated
 * the count's day, the count's {@code booked_movement_id} and {@code booking_reason}, and the audit entry
 * {@code COUNT_DIFFERENCE_BOOKED}, in the one transaction.
 * <p>
 * <b>Reversing a booked difference line</b> ({@link #reverseMovement}): a blank reason 400; the billing month of today,
 * the account locked (404), the line (404 unless it is a line of that account); 409 {@code MOVEMENT_NOT_REVERSIBLE}
 * unless it is an original {@code COUNT_DIFFERENCE} or {@code BANK_DIFFERENCE} line; 409
 * {@code ENTRY_ALREADY_REVERSED} (the unique key is the backstop); the reversal of a cash surplus goes through
 * {@link CashGuard} on today (a bank difference is never guarded: the bank may go negative). The reversal (source
 * {@code MOVEMENT}, the reversed line) is dated today; audit {@code COUNT_DIFFERENCE_REVERSED} about the count, or
 * {@code BANK_DIFFERENCE_REVERSED} about the bank reconciliation of the line's month (the line's source; the
 * {@link MovementWriter} reopens that today's month when it is reconciled). The count stays {@code BOOKED}: its
 * difference is booked once ({@code booked_movement_id} is written once), its card shows the reversal, and the next
 * count sees the cash as it is. Logs ids only.
 */
@Service
class CashCounts implements CashCountActs {

    private static final Logger log = LoggerFactory.getLogger(CashCounts.class);

    /** The most lines since the previous count a count lists. */
    static final int SINCE_LIMIT = 500;
    /** How far after the server's now a count may be dated (the clocks of the phone and the server differ a little). */
    static final Duration CLOCK_ALLOWANCE = Duration.ofMinutes(5);
    private static final int MAX_EXPLANATION = 500;
    private static final String SOURCE = "CASH_COUNT";
    private static final String REVERSAL_SOURCE = "MOVEMENT";
    private static final String REVERSED_ONCE = "account_movement_reversed_once_uq";

    private final TreasuryQueries accounts;
    private final CashCountQueries counts;
    private final MovementWriter movements;
    private final BookingMonths bookingMonths;
    private final JournalLines journalLines;
    private final TreasuryAuditPublisher audit;
    private final ActorNames actorNames;
    private final Clock clock;

    CashCounts(TreasuryQueries accounts, CashCountQueries counts, MovementWriter movements,
            BookingMonths bookingMonths, JournalLines journalLines, TreasuryAuditPublisher audit,
            ActorNames actorNames, Clock clock) {
        this.accounts = accounts;
        this.counts = counts;
        this.movements = movements;
        this.bookingMonths = bookingMonths;
        this.journalLines = journalLines;
        this.audit = audit;
        this.actorNames = actorNames;
        this.clock = clock;
    }

    /** A count as entered, checked. */
    private record Entry(Instant countedAt, LocalDate day, Money counted, @Nullable String explanation) {
    }

    /** The figures and the lines of a count without saving it. */
    @Transactional(readOnly = true)
    CashCount preview(CashCountInput input) {
        Entry entry = entry(input);
        AccountRow cash = accounts.accounts().stream().filter(account -> account.kind() == MoneyAccountKind.CASH)
                .findFirst().orElseThrow(() -> new IllegalStateException("The cooperative has no CASH account"));
        Opening opening = EntryChecks.requireOpening(cash, entry.day(), "countedAt");
        Figures figures = CashCountFigures.of(opening, accounts.dayTotals(cash.id(), opening.date(), entry.day()),
                entry.day(), entry.counted());
        CountRow previous = counts.previousOfNew(entry.countedAt()).orElse(null);
        CashCount view = new CashCount(true, cash.id(), offset(entry.countedAt()), entry.day(),
                figures.counted().toString(), figures.bookBalance().toString(), figures.difference().toString(),
                contract(CashCountFigures.status(figures.difference(), false)), List.of(), 0L);
        view.setExplanation(entry.explanation());
        since(view, cash.id(), opening, entry.day(), previous, clock.instant());
        if (previous != null) {
            view.setPreviousCount(row(previous, nameOf(names(Collections.singletonList(previous.createdBy())),
                    previous.createdBy())));
        }
        return view;
    }

    /** Saves the count with the book balance computed under the cash account lock. */
    @Transactional
    CashCount save(CashCountInput input, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        UUID memberId = Objects.requireNonNull(actor.memberId(), "memberId");
        Entry entry = entry(input);
        AccountRow cash = accounts.lockAccountOfKind(MoneyAccountKind.CASH)
                .orElseThrow(() -> new IllegalStateException("The cooperative has no CASH account"));
        Opening opening = EntryChecks.requireOpening(cash, entry.day(), "countedAt");
        Figures figures = CashCountFigures.of(opening, accounts.dayTotals(cash.id(), opening.date(), entry.day()),
                entry.day(), entry.counted());
        if (figures.difference().signum() != 0 && entry.explanation() == null) {
            EntryChecks.refuse(List.of(new FieldProblem("explanation", ProblemCodes.REQUIRED)));
        }
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        counts.insert(new NewCount(id, tenantId, cash.id(), entry.countedAt(), entry.day(), figures.counted(),
                figures.bookBalance(), figures.difference(), entry.explanation(), memberId, now));
        audit.cashCounted(actor, new CountFacts(id, entry.countedAt(), figures.counted(), figures.bookBalance(),
                figures.difference(), entry.explanation()), now);
        log.debug("Cash count {} saved", id);
        return view(counts.card(id).orElseThrow());
    }

    /** A page of the counts, newest first. */
    @Transactional(readOnly = true)
    CashCountPage list(int page, int size) {
        List<CountRow> rows = counts.page((long) page * size, size);
        long total = counts.count();
        Map<UUID, String> names = names(rows.stream().map(CountRow::createdBy).toList());
        List<CashCountRow> items = rows.stream().map(row -> row(row, nameOf(names, row.createdBy()))).toList();
        return new CashCountPage(items, page, size, total, (int) ((total + size - 1) / size));
    }

    /** The card of the count; 404 when unknown or of another cooperative. */
    @Transactional(readOnly = true)
    CashCount get(UUID id) {
        return view(counts.card(id).orElseThrow(ApiProblemException::notFound));
    }

    /** Books the count's difference once, dated the count's day. */
    @Transactional
    CashCount bookDifference(UUID id, @Nullable String rawReason, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        UUID memberId = Objects.requireNonNull(actor.memberId(), "memberId");
        String reason = EntryChecks.requireReason(rawReason);
        CountRow found = counts.card(id).orElseThrow(ApiProblemException::notFound);
        YearMonth month = bookingMonths.bookFor(found.countedOn());
        AccountRow cash = accounts.lockAccount(found.accountId())
                .orElseThrow(() -> new IllegalStateException("The count's account is not visible"));
        // read again under the lock: a booking committed meanwhile is seen
        CountRow count = counts.card(id).orElseThrow();
        if (count.difference().signum() == 0) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.NO_DIFFERENCE,
                    "The count matches the books; there is no difference to book.");
        }
        if (count.booking() != null) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.DIFFERENCE_ALREADY_BOOKED,
                    "The difference of the count is already booked.");
        }
        if (count.difference().signum() < 0) {
            Opening opening = EntryChecks.requireOpening(cash, count.countedOn());
            CashGuard.check(opening, accounts.dayTotals(cash.id(), opening.date(), EntryChecks.LAST_DAY),
                    count.countedOn(), count.difference());
        }
        Instant now = clock.instant();
        UUID movement = movements.writeEntry(tenantId, cash.id(), MovementKind.COUNT_DIFFERENCE, count.difference(),
                count.countedOn(), month, SOURCE, id, memberId, now);
        counts.book(id, movement, reason);
        audit.countDifferenceBooked(actor, id, count.countedAt(), count.difference(), count.countedOn(), reason, now);
        log.debug("Difference of cash count {} booked as movement {}", id, movement);
        return view(counts.card(id).orElseThrow());
    }

    /** Reverses a booked difference line of the account, dated today; answers the reversal line. */
    @Transactional
    AccountMovement reverseMovement(UUID accountId, UUID movementId, @Nullable String rawReason,
            AccessProfile actor) {
        UUID memberId = Objects.requireNonNull(actor.memberId(), "memberId");
        String reason = EntryChecks.requireReason(rawReason);
        LocalDate today = LocalDate.now(clock);
        YearMonth month = bookingMonths.bookFor(today);
        AccountRow account = accounts.lockAccount(accountId).orElseThrow(ApiProblemException::notFound);
        MovementFacts line = accounts.movement(movementId).filter(found -> found.accountId().equals(accountId))
                .orElseThrow(ApiProblemException::notFound);
        boolean bank = line.kind() == MovementKind.BANK_DIFFERENCE;
        if ((line.kind() != MovementKind.COUNT_DIFFERENCE && !bank) || line.reversesMovementId() != null) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.MOVEMENT_NOT_REVERSIBLE,
                    "Only a booked difference line is reversed here.");
        }
        if (line.reversed()) {
            throw EntryChecks.alreadyReversed();
        }
        if (!bank && line.amount().signum() > 0) {
            Opening opening = EntryChecks.requireOpening(account, today);
            CashGuard.check(opening, accounts.dayTotals(account.id(), opening.date(), EntryChecks.LAST_DAY), today,
                    line.amount().negate());
        }
        Instant now = clock.instant();
        UUID reversal;
        try {
            reversal = movements.writeReversal(line.id(), today, month, REVERSAL_SOURCE, line.id(), reason, memberId,
                    now);
        } catch (PersistenceException | DataIntegrityViolationException e) {
            if (ConstraintViolations.violates(e, REVERSED_ONCE)) {
                throw EntryChecks.alreadyReversed();
            }
            throw e;
        }
        if (bank) {
            // a bank difference line is dated in its reconciliation's month, and sourced from that month's row
            audit.bankDifferenceReversed(actor, line.sourceId(), YearMonth.from(line.movedOn()), line.amount(),
                    line.movedOn(), today, reason, now);
        } else {
            CountRow count = counts.card(line.sourceId())
                    .orElseThrow(() -> new IllegalStateException("A count difference line without its count"));
            audit.countDifferenceReversed(actor, count.id(), count.countedAt(), line.amount(), line.movedOn(), today,
                    reason, now);
        }
        log.debug("Difference movement {} reversed by {}", line.id(), reversal);
        MovementRow row = accounts.movementLine(reversal).orElseThrow();
        return journalLines.items(List.of(row), account.opening()).getFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<CashCountAct> act(UUID countId) {
        return counts.card(countId).map(row -> {
            CashCountAct.Booking booking = null;
            if (row.booking() != null) {
                BookingRow b = row.booking();
                CashCountAct.Reversal reversal = b.reversal() == null ? null
                        : new CashCountAct.Reversal(b.reversal().date(), b.reversal().reason());
                booking = new CashCountAct.Booking(b.date(), b.bookingMonth(), b.amount(), b.reason(), b.createdAt(),
                        reversal);
            }
            return new CashCountAct(row.id(), row.countedAt(), row.countedOn(), row.bookBalance(), row.counted(),
                    row.difference(), status(row), row.explanation(), booking);
        });
    }

    /** The list form of a count. */
    static CashCountRow row(CountRow count, @Nullable String createdByName) {
        CashCountRow row = new CashCountRow(count.id(), offset(count.countedAt()), count.countedOn(),
                count.counted().toString(), count.bookBalance().toString(), count.difference().toString(),
                contract(status(count)), offset(count.createdAt()));
        row.setCreatedByName(createdByName);
        return row;
    }

    static Status status(CountRow count) {
        return CashCountFigures.status(count.difference(), count.booking() != null);
    }

    /** The checked fields of a count. */
    private Entry entry(CashCountInput input) {
        Instant now = clock.instant();
        Instant countedAt = input.getCountedAt().toInstant();
        LocalDate day = countedAt.atZone(clock.getZone()).toLocalDate();
        Money counted = Money.parse(input.getCounted());
        String explanation = EntryChecks.blankToNull(input.getExplanation());
        List<FieldProblem> errors = new ArrayList<>();
        if (counted.signum() < 0 || MoneyAccounts.integerDigits(counted) > EntryChecks.MAX_INTEGER_DIGITS) {
            errors.add(new FieldProblem("counted", ProblemCodes.INVALID));
        }
        if (countedAt.isAfter(now.plus(CLOCK_ALLOWANCE)) || day.isAfter(LocalDate.now(clock))) {
            errors.add(new FieldProblem("countedAt", ProblemCodes.DATE_IN_FUTURE));
        }
        if (explanation != null && explanation.length() > MAX_EXPLANATION) {
            errors.add(new FieldProblem("explanation", ProblemCodes.TOO_LONG));
        }
        EntryChecks.refuse(errors);
        return new Entry(countedAt, day, counted, explanation);
    }

    /** The card of a saved count. */
    private CashCount view(CountRow count) {
        AccountRow cash = accounts.account(count.accountId())
                .orElseThrow(() -> new IllegalStateException("The count's account is not visible"));
        // a count is saved only after the opening balance, which is never removed
        Opening opening = Objects.requireNonNull(cash.opening(), "opening");
        CountRow previous = counts.previousOf(count).orElse(null);
        List<UUID> authors = new ArrayList<>();
        authors.add(count.createdBy());
        if (count.booking() != null) {
            authors.add(count.booking().createdBy());
            if (count.booking().reversal() != null) {
                authors.add(count.booking().reversal().createdBy());
            }
        }
        if (previous != null) {
            authors.add(previous.createdBy());
        }
        Map<UUID, String> names = names(authors);
        CashCount view = new CashCount(false, cash.id(), offset(count.countedAt()), count.countedOn(),
                count.counted().toString(), count.bookBalance().toString(), count.difference().toString(),
                contract(status(count)), List.of(), 0L);
        view.setId(count.id());
        view.setExplanation(count.explanation());
        view.setCreatedByName(nameOf(names, count.createdBy()));
        view.setCreatedAt(offset(count.createdAt()));
        view.setBooking(booking(count.booking(), names));
        since(view, cash.id(), opening, count.countedOn(), previous, count.createdAt());
        if (previous != null) {
            view.setPreviousCount(row(previous, nameOf(names, previous.createdBy())));
        }
        return view;
    }

    /** Fills the lines since the previous count. */
    private void since(CashCount view, UUID cashId, Opening opening, LocalDate day, @Nullable CountRow previous,
            Instant upTo) {
        SinceCount since = previous == null ? null : new SinceCount(previous.countedOn(), previous.createdAt());
        List<MovementRow> rows = accounts.movementsSince(cashId, opening.date(), day, since, upTo, SINCE_LIMIT);
        view.setMovementsSince(journalLines.items(rows, opening));
        view.setMovementsSinceTotal(rows.size() < SINCE_LIMIT ? rows.size()
                : accounts.countMovementsSince(cashId, opening.date(), day, since, upTo));
    }

    private static @Nullable CashCountBooking booking(@Nullable BookingRow booking, Map<UUID, String> names) {
        if (booking == null) {
            return null;
        }
        CashCountBooking view = new CashCountBooking(booking.movementId(), booking.date(),
                booking.bookingMonth().toString(), booking.amount().signum() > 0 ? CashCountDifferenceKind.SURPLUS
                        : CashCountDifferenceKind.SHORTAGE, booking.amount().toString(), booking.reason(),
                booking.reversal() != null, offset(booking.createdAt()));
        view.setCreatedByName(nameOf(names, booking.createdBy()));
        if (booking.reversal() != null) {
            ExpenseReversalInfo reversal = new ExpenseReversalInfo(booking.reversal().date(),
                    booking.reversal().reason(), offset(booking.reversal().createdAt()));
            reversal.setCreatedByName(nameOf(names, booking.reversal().createdBy()));
            view.setReversal(reversal);
        }
        return view;
    }

    private Map<UUID, String> names(List<@Nullable UUID> ids) {
        Set<UUID> authors = new HashSet<>();
        ids.stream().filter(Objects::nonNull).forEach(authors::add);
        return authors.isEmpty() ? Map.of() : actorNames.displayNames(authors);
    }

    private static @Nullable String nameOf(Map<UUID, String> names, @Nullable UUID id) {
        return id == null ? null : names.get(id);
    }

    private static CashCountStatus contract(Status status) {
        return CashCountStatus.fromValue(status.name());
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
