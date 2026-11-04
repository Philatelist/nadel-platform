package app.nadel.treasury.internal;

import app.nadel.money.Money;
import app.nadel.treasury.CategoryRef;
import app.nadel.treasury.MoneyAccountKind;
import app.nadel.treasury.MovementKind;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads of the cash book reports ({@link TreasuryReports}): parameterised native queries over the treasury's own
 * tables in the caller's transaction ({@code Propagation.MANDATORY}), under RLS. They take no lock: a report reads what
 * is committed.
 */
@Component
class TreasuryReportQueries {

    /** Bound to an {@code IN} list that a {@code true} "all" flag switches off (an empty list is not valid SQL). */
    private static final List<UUID> NONE = List.of(new UUID(0L, 0L));

    /**
     * An account's lines of a period for its book, oldest first: the deposit slip of a handover line, the expense of an
     * expense line (for a reversal line, the original expense: its counterparty is sealed under its own id and its
     * document number is the one entered) and the category of an expense or other income line.
     */
    private static final String BOOK_LINES = """
            select m.id, m.kind, m.amount, m.moved_on, m.created_at, m.reverses_movement_id is not null,
                   m.payment_row_id, h.document_number,
                   coalesce(eo.id, e.id),
                   case when eo.id is not null then eo.counterparty_enc else e.counterparty_enc end,
                   case when eo.id is not null then eo.document_number else e.document_number end,
                   coalesce(e.category_id, o.category_id)
            from account_movement m
            left join handover h on m.source_type = 'HANDOVER' and h.tenant_id = m.tenant_id and h.id = m.source_id
            left join expense e on m.source_type = 'EXPENSE' and e.tenant_id = m.tenant_id and e.id = m.source_id
            left join expense eo on eo.tenant_id = e.tenant_id and eo.id = e.reverses_expense_id
            left join other_income o on m.source_type = 'OTHER_INCOME' and o.tenant_id = m.tenant_id
                                        and o.id = m.source_id
            where m.account_id = :account
              and m.moved_on between :from and :to
            order by m.moved_on, m.created_at, m.id
            limit :limit
            """;

    /**
     * Every line of both accounts dated in a period with what the income and expense needs: the account, for a
     * reversal the date and payment row of the line it reverses, and the category of an expense or other income line.
     */
    private static final String PERIOD_LINES = """
            select a.kind, m.kind, m.amount, m.payment_row_id, m.reverses_movement_id is not null, om.moved_on,
                   om.payment_row_id, coalesce(e.category_id, o.category_id)
            from account_movement m
            join money_account a on a.tenant_id = m.tenant_id and a.id = m.account_id
            left join account_movement om on om.tenant_id = m.tenant_id and om.id = m.reverses_movement_id
            left join expense e on m.source_type = 'EXPENSE' and e.tenant_id = m.tenant_id and e.id = m.source_id
            left join other_income o on m.source_type = 'OTHER_INCOME' and o.tenant_id = m.tenant_id
                                        and o.id = m.source_id
            where m.moved_on between :from and :to
            order by m.moved_on, m.created_at, m.id
            """;

    /** The original expenses of a period, of one account or both and of some categories or all. */
    private static final String EXPENSES = """
            select e.id, e.expense_date, e.created_at, a.kind, e.category_id, e.amount, e.counterparty_enc,
                   e.document_number, e.document_date,
                   exists (select 1 from expense r where r.tenant_id = e.tenant_id and r.reverses_expense_id = e.id),
                   (select count(*) from expense_attachment x where x.tenant_id = e.tenant_id and x.expense_id = e.id)
            from expense e
            join money_account a on a.tenant_id = e.tenant_id and a.id = e.account_id
            where e.reverses_expense_id is null
              and e.expense_date between :from and :to
              and (:allAccounts = true or a.kind = :account)
              and (:allCategories = true or e.category_id in (:categoryIds))
            order by e.expense_date, e.created_at, e.id
            limit :limit
            """;

    private static final String CATEGORIES = """
            select c.id, c.kind, c.system_code, c.name, c.sort_order, c.archived
            from expense_category c
            order by case c.kind when 'EXPENSE' then 0 else 1 end, c.sort_order, c.id
            """;

    private final EntityManager entityManager;

    TreasuryReportQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * One line of an account's book as stored.
     *
     * @param expenseId       the expense whose counterparty and document the line shows (for a reversal, the
     *                        original); null for other lines
     * @param counterpartyEnc that expense's sealed counterparty
     */
    record BookLineRow(UUID id, MovementKind kind, Money amount, LocalDate movedOn, Instant createdAt,
            boolean reversal, @Nullable UUID paymentRowId, @Nullable String handoverDocument,
            @Nullable UUID expenseId, byte @Nullable [] counterpartyEnc, @Nullable String expenseDocument,
            @Nullable UUID categoryId) {
    }

    /**
     * A line of a period for the income and expense.
     *
     * @param paymentRowId         the ledger row of a member payment line (a payment, or the reversal row)
     * @param originalDate         for a reversal, the date of the line it reverses
     * @param originalPaymentRowId for a reversal of a member payment, the payment row of the line it reverses
     */
    record PeriodLineRow(MoneyAccountKind account, MovementKind kind, Money amount, @Nullable UUID paymentRowId,
            boolean reversal, @Nullable LocalDate originalDate, @Nullable UUID originalPaymentRowId,
            @Nullable UUID categoryId) {
    }

    /** An original expense for the expenses report. */
    record ExpenseReportRow(UUID id, LocalDate date, Instant createdAt, MoneyAccountKind account, UUID categoryId,
            Money amount, byte @Nullable [] counterpartyEnc, @Nullable String documentNumber,
            @Nullable LocalDate documentDate, boolean reversed, int attachments) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    List<BookLineRow> bookLines(UUID accountId, LocalDate from, LocalDate to, int limit) {
        return entityManager.createNativeQuery(BOOK_LINES)
                .setParameter("account", accountId)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("limit", limit)
                .getResultList().stream()
                .map(row -> {
                    Object[] v = (Object[]) row;
                    return new BookLineRow((UUID) v[0], MovementKind.valueOf((String) v[1]),
                            Money.of((BigDecimal) v[2]), date(v[3]), (Instant) v[4], (Boolean) v[5], (UUID) v[6],
                            (String) v[7], (UUID) v[8], (byte[]) v[9], (String) v[10], (UUID) v[11]);
                })
                .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    List<PeriodLineRow> periodLines(LocalDate from, LocalDate to) {
        return entityManager.createNativeQuery(PERIOD_LINES)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList().stream()
                .map(row -> {
                    Object[] v = (Object[]) row;
                    return new PeriodLineRow(MoneyAccountKind.valueOf((String) v[0]),
                            MovementKind.valueOf((String) v[1]), Money.of((BigDecimal) v[2]), (UUID) v[3],
                            (Boolean) v[4], v[5] == null ? null : date(v[5]), (UUID) v[6], (UUID) v[7]);
                })
                .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    List<ExpenseReportRow> expenses(LocalDate from, LocalDate to, @Nullable MoneyAccountKind account,
            Collection<UUID> categoryIds, int limit) {
        return entityManager.createNativeQuery(EXPENSES)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("allAccounts", account == null)
                .setParameter("account", account == null ? MoneyAccountKind.CASH.name() : account.name())
                .setParameter("allCategories", categoryIds.isEmpty())
                .setParameter("categoryIds", categoryIds.isEmpty() ? NONE : List.copyOf(categoryIds))
                .setParameter("limit", limit)
                .getResultList().stream()
                .map(row -> {
                    Object[] v = (Object[]) row;
                    return new ExpenseReportRow((UUID) v[0], date(v[1]), (Instant) v[2],
                            MoneyAccountKind.valueOf((String) v[3]), (UUID) v[4], Money.of((BigDecimal) v[5]),
                            (byte[]) v[6], (String) v[7], v[8] == null ? null : date(v[8]), (Boolean) v[9],
                            ((Number) v[10]).intValue());
                })
                .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    List<CategoryRef> categories() {
        return entityManager.createNativeQuery(CATEGORIES).getResultList().stream()
                .map(row -> {
                    Object[] v = (Object[]) row;
                    return new CategoryRef((UUID) v[0], CategoryRef.Kind.valueOf((String) v[1]), (String) v[2],
                            (String) v[3], ((Number) v[4]).intValue(), (Boolean) v[5]);
                })
                .toList();
    }

    private static LocalDate date(Object value) {
        Objects.requireNonNull(value, "date");
        return value instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate) value;
    }
}
