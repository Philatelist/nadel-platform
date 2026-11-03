package app.nadel.treasury.internal;

import app.nadel.money.Money;
import app.nadel.treasury.MoneyAccountKind;
import app.nadel.treasury.MovementKind;
import app.nadel.treasury.internal.AccountBalances.DayTotal;
import app.nadel.treasury.internal.AccountBalances.Opening;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads of the treasury tables: parameterised native queries in the caller's transaction
 * ({@code Propagation.MANDATORY}), under RLS like every query of the application, so another cooperative's rows are
 * never seen. The {@code lock...} reads take the account row {@code FOR UPDATE} (the column grant on the opening balance
 * gives {@code nadel_app} the {@code UPDATE} privilege a row lock needs): every writer of an account's journal or
 * opening balance holds it, so they run one after the other.
 */
@Component
class TreasuryQueries {

    private static final String ACCOUNT_COLUMNS = "a.id, a.kind, a.opening_amount, a.opening_date";

    private static final String ACCOUNTS = "select " + ACCOUNT_COLUMNS + " from money_account a "
            + "order by case a.kind when 'CASH' then 0 else 1 end";

    private static final String ACCOUNT = "select " + ACCOUNT_COLUMNS + " from money_account a where a.id = :id";

    private static final String LOCK_ACCOUNT = ACCOUNT + " for update";

    private static final String LOCK_ACCOUNT_OF_KIND = "select " + ACCOUNT_COLUMNS
            + " from money_account a where a.kind = :kind for update";

    /** The day totals of an account over a range ({@code account_movement_account_day_idx}). */
    private static final String DAY_TOTALS = """
            select m.moved_on, sum(m.amount)
            from account_movement m
            where m.account_id = :account
              and m.moved_on between :from and :to
            group by m.moved_on
            order by m.moved_on
            """;

    private static final String COUNT_MOVEMENTS = """
            select count(*)
            from account_movement m
            where m.account_id = :account
              and m.moved_on between :from and :to
            """;

    /**
     * A page of an account's journal, oldest first, each line with whether a reversal points at it, the document
     * number of a handover line and the category of an expense or other income line (a reversal line's entry row
     * carries the same values).
     */
    private static final String MOVEMENTS = """
            select m.id, m.kind, m.amount, m.moved_on, m.booking_month, m.source_type, m.source_id, m.payment_row_id,
                   m.reverses_movement_id, m.reason, m.created_by, m.created_at, r.id is not null as reversed,
                   h.document_number, coalesce(e.category_id, o.category_id)
            from account_movement m
            left join account_movement r on r.tenant_id = m.tenant_id and r.reverses_movement_id = m.id
            left join handover h on m.source_type = 'HANDOVER' and h.tenant_id = m.tenant_id and h.id = m.source_id
            left join expense e on m.source_type = 'EXPENSE' and e.tenant_id = m.tenant_id and e.id = m.source_id
            left join other_income o on m.source_type = 'OTHER_INCOME' and o.tenant_id = m.tenant_id
                                        and o.id = m.source_id
            where m.account_id = :account
              and m.moved_on between :from and :to
            order by m.moved_on, m.created_at, m.id
            offset :offset limit :limit
            """;

    /** The movement written for a ledger payment row ({@code account_movement_payment_row_uq}). */
    private static final String PAYMENT_MOVEMENT = """
            select m.id, m.account_id
            from account_movement m
            where m.payment_row_id = :row
            """;

    private final EntityManager entityManager;

    TreasuryQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** A money account as stored; {@code opening} null while the opening balance is not set. */
    record AccountRow(UUID id, MoneyAccountKind kind, @Nullable Opening opening) {
    }

    /**
     * One line of an account's journal as stored; {@code documentNumber} is a handover's, {@code categoryId} an
     * expense's or other income's.
     */
    record MovementRow(UUID id, MovementKind kind, Money amount, LocalDate movedOn, YearMonth bookingMonth,
            String sourceType, UUID sourceId, @Nullable UUID paymentRowId, @Nullable UUID reversesMovementId,
            @Nullable String reason, @Nullable UUID createdBy, Instant createdAt, boolean reversed,
            @Nullable String documentNumber, @Nullable UUID categoryId) {
    }

    /** The movement of a ledger payment row and its account. */
    record PaymentMovement(UUID id, UUID accountId) {
    }

    /** The accounts of the cooperative, cash first. */
    @Transactional(propagation = Propagation.MANDATORY)
    List<AccountRow> accounts() {
        return entityManager.createNativeQuery(ACCOUNTS).getResultList().stream()
                .map(row -> account((Object[]) row)).toList();
    }

    /** The account; empty when unknown or of another cooperative. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<AccountRow> account(UUID id) {
        return single(entityManager.createNativeQuery(ACCOUNT).setParameter("id", id).getResultList());
    }

    /** The account locked {@code FOR UPDATE}; empty when unknown or of another cooperative. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<AccountRow> lockAccount(UUID id) {
        return single(entityManager.createNativeQuery(LOCK_ACCOUNT).setParameter("id", id).getResultList());
    }

    /** The cooperative's account of the kind locked {@code FOR UPDATE}; empty when it has none. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<AccountRow> lockAccountOfKind(MoneyAccountKind kind) {
        return single(entityManager.createNativeQuery(LOCK_ACCOUNT_OF_KIND).setParameter("kind", kind.name())
                .getResultList());
    }

    /** The sums of the account's movements per day from {@code from} to {@code to}, days without movements absent. */
    @Transactional(propagation = Propagation.MANDATORY)
    List<DayTotal> dayTotals(UUID accountId, LocalDate from, LocalDate to) {
        return entityManager.createNativeQuery(DAY_TOTALS)
                .setParameter("account", accountId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList().stream()
                .map(row -> {
                    Object[] v = (Object[]) row;
                    return new DayTotal(date(v[0]), Money.of((BigDecimal) v[1]));
                })
                .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    long countMovements(UUID accountId, LocalDate from, LocalDate to) {
        return ((Number) entityManager.createNativeQuery(COUNT_MOVEMENTS)
                .setParameter("account", accountId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult()).longValue();
    }

    /** A page of the account's movements dated from {@code from} to {@code to}, oldest first. */
    @Transactional(propagation = Propagation.MANDATORY)
    List<MovementRow> movements(UUID accountId, LocalDate from, LocalDate to, long offset, int limit) {
        return entityManager.createNativeQuery(MOVEMENTS)
                .setParameter("account", accountId)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("offset", offset)
                .setParameter("limit", limit)
                .getResultList().stream()
                .map(row -> {
                    Object[] v = (Object[]) row;
                    return new MovementRow((UUID) v[0], MovementKind.valueOf((String) v[1]),
                            Money.of((BigDecimal) v[2]), date(v[3]), YearMonth.from(date(v[4])), (String) v[5],
                            (UUID) v[6], (UUID) v[7], (UUID) v[8], (String) v[9], (UUID) v[10], (Instant) v[11],
                            (Boolean) v[12], (String) v[13], (UUID) v[14]);
                })
                .toList();
    }

    /** The movement written for the ledger payment row; empty when it has none. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<PaymentMovement> paymentMovement(UUID ledgerRowId) {
        List<?> rows = entityManager.createNativeQuery(PAYMENT_MOVEMENT).setParameter("row", ledgerRowId)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] v = (Object[]) rows.getFirst();
        return Optional.of(new PaymentMovement((UUID) v[0], (UUID) v[1]));
    }

    private static Optional<AccountRow> single(List<?> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(account((Object[]) rows.getFirst()));
    }

    private static AccountRow account(Object[] v) {
        Opening opening = v[2] == null ? null : new Opening(Money.of((BigDecimal) v[2]), date(v[3]));
        return new AccountRow((UUID) v[0], MoneyAccountKind.valueOf((String) v[1]), opening);
    }

    private static LocalDate date(Object value) {
        Objects.requireNonNull(value, "date");
        return value instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate) value;
    }
}
