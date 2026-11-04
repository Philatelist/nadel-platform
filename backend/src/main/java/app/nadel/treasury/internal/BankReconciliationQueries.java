package app.nadel.treasury.internal;

import app.nadel.money.Money;
import app.nadel.treasury.BankMonthStatus;
import app.nadel.treasury.MovementKind;
import app.nadel.treasury.internal.BankComparison.Flow;
import app.nadel.treasury.internal.StatementRules.Figures;
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
 * The reads and writes of {@code bank_reconciliation} and {@code bank_difference} (V32, V34) and the bank flows of a
 * month: parameterised native queries in the caller's transaction ({@code Propagation.MANDATORY}), under RLS. Writes
 * touch only the columns granted to {@code nadel_app}; the guard trigger {@code bank_reconciliation_guard} enforces the
 * status machine. Every writer holds the bank account row {@code FOR UPDATE} first (the treasury lock order: … bank
 * account → {@code bank_reconciliation} rows, several of them in month order), so the writes of one cooperative's
 * months run one after the other.
 */
@Component
class BankReconciliationQueries {

    private static final String COLUMNS = """
            r.id, r.month, r.statement_opening, r.statement_incoming, r.statement_outgoing, r.statement_closing,
            r.status, r.reconciled_by, r.reconciled_at, r.reopened_movement_id, r.reopened_at
            """;

    private static final String FIND = "select " + COLUMNS + " from bank_reconciliation r where r.month = :month";

    private static final String INSERT = """
            insert into bank_reconciliation (id, tenant_id, month, statement_opening, statement_incoming,
                                             statement_outgoing, statement_closing, created_by, created_at)
            values (:id, :tenant, :month, :opening, :incoming, :outgoing, :closing, cast(:by as uuid), :at)
            """;

    private static final String UPDATE_STATEMENT = """
            update bank_reconciliation
            set statement_opening = :opening, statement_incoming = :incoming, statement_outgoing = :outgoing,
                statement_closing = :closing
            where id = :id
            """;

    private static final String CONFIRM = """
            update bank_reconciliation
            set status = 'RECONCILED', reconciled_by = cast(:by as uuid), reconciled_at = :at
            where id = :id
            """;

    /**
     * Sets the reconciled month a bank movement is dated in, and every later reconciled month (their opening balances
     * change with the line), back to {@code NEEDS_RECHECK} with the movement and the time, and answers what it
     * reopened, month by month. Nothing when the movement is not a bank line counted in the books (on the cash, or
     * dated before the opening date); a month {@code NOT_RECONCILED} or already {@code NEEDS_RECHECK} (it keeps its
     * first reopening) is left as it is. The rows are locked in month order ({@code target}) before the update, so two
     * writers never lock the same rows in a different order (the caller holds the bank account row first anyway).
     */
    private static final String REOPEN = """
            with line as (
                select m.id, m.tenant_id, m.kind, m.amount, m.moved_on
                from account_movement m
                join money_account a on a.tenant_id = m.tenant_id and a.id = m.account_id
                where m.id = :movement
                  and a.kind = 'BANK'
                  and m.moved_on >= a.opening_date),
            target as materialized (
                select r.id
                from bank_reconciliation r
                join line on r.tenant_id = line.tenant_id
                where r.month >= date_trunc('month', line.moved_on)::date
                  and r.status = 'RECONCILED'
                order by r.month
                for update of r),
            reopened as (
                update bank_reconciliation r
                set status = 'NEEDS_RECHECK', reopened_movement_id = line.id, reopened_at = :at
                from target, line
                where r.id = target.id
                returning r.id, r.month, line.kind, line.amount, line.moved_on)
            select id, month, kind, amount, moved_on from reopened order by month
            """;

    /**
     * The flows of the account per day from {@code :from} to {@code :to}: the lines of one day summed by whether they
     * reverse another line and by their sign, so {@link BankComparison} can tell receipts from spendings.
     */
    private static final String FLOWS = """
            select m.moved_on, m.reverses_movement_id is not null, sum(m.amount)
            from account_movement m
            where m.account_id = :account
              and m.moved_on between :from and :to
            group by m.moved_on, m.reverses_movement_id is not null, sign(m.amount)
            order by m.moved_on
            """;

    private static final String STATUSES = """
            select r.month, r.status
            from bank_reconciliation r
            where r.month between :from and :to
            order by r.month desc
            limit :limit
            """;

    private static final String MOVEMENT_MONTHS = """
            select distinct date_trunc('month', m.moved_on)::date as month
            from account_movement m
            where m.account_id = :account
              and m.moved_on between :from and :to
            order by month desc
            limit :limit
            """;

    private static final String INSERT_DIFFERENCE = """
            insert into bank_difference (id, tenant_id, reconciliation_id, movement_id, reason, created_by, created_at)
            values (:id, :tenant, :reconciliation, :movement, :reason, cast(:by as uuid), :at)
            """;

    private final EntityManager entityManager;

    BankReconciliationQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** A month's reconciliation as stored; the statement is entered when the row exists. */
    record ReconciliationRow(UUID id, YearMonth month, Figures statement, BankMonthStatus status,
            @Nullable UUID reconciledBy, @Nullable Instant reconciledAt, @Nullable UUID reopenedMovementId,
            @Nullable Instant reopenedAt) {
    }

    /** What a bank movement reopened: the month's reconciliation and the movement's facts. */
    record Reopened(UUID reconciliationId, YearMonth month, MovementKind kind, Money amount, LocalDate date) {
    }

    /** The status of a month that has a reconciliation. */
    record MonthStatus(YearMonth month, BankMonthStatus status) {
    }

    /** A new reconciliation row with the statement. */
    record NewReconciliation(UUID id, UUID tenantId, YearMonth month, Figures statement, UUID createdBy,
            Instant createdAt) {
    }

    /** The month's reconciliation; empty before its statement is entered. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<ReconciliationRow> find(YearMonth month) {
        List<?> rows = entityManager.createNativeQuery(FIND).setParameter("month", month.atDay(1)).getResultList();
        return rows.stream().findFirst().map(row -> row((Object[]) row));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void insert(NewReconciliation row) {
        entityManager.createNativeQuery(INSERT)
                .setParameter("id", row.id())
                .setParameter("tenant", row.tenantId())
                .setParameter("month", row.month().atDay(1))
                .setParameter("opening", row.statement().opening().value())
                .setParameter("incoming", row.statement().incoming().value())
                .setParameter("outgoing", row.statement().outgoing().value())
                .setParameter("closing", row.statement().closing().value())
                .setParameter("by", row.createdBy())
                .setParameter("at", row.createdAt())
                .executeUpdate();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void updateStatement(UUID id, Figures statement) {
        entityManager.createNativeQuery(UPDATE_STATEMENT)
                .setParameter("opening", statement.opening().value())
                .setParameter("incoming", statement.incoming().value())
                .setParameter("outgoing", statement.outgoing().value())
                .setParameter("closing", statement.closing().value())
                .setParameter("id", id)
                .executeUpdate();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void confirm(UUID id, UUID by, Instant at) {
        entityManager.createNativeQuery(CONFIRM)
                .setParameter("by", by)
                .setParameter("at", at)
                .setParameter("id", id)
                .executeUpdate();
    }

    /**
     * Reopens the reconciled month the movement is dated in and every later reconciled month, oldest first (see
     * {@link #REOPEN}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Reopened> reopen(UUID movementId, Instant at) {
        List<?> rows = entityManager.createNativeQuery(REOPEN)
                .setParameter("movement", movementId)
                .setParameter("at", at)
                .getResultList();
        return rows.stream().map(row -> {
            Object[] v = (Object[]) row;
            return new Reopened((UUID) v[0], YearMonth.from(date(v[1])), MovementKind.valueOf((String) v[2]),
                    Money.of((BigDecimal) v[3]), date(v[4]));
        }).toList();
    }

    /** The bank flows from {@code from} to {@code to} (see {@link #FLOWS}). */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Flow> flows(UUID accountId, LocalDate from, LocalDate to) {
        return entityManager.createNativeQuery(FLOWS)
                .setParameter("account", accountId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList().stream()
                .map(row -> {
                    Object[] v = (Object[]) row;
                    return new Flow(date(v[0]), (Boolean) v[1], Money.of((BigDecimal) v[2]));
                })
                .toList();
    }

    /** The statuses of the months from {@code from} to {@code to} that have a reconciliation, newest first. */
    @Transactional(propagation = Propagation.MANDATORY)
    List<MonthStatus> statuses(YearMonth from, YearMonth to, int limit) {
        return entityManager.createNativeQuery(STATUSES)
                .setParameter("from", from.atDay(1))
                .setParameter("to", to.atDay(1))
                .setParameter("limit", limit)
                .getResultList().stream()
                .map(row -> {
                    Object[] v = (Object[]) row;
                    return new MonthStatus(YearMonth.from(date(v[0])), BankMonthStatus.valueOf((String) v[1]));
                })
                .toList();
    }

    /** The months of the account's lines dated from {@code from} to {@code to}, newest first. */
    @Transactional(propagation = Propagation.MANDATORY)
    List<YearMonth> movementMonths(UUID accountId, LocalDate from, LocalDate to, int limit) {
        return entityManager.createNativeQuery(MOVEMENT_MONTHS)
                .setParameter("account", accountId)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("limit", limit)
                .getResultList().stream()
                .map(value -> YearMonth.from(date(value)))
                .toList();
    }

    /** The reason of a booked bank difference line. */
    @Transactional(propagation = Propagation.MANDATORY)
    void insertDifference(UUID tenantId, UUID reconciliationId, UUID movementId, String reason, UUID by,
            Instant at) {
        entityManager.createNativeQuery(INSERT_DIFFERENCE)
                .setParameter("id", UUID.randomUUID())
                .setParameter("tenant", tenantId)
                .setParameter("reconciliation", reconciliationId)
                .setParameter("movement", movementId)
                .setParameter("reason", reason)
                .setParameter("by", by)
                .setParameter("at", at)
                .executeUpdate();
    }

    private static ReconciliationRow row(Object[] v) {
        Figures statement = new Figures(Money.of((BigDecimal) v[2]), Money.of((BigDecimal) v[3]),
                Money.of((BigDecimal) v[4]), Money.of((BigDecimal) v[5]));
        return new ReconciliationRow((UUID) v[0], YearMonth.from(date(v[1])), statement,
                BankMonthStatus.valueOf((String) v[6]), (UUID) v[7], (Instant) v[8], (UUID) v[9], (Instant) v[10]);
    }

    private static LocalDate date(Object value) {
        Objects.requireNonNull(value, "date");
        return value instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate) value;
    }
}
