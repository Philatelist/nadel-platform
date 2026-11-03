package app.nadel.treasury.internal;

import app.nadel.money.Money;
import app.nadel.treasury.MoneyAccountKind;
import app.nadel.treasury.internal.ExpenseQueries.ReversalRow;
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
 * The reads and inserts of {@code other_income} (append-only: inserts only), as parameterised native queries in the
 * caller's transaction ({@code Propagation.MANDATORY}), under RLS. An original income has
 * {@code reverses_other_income_id} null; its reversal is a second row pointing at it with the reason and the category,
 * without a description, dated the day of the reversal. Reads of the card name originals only.
 */
@Component
class OtherIncomeQueries {

    private static final String INSERT = """
            insert into other_income (id, tenant_id, account_id, category_id, amount, received_on, description_enc,
                                      movement_id, reverses_other_income_id, reason, created_by, created_at)
            values (:id, :tenant, :account, :category, :amount, :date, cast(:description as bytea), :movement,
                    cast(:reverses as uuid), cast(:reason as text), cast(:createdBy as uuid), :createdAt)
            """;

    /** An original income with its account, month and reversal, if any. */
    private static final String CARD = """
            select o.id, a.id, a.kind, o.category_id, o.amount, o.received_on, m.id, m.booking_month,
                   o.description_enc, o.created_by, o.created_at, r.id, r.received_on, r.reason, r.created_by,
                   r.created_at
            from other_income o
            join money_account a on a.tenant_id = o.tenant_id and a.id = o.account_id
            join account_movement m on m.tenant_id = o.tenant_id and m.id = o.movement_id
            left join other_income r on r.tenant_id = o.tenant_id and r.reverses_other_income_id = o.id
            where o.id = :id and o.reverses_other_income_id is null
            """;

    private static final String REVERSAL_OF =
            "select r.id from other_income r where r.reverses_other_income_id = :id";

    private final EntityManager entityManager;

    OtherIncomeQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** A new income row (an original, or with {@code reversesOtherIncomeId} and {@code reason} its reversal). */
    record NewOtherIncome(UUID id, UUID tenantId, UUID accountId, UUID categoryId, Money amount, LocalDate date,
            byte @Nullable [] descriptionEnc, UUID movementId, @Nullable UUID reversesOtherIncomeId,
            @Nullable String reason, UUID createdBy, Instant createdAt) {
    }

    /** An original income as stored, with its reversal. */
    record OtherIncomeRow(UUID id, UUID accountId, MoneyAccountKind account, UUID categoryId, Money amount,
            LocalDate date, UUID movementId, YearMonth bookingMonth, byte @Nullable [] descriptionEnc,
            @Nullable UUID createdBy, Instant createdAt, @Nullable ReversalRow reversal) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void insert(NewOtherIncome o) {
        entityManager.createNativeQuery(INSERT)
                .setParameter("id", o.id())
                .setParameter("tenant", o.tenantId())
                .setParameter("account", o.accountId())
                .setParameter("category", o.categoryId())
                .setParameter("amount", o.amount().value())
                .setParameter("date", o.date())
                .setParameter("description", o.descriptionEnc())
                .setParameter("movement", o.movementId())
                .setParameter("reverses", o.reversesOtherIncomeId())
                .setParameter("reason", o.reason())
                .setParameter("createdBy", o.createdBy())
                .setParameter("createdAt", o.createdAt())
                .executeUpdate();
    }

    /** The original income; empty when unknown, a reversal row or of another cooperative. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<OtherIncomeRow> card(UUID id) {
        List<?> rows = entityManager.createNativeQuery(CARD).setParameter("id", id).getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] v = (Object[]) rows.getFirst();
        ReversalRow reversal = v[11] == null ? null
                : new ReversalRow((UUID) v[11], date(v[12]), (String) v[13], (UUID) v[14], (Instant) v[15]);
        return Optional.of(new OtherIncomeRow((UUID) v[0], (UUID) v[1], MoneyAccountKind.valueOf((String) v[2]),
                (UUID) v[3], Money.of((BigDecimal) v[4]), date(v[5]), (UUID) v[6], YearMonth.from(date(v[7])),
                (byte[]) v[8], (UUID) v[9], (Instant) v[10], reversal));
    }

    /** Whether the income has a reversal row (read after the account lock, so a committed reversal is seen). */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean reversed(UUID id) {
        return !entityManager.createNativeQuery(REVERSAL_OF).setParameter("id", id).getResultList().isEmpty();
    }

    private static LocalDate date(Object value) {
        Objects.requireNonNull(value, "date");
        return value instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate) value;
    }
}
