package app.nadel.treasury.internal;

import app.nadel.money.Money;
import app.nadel.treasury.MoneyAccountKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads and inserts of {@code expense} (append-only: inserts only) and the copy of stored receipt files, as
 * parameterised native queries in the caller's transaction ({@code Propagation.MANDATORY}), under RLS. An original
 * expense has {@code reverses_expense_id} null; its reversal is a second row pointing at it with the reason, dated the
 * day of the reversal. Reads of the expense list and card name originals only.
 */
@Component
class ExpenseQueries {

    private static final String INSERT = """
            insert into expense (id, tenant_id, account_id, category_id, amount, expense_date, counterparty_enc,
                                 description_enc, document_number, document_date, movement_id, reverses_expense_id,
                                 reason, created_by, created_at)
            values (:id, :tenant, :account, :category, :amount, :date, cast(:counterparty as bytea),
                    cast(:description as bytea), cast(:documentNumber as text), cast(:documentDate as date), :movement,
                    cast(:reverses as uuid), cast(:reason as text), cast(:createdBy as uuid), :createdAt)
            """;

    /** An original expense with its account, month and reversal, if any. */
    private static final String CARD = """
            select e.id, a.id, a.kind, e.category_id, e.amount, e.expense_date, m.id, m.booking_month,
                   e.counterparty_enc, e.description_enc, e.document_number, e.document_date, e.created_by,
                   e.created_at, r.id, r.expense_date, r.reason, r.created_by, r.created_at
            from expense e
            join money_account a on a.tenant_id = e.tenant_id and a.id = e.account_id
            join account_movement m on m.tenant_id = e.tenant_id and m.id = e.movement_id
            left join expense r on r.tenant_id = e.tenant_id and r.reverses_expense_id = e.id
            where e.id = :id and e.reverses_expense_id is null
            """;

    private static final String REVERSAL_OF = "select r.id from expense r where r.reverses_expense_id = :id";

    private static final String LIST = """
            select e.id, a.kind, e.category_id, e.amount, e.expense_date, e.counterparty_enc,
                   exists (select 1 from expense r where r.tenant_id = e.tenant_id and r.reverses_expense_id = e.id)
            from expense e
            join money_account a on a.tenant_id = e.tenant_id and a.id = e.account_id
            where e.reverses_expense_id is null
            """;

    private static final String COUNT = """
            select count(*)
            from expense e
            join money_account a on a.tenant_id = e.tenant_id and a.id = e.account_id
            where e.reverses_expense_id is null
            """;

    private static final String COPY_ATTACHMENT = """
            insert into expense_attachment (id, tenant_id, expense_id, position, content_type, file_name, size_bytes,
                                            sha256, content, created_by, created_at)
            select gen_random_uuid(), a.tenant_id, :expense, :position, a.content_type, a.file_name, a.size_bytes,
                   a.sha256, a.content, cast(:createdBy as uuid), :createdAt
            from expense_attachment a
            where a.expense_id = :source and a.position = :n
            """;

    private final EntityManager entityManager;

    ExpenseQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** A new expense row (an original, or with {@code reversesExpenseId} and {@code reason} its reversal). */
    record NewExpense(UUID id, UUID tenantId, UUID accountId, UUID categoryId, Money amount, LocalDate date,
            byte @Nullable [] counterpartyEnc, byte @Nullable [] descriptionEnc, @Nullable String documentNumber,
            @Nullable LocalDate documentDate, UUID movementId, @Nullable UUID reversesExpenseId,
            @Nullable String reason, UUID createdBy, Instant createdAt) {
    }

    /** How an expense was reversed. */
    record ReversalRow(UUID id, LocalDate date, String reason, @Nullable UUID createdBy, Instant createdAt) {
    }

    /** An original expense as stored, with its reversal. */
    record ExpenseRow(UUID id, UUID accountId, MoneyAccountKind account, UUID categoryId, Money amount,
            LocalDate date, UUID movementId, YearMonth bookingMonth, byte @Nullable [] counterpartyEnc,
            byte @Nullable [] descriptionEnc, @Nullable String documentNumber, @Nullable LocalDate documentDate,
            @Nullable UUID createdBy, Instant createdAt, @Nullable ReversalRow reversal) {
    }

    /** One line of the expense list as stored. */
    record SummaryRow(UUID id, MoneyAccountKind account, UUID categoryId, Money amount, LocalDate date,
            byte @Nullable [] counterpartyEnc, boolean reversed) {
    }

    /** The filters of the list; each null means no filter. */
    record Filter(@Nullable LocalDate from, @Nullable LocalDate to, @Nullable MoneyAccountKind account,
            @Nullable UUID categoryId) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void insert(NewExpense e) {
        entityManager.createNativeQuery(INSERT)
                .setParameter("id", e.id())
                .setParameter("tenant", e.tenantId())
                .setParameter("account", e.accountId())
                .setParameter("category", e.categoryId())
                .setParameter("amount", e.amount().value())
                .setParameter("date", e.date())
                .setParameter("counterparty", e.counterpartyEnc())
                .setParameter("description", e.descriptionEnc())
                .setParameter("documentNumber", e.documentNumber())
                .setParameter("documentDate", e.documentDate())
                .setParameter("movement", e.movementId())
                .setParameter("reverses", e.reversesExpenseId())
                .setParameter("reason", e.reason())
                .setParameter("createdBy", e.createdBy())
                .setParameter("createdAt", e.createdAt())
                .executeUpdate();
    }

    /** The original expense; empty when unknown, a reversal row or of another cooperative. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<ExpenseRow> card(UUID id) {
        List<?> rows = entityManager.createNativeQuery(CARD).setParameter("id", id).getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] v = (Object[]) rows.getFirst();
        ReversalRow reversal = v[14] == null ? null
                : new ReversalRow((UUID) v[14], date(v[15]), (String) v[16], (UUID) v[17], (Instant) v[18]);
        return Optional.of(new ExpenseRow((UUID) v[0], (UUID) v[1], MoneyAccountKind.valueOf((String) v[2]),
                (UUID) v[3], Money.of((BigDecimal) v[4]), date(v[5]), (UUID) v[6], YearMonth.from(date(v[7])),
                (byte[]) v[8], (byte[]) v[9], (String) v[10], v[11] == null ? null : date(v[11]), (UUID) v[12],
                (Instant) v[13], reversal));
    }

    /** Whether the expense has a reversal row (read after the account lock, so a committed reversal is seen). */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean reversed(UUID id) {
        return !entityManager.createNativeQuery(REVERSAL_OF).setParameter("id", id).getResultList().isEmpty();
    }

    /** A page of the original expenses, newest first (date, time entered, id). */
    @Transactional(propagation = Propagation.MANDATORY)
    List<SummaryRow> list(Filter filter, long offset, int limit) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String sql = LIST + where(filter, parameters)
                + " order by e.expense_date desc, e.created_at desc, e.id desc offset :offset limit :limit";
        Query query = entityManager.createNativeQuery(sql);
        parameters.forEach(query::setParameter);
        query.setParameter("offset", offset).setParameter("limit", limit);
        List<SummaryRow> rows = new ArrayList<>();
        for (Object result : query.getResultList()) {
            Object[] v = (Object[]) result;
            rows.add(new SummaryRow((UUID) v[0], MoneyAccountKind.valueOf((String) v[1]), (UUID) v[2],
                    Money.of((BigDecimal) v[3]), date(v[4]), (byte[]) v[5], (Boolean) v[6]));
        }
        return rows;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    long count(Filter filter) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        Query query = entityManager.createNativeQuery(COUNT + where(filter, parameters));
        parameters.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * Copies the stored file {@code n} of the expense {@code source} to {@code expense} as its file {@code position},
     * inside the database (the bytes never travel to the application).
     *
     * @return whether the file existed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean copyAttachment(UUID source, int n, UUID expense, int position, UUID createdBy, Instant createdAt) {
        return entityManager.createNativeQuery(COPY_ATTACHMENT)
                .setParameter("expense", expense)
                .setParameter("position", (short) position)
                .setParameter("createdBy", createdBy)
                .setParameter("createdAt", createdAt)
                .setParameter("source", source)
                .setParameter("n", (short) n)
                .executeUpdate() == 1;
    }

    /** The conditions of the set filters (fixed text; the values are parameters). */
    private static String where(Filter filter, Map<String, Object> parameters) {
        StringBuilder sql = new StringBuilder();
        if (filter.from() != null) {
            sql.append(" and e.expense_date >= :from");
            parameters.put("from", filter.from());
        }
        if (filter.to() != null) {
            sql.append(" and e.expense_date <= :to");
            parameters.put("to", filter.to());
        }
        if (filter.account() != null) {
            sql.append(" and a.kind = :account");
            parameters.put("account", filter.account().name());
        }
        if (filter.categoryId() != null) {
            sql.append(" and e.category_id = :category");
            parameters.put("category", filter.categoryId());
        }
        return sql.toString();
    }

    private static LocalDate date(Object value) {
        Objects.requireNonNull(value, "date");
        return value instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate) value;
    }
}
