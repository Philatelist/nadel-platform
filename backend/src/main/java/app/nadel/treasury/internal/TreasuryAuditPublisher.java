package app.nadel.treasury.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.money.Money;
import app.nadel.treasury.BankMonthStatus;
import app.nadel.treasury.MoneyAccountKind;
import app.nadel.treasury.MovementKind;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes the audit events of the treasury (feature 018 §2.14, technical considerations 2.9) inside the transaction
 * that stores the change, so the Spring Modulith outbox keeps them with it ({@code audit/README.md}). Codes, ids and
 * amounts only; personal texts are sealed before the event exists.
 * <ul>
 *   <li>{@code MONEY_ACCOUNT} (entity the account, label its kind code {@code CASH}/{@code BANK}):
 *       {@code OPENING_BALANCE_SET} with {@code kind}, {@code amount} (two decimals), {@code date} (ISO).</li>
 *   <li>{@code EXPENSE} (entity the expense, label the expense date, ISO): {@code EXPENSE_RECORDED} and
 *       {@code EXPENSE_REVERSED} with {@code amount}, {@code account} (the kind code), {@code category} (the category
 *       id), {@code date} (the expense date), a reversal's {@code reversedOn} and {@code reason} (user content), and the
 *       counterparty and description as {@code counterparty} and {@code description}, sealed by
 *       {@link TreasuryCipher#auditValue} ({@code sealed = true}: opened only for readers with
 *       {@code MEMBERS_VIEW}). New values only.</li>
 *   <li>{@code HANDOVER} (entity the handover, label its deposit slip number: a bank document number, not personal
 *       data): {@code HANDOVER_RECORDED} and {@code HANDOVER_REVERSED} with {@code amount}, {@code date} (the handover
 *       date), {@code documentNumber}, and a reversal's {@code reversedOn} and {@code reason}. New values only.</li>
 *   <li>{@code OTHER_INCOME} (entity the income, label the income date, ISO): {@code OTHER_INCOME_RECORDED} and
 *       {@code OTHER_INCOME_REVERSED} with {@code amount}, {@code account}, {@code category}, {@code date}, a
 *       reversal's {@code reversedOn} and {@code reason}, and the description as {@code description}, sealed like an
 *       expense's. New values only.</li>
 *   <li>{@code CASH_COUNT} (entity the count, label the moment of the count, ISO instant): {@code CASH_COUNTED} with
 *       {@code countedAt}, {@code counted}, {@code bookBalance}, {@code difference} and the {@code explanation} when
 *       given (user content in the clear, like a reversal's reason: it is stored in the clear in {@code cash_count}
 *       too); {@code COUNT_DIFFERENCE_BOOKED} with {@code amount} (the booked line, signed), {@code date} (the count's
 *       day) and {@code reason}; {@code COUNT_DIFFERENCE_REVERSED} with {@code amount} (the reversed line),
 *       {@code date}, {@code reversedOn} and {@code reason}. New values only.</li>
 *   <li>{@code BANK_RECONCILIATION} (entity the month's reconciliation row, label the month {@code YYYY-MM}):
 *       {@code BANK_STATEMENT_ENTERED} with {@code opening}, {@code incoming}, {@code outgoing}, {@code closing} (old
 *       values when figures entered before are replaced); {@code BANK_DIFFERENCE_BOOKED} with {@code amount} (the
 *       booked line, signed), {@code date} (its day) and {@code reason}; {@code BANK_DIFFERENCE_REVERSED} with
 *       {@code amount} (the reversed line), {@code date}, {@code reversedOn} and {@code reason};
 *       {@code BANK_MONTH_RECONCILED} with {@code status} (old and new) and the four statement figures;
 *       {@code BANK_MONTH_REOPENED} by the system ({@link #SYSTEM}: no member) with {@code status} (old and new) and
 *       the line that reopened the month: {@code movement} (its id), {@code kind}, {@code date}, {@code amount}; a
 *       line dated in an earlier month writes one entry for each later month it reopens.</li>
 *   <li>{@code CASHBOOK_CATEGORY} (entity the category, label {@code base:<CODE>} for a seeded category never renamed,
 *       otherwise its name): {@code CASHBOOK_CATEGORY_ADDED} ({@code kind}, {@code name}),
 *       {@code CASHBOOK_CATEGORY_RENAMED} ({@code name} old and new), {@code CASHBOOK_CATEGORY_REORDERED}
 *       ({@code position} old and new), {@code CASHBOOK_CATEGORY_ARCHIVED} ({@code archived} old and new).</li>
 * </ul>
 */
@Component
class TreasuryAuditPublisher {

    static final String MONEY_ACCOUNT = "MONEY_ACCOUNT";
    static final String EXPENSE = "EXPENSE";
    static final String CASHBOOK_CATEGORY = "CASHBOOK_CATEGORY";
    static final String HANDOVER = "HANDOVER";
    static final String OTHER_INCOME = "OTHER_INCOME";
    static final String CASH_COUNT = "CASH_COUNT";
    static final String BANK_RECONCILIATION = "BANK_RECONCILIATION";
    /** The subject of what the treasury records by itself (a bank month reopened by a later line). */
    static final String SYSTEM = "system:treasury";

    /** Every action the treasury writes; each is a value of the contract's {@code AuditAction}. */
    enum Action {
        OPENING_BALANCE_SET,
        EXPENSE_RECORDED,
        EXPENSE_REVERSED,
        CASHBOOK_CATEGORY_ADDED,
        CASHBOOK_CATEGORY_RENAMED,
        CASHBOOK_CATEGORY_REORDERED,
        CASHBOOK_CATEGORY_ARCHIVED,
        HANDOVER_RECORDED,
        HANDOVER_REVERSED,
        OTHER_INCOME_RECORDED,
        OTHER_INCOME_REVERSED,
        CASH_COUNTED,
        COUNT_DIFFERENCE_BOOKED,
        COUNT_DIFFERENCE_REVERSED,
        BANK_STATEMENT_ENTERED,
        BANK_DIFFERENCE_BOOKED,
        BANK_DIFFERENCE_REVERSED,
        BANK_MONTH_RECONCILED,
        BANK_MONTH_REOPENED
    }

    /** What an expense entry records (the expense as entered; the texts in the clear, sealed here). */
    record ExpenseFacts(UUID expenseId, MoneyAccountKind account, Money amount, UUID categoryId, LocalDate date,
            @Nullable String counterparty, @Nullable String description) {
    }

    /** What a handover entry records. */
    record HandoverFacts(UUID handoverId, Money amount, LocalDate date, String documentNumber) {
    }

    /** What an other income entry records (the description in the clear, sealed here). */
    record OtherIncomeFacts(UUID incomeId, MoneyAccountKind account, Money amount, UUID categoryId, LocalDate date,
            @Nullable String description) {
    }

    /** What a cash count records. */
    record CountFacts(UUID countId, Instant countedAt, Money counted, Money bookBalance, Money difference,
            @Nullable String explanation) {
    }

    private final ApplicationEventPublisher events;
    private final TreasuryCipher cipher;

    TreasuryAuditPublisher(ApplicationEventPublisher events, TreasuryCipher cipher) {
        this.events = events;
        this.cipher = cipher;
    }

    void openingBalanceSet(AccessProfile actor, UUID accountId, MoneyAccountKind kind, Money amount, LocalDate date,
            Instant at) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        events.publishEvent(new AuditEvent(UUID.randomUUID(), tenantId, at, actorOf(actor),
                Action.OPENING_BALANCE_SET.name(), MONEY_ACCOUNT, accountId, kind.name(),
                List.of(new FieldChange("kind", null, kind.name()),
                        new FieldChange("amount", null, amount.toString()),
                        new FieldChange("date", null, date.toString()))));
    }

    void expenseRecorded(AccessProfile actor, ExpenseFacts expense, Instant at) {
        publishExpense(actor, Action.EXPENSE_RECORDED, expense, List.of(), at);
    }

    void expenseReversed(AccessProfile actor, ExpenseFacts expense, LocalDate reversedOn, String reason, Instant at) {
        publishExpense(actor, Action.EXPENSE_REVERSED, expense,
                List.of(new FieldChange("reversedOn", null, reversedOn.toString()),
                        new FieldChange("reason", null, reason)), at);
    }

    void handoverRecorded(AccessProfile actor, HandoverFacts handover, Instant at) {
        publishHandover(actor, Action.HANDOVER_RECORDED, handover, List.of(), at);
    }

    void handoverReversed(AccessProfile actor, HandoverFacts handover, LocalDate reversedOn, String reason,
            Instant at) {
        publishHandover(actor, Action.HANDOVER_REVERSED, handover, reversal(reversedOn, reason), at);
    }

    void otherIncomeRecorded(AccessProfile actor, OtherIncomeFacts income, Instant at) {
        publishOtherIncome(actor, Action.OTHER_INCOME_RECORDED, income, List.of(), at);
    }

    void otherIncomeReversed(AccessProfile actor, OtherIncomeFacts income, LocalDate reversedOn, String reason,
            Instant at) {
        publishOtherIncome(actor, Action.OTHER_INCOME_REVERSED, income, reversal(reversedOn, reason), at);
    }

    void cashCounted(AccessProfile actor, CountFacts count, Instant at) {
        List<FieldChange> changes = new ArrayList<>(List.of(
                new FieldChange("countedAt", null, count.countedAt().toString()),
                new FieldChange("counted", null, count.counted().toString()),
                new FieldChange("bookBalance", null, count.bookBalance().toString()),
                new FieldChange("difference", null, count.difference().toString())));
        if (count.explanation() != null) {
            changes.add(new FieldChange("explanation", null, count.explanation()));
        }
        publishCount(actor, Action.CASH_COUNTED, count.countId(), count.countedAt(), changes, at);
    }

    void countDifferenceBooked(AccessProfile actor, UUID countId, Instant countedAt, Money amount, LocalDate date,
            String reason, Instant at) {
        publishCount(actor, Action.COUNT_DIFFERENCE_BOOKED, countId, countedAt, List.of(
                new FieldChange("amount", null, amount.toString()),
                new FieldChange("date", null, date.toString()),
                new FieldChange("reason", null, reason)), at);
    }

    void countDifferenceReversed(AccessProfile actor, UUID countId, Instant countedAt, Money amount, LocalDate date,
            LocalDate reversedOn, String reason, Instant at) {
        List<FieldChange> changes = new ArrayList<>(List.of(
                new FieldChange("amount", null, amount.toString()),
                new FieldChange("date", null, date.toString())));
        changes.addAll(reversal(reversedOn, reason));
        publishCount(actor, Action.COUNT_DIFFERENCE_REVERSED, countId, countedAt, changes, at);
    }

    void bankStatementEntered(AccessProfile actor, UUID reconciliationId, YearMonth month,
            StatementRules.@Nullable Figures before, StatementRules.Figures after, Instant at) {
        publishBank(actorOf(actor), Action.BANK_STATEMENT_ENTERED, Objects.requireNonNull(actor.tenantId(), "tenantId"),
                reconciliationId, month, figures(before, after), at);
    }

    void bankDifferenceBooked(AccessProfile actor, UUID reconciliationId, YearMonth month, Money amount,
            LocalDate date, String reason, Instant at) {
        publishBank(actorOf(actor), Action.BANK_DIFFERENCE_BOOKED, Objects.requireNonNull(actor.tenantId(), "tenantId"),
                reconciliationId, month, List.of(new FieldChange("amount", null, amount.toString()),
                        new FieldChange("date", null, date.toString()),
                        new FieldChange("reason", null, reason)), at);
    }

    void bankDifferenceReversed(AccessProfile actor, UUID reconciliationId, YearMonth month, Money amount,
            LocalDate date, LocalDate reversedOn, String reason, Instant at) {
        List<FieldChange> changes = new ArrayList<>(List.of(
                new FieldChange("amount", null, amount.toString()),
                new FieldChange("date", null, date.toString())));
        changes.addAll(reversal(reversedOn, reason));
        publishBank(actorOf(actor), Action.BANK_DIFFERENCE_REVERSED,
                Objects.requireNonNull(actor.tenantId(), "tenantId"), reconciliationId, month, changes, at);
    }

    void bankMonthReconciled(AccessProfile actor, UUID reconciliationId, YearMonth month, BankMonthStatus from,
            StatementRules.Figures statement, Instant at) {
        List<FieldChange> changes = new ArrayList<>();
        changes.add(new FieldChange("status", from.name(), BankMonthStatus.RECONCILED.name()));
        changes.addAll(figures(null, statement));
        publishBank(actorOf(actor), Action.BANK_MONTH_RECONCILED, Objects.requireNonNull(actor.tenantId(), "tenantId"),
                reconciliationId, month, changes, at);
    }

    /**
     * A reconciled month set back to {@code NEEDS_RECHECK} by a bank line dated in it or in an earlier month (one
     * entry per reopened month); recorded by the system.
     */
    void bankMonthReopened(UUID tenantId, UUID reconciliationId, YearMonth month, UUID movementId, MovementKind kind,
            LocalDate date, Money amount, Instant at) {
        publishBank(new Actor(null, SYSTEM, ""), Action.BANK_MONTH_REOPENED, tenantId, reconciliationId, month, List.of(
                new FieldChange("status", BankMonthStatus.RECONCILED.name(), BankMonthStatus.NEEDS_RECHECK.name()),
                new FieldChange("movement", null, movementId.toString()),
                new FieldChange("kind", null, kind.name()),
                new FieldChange("date", null, date.toString()),
                new FieldChange("amount", null, amount.toString())), at);
    }

    void category(AccessProfile actor, Action action, UUID categoryId, String label, List<FieldChange> changes,
            Instant at) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        events.publishEvent(new AuditEvent(UUID.randomUUID(), tenantId, at, actorOf(actor), action.name(),
                CASHBOOK_CATEGORY, categoryId, label, changes));
    }

    private void publishExpense(AccessProfile actor, Action action, ExpenseFacts expense, List<FieldChange> more,
            Instant at) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        UUID eventId = UUID.randomUUID();
        List<FieldChange> changes = new ArrayList<>(List.of(
                new FieldChange("amount", null, expense.amount().toString()),
                new FieldChange("account", null, expense.account().name()),
                new FieldChange("category", null, expense.categoryId().toString()),
                new FieldChange("date", null, expense.date().toString())));
        changes.addAll(more);
        FieldChange counterparty = cipher.auditValue(tenantId, eventId, "counterparty", expense.counterparty());
        if (counterparty != null) {
            changes.add(counterparty);
        }
        FieldChange description = cipher.auditValue(tenantId, eventId, "description", expense.description());
        if (description != null) {
            changes.add(description);
        }
        events.publishEvent(new AuditEvent(eventId, tenantId, at, actorOf(actor), action.name(), EXPENSE,
                expense.expenseId(), expense.date().toString(), changes));
    }

    private void publishHandover(AccessProfile actor, Action action, HandoverFacts handover, List<FieldChange> more,
            Instant at) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        List<FieldChange> changes = new ArrayList<>(List.of(
                new FieldChange("amount", null, handover.amount().toString()),
                new FieldChange("date", null, handover.date().toString()),
                new FieldChange("documentNumber", null, handover.documentNumber())));
        changes.addAll(more);
        events.publishEvent(new AuditEvent(UUID.randomUUID(), tenantId, at, actorOf(actor), action.name(), HANDOVER,
                handover.handoverId(), handover.documentNumber(), changes));
    }

    private void publishOtherIncome(AccessProfile actor, Action action, OtherIncomeFacts income,
            List<FieldChange> more, Instant at) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        UUID eventId = UUID.randomUUID();
        List<FieldChange> changes = new ArrayList<>(List.of(
                new FieldChange("amount", null, income.amount().toString()),
                new FieldChange("account", null, income.account().name()),
                new FieldChange("category", null, income.categoryId().toString()),
                new FieldChange("date", null, income.date().toString())));
        changes.addAll(more);
        FieldChange description = cipher.auditValue(tenantId, eventId, "description", income.description());
        if (description != null) {
            changes.add(description);
        }
        events.publishEvent(new AuditEvent(eventId, tenantId, at, actorOf(actor), action.name(), OTHER_INCOME,
                income.incomeId(), income.date().toString(), changes));
    }

    private void publishCount(AccessProfile actor, Action action, UUID countId, Instant countedAt,
            List<FieldChange> changes, Instant at) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        events.publishEvent(new AuditEvent(UUID.randomUUID(), tenantId, at, actorOf(actor), action.name(), CASH_COUNT,
                countId, countedAt.toString(), changes));
    }

    private void publishBank(Actor actor, Action action, UUID tenantId, UUID reconciliationId, YearMonth month,
            List<FieldChange> changes, Instant at) {
        events.publishEvent(new AuditEvent(UUID.randomUUID(), tenantId, at, actor, action.name(), BANK_RECONCILIATION,
                reconciliationId, month.toString(), changes));
    }

    /** The four figures as fields; old values from {@code before} when given. */
    private static List<FieldChange> figures(StatementRules.@Nullable Figures before, StatementRules.Figures after) {
        return List.of(
                new FieldChange("opening", before == null ? null : before.opening().toString(),
                        after.opening().toString()),
                new FieldChange("incoming", before == null ? null : before.incoming().toString(),
                        after.incoming().toString()),
                new FieldChange("outgoing", before == null ? null : before.outgoing().toString(),
                        after.outgoing().toString()),
                new FieldChange("closing", before == null ? null : before.closing().toString(),
                        after.closing().toString()));
    }

    private static List<FieldChange> reversal(LocalDate reversedOn, String reason) {
        return List.of(new FieldChange("reversedOn", null, reversedOn.toString()),
                new FieldChange("reason", null, reason));
    }

    private static Actor actorOf(AccessProfile actor) {
        return new Actor(actor.memberId(), actor.subject(), actor.displayName());
    }
}
