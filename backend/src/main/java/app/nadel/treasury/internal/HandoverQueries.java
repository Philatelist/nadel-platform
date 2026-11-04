package app.nadel.treasury.internal;

import app.nadel.money.Money;
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
 * The reads and inserts of {@code handover} (append-only: inserts only), as parameterised native queries in the
 * caller's transaction ({@code Propagation.MANDATORY}), under RLS. An original handover has
 * {@code reverses_handover_id} null and points at its two movements (cash out, bank in); its reversal is a second row
 * pointing at it with the reason and at the two reversing movements, dated the day of the reversal, with the original's
 * document number. Reads of the card name originals only.
 */
@Component
class HandoverQueries {

    private static final String INSERT = """
            insert into handover (id, tenant_id, amount, handed_on, document_number, out_movement_id, in_movement_id,
                                  reverses_handover_id, reason, created_by, created_at)
            values (:id, :tenant, :amount, :date, :documentNumber, :out, :in, cast(:reverses as uuid),
                    cast(:reason as text), cast(:createdBy as uuid), :createdAt)
            """;

    /** An original handover with the month of its lines and its reversal, if any. */
    private static final String CARD = """
            select h.id, h.amount, h.handed_on, h.document_number, m.booking_month, h.out_movement_id,
                   h.in_movement_id, h.created_by, h.created_at, r.id, r.handed_on, r.reason, r.created_by,
                   r.created_at
            from handover h
            join account_movement m on m.tenant_id = h.tenant_id and m.id = h.out_movement_id
            left join handover r on r.tenant_id = h.tenant_id and r.reverses_handover_id = h.id
            where h.id = :id and h.reverses_handover_id is null
            """;

    private static final String REVERSAL_OF = "select r.id from handover r where r.reverses_handover_id = :id";

    private final EntityManager entityManager;

    HandoverQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** A new handover row (an original, or with {@code reversesHandoverId} and {@code reason} its reversal). */
    record NewHandover(UUID id, UUID tenantId, Money amount, LocalDate date, String documentNumber,
            UUID outMovementId, UUID inMovementId, @Nullable UUID reversesHandoverId, @Nullable String reason,
            UUID createdBy, Instant createdAt) {
    }

    /** An original handover as stored, with its reversal. */
    record HandoverRow(UUID id, Money amount, LocalDate date, String documentNumber, YearMonth bookingMonth,
            UUID outMovementId, UUID inMovementId, @Nullable UUID createdBy, Instant createdAt,
            @Nullable ReversalRow reversal) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void insert(NewHandover h) {
        entityManager.createNativeQuery(INSERT)
                .setParameter("id", h.id())
                .setParameter("tenant", h.tenantId())
                .setParameter("amount", h.amount().value())
                .setParameter("date", h.date())
                .setParameter("documentNumber", h.documentNumber())
                .setParameter("out", h.outMovementId())
                .setParameter("in", h.inMovementId())
                .setParameter("reverses", h.reversesHandoverId())
                .setParameter("reason", h.reason())
                .setParameter("createdBy", h.createdBy())
                .setParameter("createdAt", h.createdAt())
                .executeUpdate();
    }

    /** The original handover; empty when unknown, a reversal row or of another cooperative. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<HandoverRow> card(UUID id) {
        List<?> rows = entityManager.createNativeQuery(CARD).setParameter("id", id).getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] v = (Object[]) rows.getFirst();
        ReversalRow reversal = v[9] == null ? null
                : new ReversalRow((UUID) v[9], date(v[10]), (String) v[11], (UUID) v[12], (Instant) v[13]);
        return Optional.of(new HandoverRow((UUID) v[0], Money.of((BigDecimal) v[1]), date(v[2]), (String) v[3],
                YearMonth.from(date(v[4])), (UUID) v[5], (UUID) v[6], (UUID) v[7], (Instant) v[8], reversal));
    }

    /** Whether the handover has a reversal row (read after the account locks, so a committed reversal is seen). */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean reversed(UUID id) {
        return !entityManager.createNativeQuery(REVERSAL_OF).setParameter("id", id).getResultList().isEmpty();
    }

    private static LocalDate date(Object value) {
        Objects.requireNonNull(value, "date");
        return value instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate) value;
    }
}
