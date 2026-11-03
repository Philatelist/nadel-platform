package app.nadel.treasury.internal;

import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.PaymentMoneyMoved;
import app.nadel.ledger.PaymentRef;
import app.nadel.ledger.PaymentSource;
import app.nadel.treasury.MoneyAccountKind;
import app.nadel.treasury.internal.TreasuryQueries.AccountRow;
import app.nadel.treasury.internal.TreasuryQueries.PaymentMovement;
import jakarta.persistence.EntityManager;
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
 */
@Component
class MovementWriter {

    private static final Logger log = LoggerFactory.getLogger(MovementWriter.class);

    private static final String INSERT_PAYMENT = """
            insert into account_movement (tenant_id, account_id, kind, amount, moved_on, booking_month, source_type,
                                          source_id, payment_row_id, reverses_movement_id, reason, created_by,
                                          created_at)
            values (:tenant, :account, 'MEMBER_PAYMENT', :amount, :movedOn, :month, 'PAYMENT', :row, :row,
                    cast(:reverses as uuid), cast(:reason as text), cast(:createdBy as uuid), :createdAt)
            on conflict (tenant_id, payment_row_id) where payment_row_id is not null do nothing
            """;

    private final TreasuryQueries queries;
    private final LedgerReadModel ledger;
    private final EntityManager entityManager;

    MovementWriter(TreasuryQueries queries, LedgerReadModel ledger, EntityManager entityManager) {
        this.queries = queries;
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
            inserted += entityManager.createNativeQuery(INSERT_PAYMENT)
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
                    .executeUpdate();
        }
        log.debug("{} payment movements written for {} lines", inserted, lines.size());
        return inserted;
    }

    static MoneyAccountKind kindOf(PaymentSource source) {
        return switch (source) {
            case CASH -> MoneyAccountKind.CASH;
            case BANK -> MoneyAccountKind.BANK;
        };
    }
}
