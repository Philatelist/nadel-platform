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
 * The reads and writes of {@code cash_count}, as parameterised native queries in the caller's transaction
 * ({@code Propagation.MANDATORY}), under RLS. A count is inserted once with the book balance it computed; the one
 * update is the booking of its difference ({@code booked_movement_id} and {@code booking_reason} together, once: the
 * trigger {@code cash_count_guard} and the column grants of V32 and V33). Every read carries the booked line and its
 * reversal, if any. Counts are ordered by the moment of the count, then the time saved, then id.
 */
@Component
class CashCountQueries {

    private static final String COLUMNS = """
            select c.id, c.account_id, c.counted_at, c.counted_on, c.counted_amount, c.book_balance, c.difference,
                   c.explanation, c.created_by, c.created_at, c.booked_movement_id, c.booking_reason, m.moved_on,
                   m.booking_month, m.amount, m.created_by, m.created_at, r.id, r.moved_on, r.reason, r.created_by,
                   r.created_at
            from cash_count c
            left join account_movement m on m.tenant_id = c.tenant_id and m.id = c.booked_movement_id
            left join account_movement r on r.tenant_id = c.tenant_id and r.reverses_movement_id = c.booked_movement_id
            """;

    private static final String NEWEST_FIRST = " order by c.counted_at desc, c.created_at desc, c.id desc";

    private static final String CARD = COLUMNS + " where c.id = :id";

    private static final String PAGE = COLUMNS + NEWEST_FIRST + " offset :offset limit :limit";

    private static final String LATEST = COLUMNS + NEWEST_FIRST + " limit 1";

    /** The count before a new one at {@code countedAt}: every saved count was saved before it. */
    private static final String PREVIOUS_OF_NEW = COLUMNS + " where c.counted_at <= :countedAt" + NEWEST_FIRST
            + " limit 1";

    /** The count before a saved one, in the order of the list. */
    private static final String PREVIOUS_OF_SAVED = COLUMNS
            + " where (c.counted_at, c.created_at, c.id) < (:countedAt, :createdAt, :id)" + NEWEST_FIRST + " limit 1";

    private static final String COUNT = "select count(*) from cash_count";

    private static final String INSERT = """
            insert into cash_count (id, tenant_id, account_id, counted_at, counted_on, counted_amount, book_balance,
                                    difference, explanation, created_by, created_at)
            values (:id, :tenant, :account, :countedAt, :countedOn, :counted, :book, :difference,
                    cast(:explanation as text), cast(:createdBy as uuid), :createdAt)
            """;

    private static final String BOOK = """
            update cash_count set booked_movement_id = :movement, booking_reason = :reason where id = :id
            """;

    private final EntityManager entityManager;

    CashCountQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** A new count as computed. */
    record NewCount(UUID id, UUID tenantId, UUID accountId, Instant countedAt, LocalDate countedOn, Money counted,
            Money bookBalance, Money difference, @Nullable String explanation, UUID createdBy, Instant createdAt) {
    }

    /** A count as stored, with its booking. */
    record CountRow(UUID id, UUID accountId, Instant countedAt, LocalDate countedOn, Money counted, Money bookBalance,
            Money difference, @Nullable String explanation, @Nullable UUID createdBy, Instant createdAt,
            @Nullable BookingRow booking) {
    }

    /** The booked line of a count's difference, with its reversal. */
    record BookingRow(UUID movementId, LocalDate date, YearMonth bookingMonth, Money amount, String reason,
            @Nullable UUID createdBy, Instant createdAt, @Nullable ReversalRow reversal) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void insert(NewCount count) {
        entityManager.createNativeQuery(INSERT)
                .setParameter("id", count.id())
                .setParameter("tenant", count.tenantId())
                .setParameter("account", count.accountId())
                .setParameter("countedAt", count.countedAt())
                .setParameter("countedOn", count.countedOn())
                .setParameter("counted", count.counted().value())
                .setParameter("book", count.bookBalance().value())
                .setParameter("difference", count.difference().value())
                .setParameter("explanation", count.explanation())
                .setParameter("createdBy", count.createdBy())
                .setParameter("createdAt", count.createdAt())
                .executeUpdate();
    }

    /** Writes the booked line and the reason of the count's difference (the one update a count gets). */
    @Transactional(propagation = Propagation.MANDATORY)
    void book(UUID countId, UUID movementId, String reason) {
        int updated = entityManager.createNativeQuery(BOOK)
                .setParameter("movement", movementId)
                .setParameter("reason", reason)
                .setParameter("id", countId)
                .executeUpdate();
        if (updated != 1) {
            throw new IllegalStateException("The booked count is not visible");
        }
    }

    /** The count; empty when unknown or of another cooperative. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<CountRow> card(UUID id) {
        return first(entityManager.createNativeQuery(CARD).setParameter("id", id).getResultList());
    }

    /** A page of the counts, newest first. */
    @Transactional(propagation = Propagation.MANDATORY)
    List<CountRow> page(long offset, int limit) {
        return entityManager.createNativeQuery(PAGE).setParameter("offset", offset).setParameter("limit", limit)
                .getResultList().stream().map(row -> row((Object[]) row)).toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    long count() {
        return ((Number) entityManager.createNativeQuery(COUNT).getSingleResult()).longValue();
    }

    /** The newest count; empty before the first. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<CountRow> latest() {
        return first(entityManager.createNativeQuery(LATEST).getResultList());
    }

    /** The newest saved count at or before {@code countedAt}, for a count not saved yet. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<CountRow> previousOfNew(Instant countedAt) {
        return first(entityManager.createNativeQuery(PREVIOUS_OF_NEW).setParameter("countedAt", countedAt)
                .getResultList());
    }

    /** The count just before the saved one in the list order. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<CountRow> previousOf(CountRow count) {
        return first(entityManager.createNativeQuery(PREVIOUS_OF_SAVED)
                .setParameter("countedAt", count.countedAt())
                .setParameter("createdAt", count.createdAt())
                .setParameter("id", count.id())
                .getResultList());
    }

    private static Optional<CountRow> first(List<?> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(row((Object[]) rows.getFirst()));
    }

    private static CountRow row(Object[] v) {
        BookingRow booking = null;
        if (v[10] != null) {
            ReversalRow reversal = v[17] == null ? null
                    : new ReversalRow((UUID) v[17], date(v[18]), (String) v[19], (UUID) v[20], (Instant) v[21]);
            booking = new BookingRow((UUID) v[10], date(v[12]), YearMonth.from(date(v[13])),
                    Money.of((BigDecimal) v[14]), (String) v[11], (UUID) v[15], (Instant) v[16], reversal);
        }
        return new CountRow((UUID) v[0], (UUID) v[1], (Instant) v[2], date(v[3]), Money.of((BigDecimal) v[4]),
                Money.of((BigDecimal) v[5]), Money.of((BigDecimal) v[6]), (String) v[7], (UUID) v[8], (Instant) v[9],
                booking);
    }

    private static LocalDate date(Object value) {
        Objects.requireNonNull(value, "date");
        return value instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate) value;
    }
}
