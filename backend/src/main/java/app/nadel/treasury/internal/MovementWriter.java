package app.nadel.treasury.internal;

import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.PaymentMoneyMoved;
import app.nadel.ledger.PaymentRef;
import app.nadel.ledger.PaymentSource;
import app.nadel.money.Money;
import app.nadel.tenancy.TenantContext;
import app.nadel.treasury.MoneyAccountKind;
import app.nadel.treasury.MovementKind;
import app.nadel.treasury.internal.TreasuryQueries.AccountRow;
import app.nadel.treasury.internal.TreasuryQueries.PaymentMovement;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one writer of {@code account_movement} (append-only: inserts only). Runs in the caller's transaction
 * ({@code Propagation.MANDATORY}).
 * <p>
 * <b>Member payments</b> ({@link #writePaymentLines}, from {@link PaymentMovementListener} inside the payment's own
 * transaction): the accounts the lines move money on are locked {@code FOR UPDATE}, cash before bank (the lock order of
 * the ledger README: after the billing month and the receipt counter the payment path already holds), then one
 * {@code MEMBER_PAYMENT} movement per payment row is inserted with {@code ON CONFLICT (tenant_id, payment_row_id) DO
 * NOTHING}, so a row that has its movement already (written by the V32 backfill, or a second delivery) is left as it
 * is. A payment's movement is dated the day received in the payment's billing month; a reversal's movement is the
 * negated amount on the account of the payment's movement, dated the day of the reversal in the month the ledger gave it,
 * pointing at the payment's movement with the reversal's reason (read from the ledger row, {@link LedgerReadModel#paymentRefs}).
 * A cooperative without the account, or a reversal whose payment has no movement, is a broken invariant (V32 seeds the
 * accounts and backfills every payment): an {@link IllegalStateException}, which rolls the payment back. Logs ids and
 * counts only.
 * <p>
 * <b>Entries of the cash book</b> ({@link #writeEntry}, {@link #writeReversal}: an expense, a handover (two lines), an
 * other income and their reversals; later slices add differences): the caller has booked the month with
 * {@code ledger.BookingMonths.bookFor} and holds the account row {@code FOR UPDATE} (the treasury lock order), and has
 * checked the entry (opening balance, the no-negative-cash rule). A reversal has the kind and account of the line it
 * reverses, the negated amount, the reason, and the day and month the caller gives (today and its open month).
 * <p>
 * <b>Reopening a reconciled bank month</b> (feature 018 §2.7, technical considerations 2.2, 2.5): every movement this
 * writer inserts — a member payment or its reversal, an expense, other income, a handover line, a bank difference, any
 * reversal — is checked right after its insert, in the same transaction and after the account lock the caller holds
 * (the lock order ends: … bank account → {@code bank_reconciliation} rows, locked in month order). A bank line counted
 * in the books (dated on or after the opening date) sets its calendar month and every later month that is
 * {@code RECONCILED} (their opening balances change with it) to {@code NEEDS_RECHECK}, each with the line and the time
 * ({@link BankReconciliationQueries#reopen}), and the system's audit entry {@code BANK_MONTH_REOPENED} is published for
 * each reopened month. A cash line, a month not reconciled, a month already {@code NEEDS_RECHECK} (the first reopening
 * is kept until the month is reconciled again) and a line before the opening date reopen nothing. Being the one
 * writer, this is the one place the rule lives.
 */
@Component
class MovementWriter {

    private static final Logger log = LoggerFactory.getLogger(MovementWriter.class);

    /** Answers the id of the inserted movement, or nothing when the row has its movement already. */
    private static final String INSERT_PAYMENT = """
            with inserted as (
                insert into account_movement (tenant_id, account_id, kind, amount, moved_on, booking_month, source_type,
                                              source_id, payment_row_id, reverses_movement_id, reason, created_by,
                                              created_at)
                values (:tenant, :account, 'MEMBER_PAYMENT', :amount, :movedOn, :month, 'PAYMENT', :row, :row,
                        cast(:reverses as uuid), cast(:reason as text), cast(:createdBy as uuid), :createdAt)
                on conflict (tenant_id, payment_row_id) where payment_row_id is not null do nothing
                returning id)
            select id from inserted
            """;

    private static final String INSERT_ENTRY = """
            insert into account_movement (id, tenant_id, account_id, kind, amount, moved_on, booking_month, source_type,
                                          source_id, created_by, created_at)
            values (:id, :tenant, :account, :kind, :amount, :movedOn, :month, :sourceType, :sourceId,
                    cast(:createdBy as uuid), :createdAt)
            """;

    private static final String INSERT_REVERSAL = """
            insert into account_movement (id, tenant_id, account_id, kind, amount, moved_on, booking_month, source_type,
                                          source_id, reverses_movement_id, reason, created_by, created_at)
            select :id, m.tenant_id, m.account_id, m.kind, -m.amount, :movedOn, :month, :sourceType, :sourceId, m.id,
                   :reason, cast(:createdBy as uuid), :createdAt
            from account_movement m
            where m.id = :original
            """;

    private final TreasuryQueries queries;
    private final BankReconciliationQueries reconciliations;
    private final TreasuryAuditPublisher audit;
    private final LedgerReadModel ledger;
    private final EntityManager entityManager;

    MovementWriter(TreasuryQueries queries, BankReconciliationQueries reconciliations, TreasuryAuditPublisher audit,
            LedgerReadModel ledger, EntityManager entityManager) {
        this.queries = queries;
        this.reconciliations = reconciliations;
        this.audit = audit;
        this.ledger = ledger;
        this.entityManager = entityManager;
    }

    /**
     * Writes the movements of the payment rows of one event of the cooperative {@code tenantId} (the one bound to the
     * transaction).
     *
     * @return the movements inserted (a line whose row has its movement already inserts none)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    int writePaymentLines(UUID tenantId, List<PaymentMoneyMoved.Line> lines) {
        EnumSet<PaymentSource> sources = EnumSet.noneOf(PaymentSource.class);
        lines.forEach(line -> sources.add(line.source()));
        Map<PaymentSource, AccountRow> accounts = new EnumMap<>(PaymentSource.class);
        // cash before bank: PaymentSource declares CASH first, and an EnumSet iterates in that order
        for (PaymentSource source : sources) {
            MoneyAccountKind kind = kindOf(source);
            accounts.put(source, queries.lockAccountOfKind(kind)
                    .orElseThrow(() -> new IllegalStateException("The cooperative has no " + kind + " account")));
        }
        int inserted = 0;
        for (PaymentMoneyMoved.Line line : lines) {
            UUID account = accounts.get(line.source()).id();
            UUID reverses = null;
            String reason = null;
            if (line.reversal()) {
                PaymentMovement original = queries.paymentMovement(line.paymentId())
                        .orElseThrow(() -> new IllegalStateException("The reversed payment has no movement"));
                PaymentRef ref = Objects.requireNonNull(ledger.paymentRefs(List.of(line.ledgerRowId()))
                        .get(line.ledgerRowId()), "the reversal row is not visible");
                account = original.accountId();
                reverses = original.id();
                reason = ref.reason();
            }
            List<?> ids = entityManager.createNativeQuery(INSERT_PAYMENT)
                    .setParameter("tenant", tenantId)
                    .setParameter("account", account)
                    .setParameter("amount", line.amount().value())
                    .setParameter("movedOn", line.movedOn())
                    .setParameter("month", line.bookingMonth().atDay(1))
                    .setParameter("row", line.ledgerRowId())
                    .setParameter("reverses", reverses)
                    .setParameter("reason", reason)
                    .setParameter("createdBy", line.createdBy())
                    .setParameter("createdAt", line.createdAt())
                    .getResultList();
            for (Object id : ids) {
                reopenBankMonth(tenantId, (UUID) id, line.createdAt());
                inserted++;
            }
        }
        log.debug("{} payment movements written for {} lines", inserted, lines.size());
        return inserted;
    }

    /**
     * Inserts one movement of a cash book entry.
     *
     * @param amount signed: money in positive, money out negative, never 0.00
     * @return the id of the movement
     */
    @Transactional(propagation = Propagation.MANDATORY)
    UUID writeEntry(UUID tenantId, UUID accountId, MovementKind kind, Money amount, LocalDate movedOn,
            YearMonth bookingMonth, String sourceType, UUID sourceId, UUID createdBy, Instant createdAt) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery(INSERT_ENTRY)
                .setParameter("id", id)
                .setParameter("tenant", tenantId)
                .setParameter("account", accountId)
                .setParameter("kind", kind.name())
                .setParameter("amount", amount.value())
                .setParameter("movedOn", movedOn)
                .setParameter("month", bookingMonth.atDay(1))
                .setParameter("sourceType", sourceType)
                .setParameter("sourceId", sourceId)
                .setParameter("createdBy", createdBy)
                .setParameter("createdAt", createdAt)
                .executeUpdate();
        reopenBankMonth(tenantId, id, createdAt);
        log.debug("Movement {} written", id);
        return id;
    }

    /**
     * Inserts the reversal of a movement: same account and kind, the negated amount, dated {@code movedOn} in
     * {@code bookingMonth}, with the reason. A movement is reversed once (the unique index
     * {@code account_movement_reversed_once_uq}, SQLSTATE 23505, is the backstop of the caller's check).
     *
     * @return the id of the reversal
     * @throws IllegalStateException when the movement is not visible
     */
    @Transactional(propagation = Propagation.MANDATORY)
    UUID writeReversal(UUID originalId, LocalDate movedOn, YearMonth bookingMonth, String sourceType,
            UUID sourceId, String reason, UUID createdBy, Instant createdAt) {
        UUID id = UUID.randomUUID();
        int inserted = entityManager.createNativeQuery(INSERT_REVERSAL)
                .setParameter("id", id)
                .setParameter("movedOn", movedOn)
                .setParameter("month", bookingMonth.atDay(1))
                .setParameter("sourceType", sourceType)
                .setParameter("sourceId", sourceId)
                .setParameter("reason", reason)
                .setParameter("createdBy", createdBy)
                .setParameter("createdAt", createdAt)
                .setParameter("original", originalId)
                .executeUpdate();
        if (inserted != 1) {
            throw new IllegalStateException("The reversed movement is not visible");
        }
        reopenBankMonth(TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("No cooperative is bound")), id, createdAt);
        log.debug("Movement {} reversed by {}", originalId, id);
        return id;
    }

    /**
     * Reopens the reconciled bank month the movement is dated in and every later reconciled month, each with the
     * system's audit entry.
     */
    private void reopenBankMonth(UUID tenantId, UUID movementId, Instant at) {
        for (BankReconciliationQueries.Reopened month : reconciliations.reopen(movementId, at)) {
            audit.bankMonthReopened(tenantId, month.reconciliationId(), month.month(), movementId, month.kind(),
                    month.date(), month.amount(), at);
            log.debug("Bank reconciliation {} reopened by movement {}", month.reconciliationId(), movementId);
        }
    }

    static MoneyAccountKind kindOf(PaymentSource source) {
        return switch (source) {
            case CASH -> MoneyAccountKind.CASH;
            case BANK -> MoneyAccountKind.BANK;
        };
    }
}
