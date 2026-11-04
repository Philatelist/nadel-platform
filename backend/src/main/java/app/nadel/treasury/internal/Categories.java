package app.nadel.treasury.internal;

import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.treasury.internal.TreasuryAuditPublisher.Action;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.ExpenseCategory;
import nadel.contract.model.ExpenseCategoryCreate;
import nadel.contract.model.ExpenseCategoryKind;
import nadel.contract.model.ExpenseCategoryOrder;
import nadel.contract.model.ExpenseCategoryUpdate;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The expense and income categories of the cooperative (feature 018 §2.9; table {@code expense_category}, V32): the
 * list of one kind in its order, adding, renaming, reordering and archiving; a category is never deleted. A seeded
 * category has a {@code system_code} and keeps {@code name} null until it is renamed (the client names it from the
 * catalogs); renaming stores the name. {@code nadel_app} may update only {@code name}, {@code sort_order} and
 * {@code archived} (column grant), which is all this class writes. The caller holds the permission (checked on
 * {@code TreasuryController}: {@code CASHBOOK_VIEW} to list, {@code CASHBOOK_APPROVE} to change).
 * <p>
 * <b>Names</b> are unique per kind ignoring case, archived categories included, exactly as the unique index
 * {@code expense_category_name_uq} on {@code lower(name)} says: a quick check answers 409 {@code CATEGORY_NAME_TAKEN}
 * and the index is the guarantee against two requests at once. A seeded category that was never renamed has no name,
 * so a new name equal to its catalog name does not collide. A change of one category locks its row, a reorder every
 * non-archived row of the kind. Audit {@code CASHBOOK_CATEGORY_*} (see {@link TreasuryAuditPublisher}).
 */
@Service
class Categories {

    private static final String NAME_INDEX = "expense_category_name_uq";
    private static final String COLUMNS = "c.id, c.kind, c.system_code, c.name, c.sort_order, c.archived";

    private final EntityManager entityManager;
    private final TreasuryAuditPublisher audit;
    private final Clock clock;

    Categories(EntityManager entityManager, TreasuryAuditPublisher audit, Clock clock) {
        this.entityManager = entityManager;
        this.audit = audit;
        this.clock = clock;
    }

    /** A category as stored; {@code kind} is {@code EXPENSE} or {@code INCOME}. */
    record CategoryRow(UUID id, String kind, @Nullable String systemCode, @Nullable String name, int sortOrder,
            boolean archived) {

        /** The audit label and name value: {@code base:<CODE>} while a seeded category has no name of its own. */
        String auditName() {
            return name != null ? name : "base:" + systemCode;
        }
    }

    @Transactional(readOnly = true)
    List<ExpenseCategory> list(@Nullable ExpenseCategoryKind kind, boolean includeArchived) {
        String value = (kind == null ? ExpenseCategoryKind.EXPENSE : kind).getValue();
        return rows("select " + COLUMNS + " from expense_category c where c.kind = :kind"
                + (includeArchived ? "" : " and not c.archived") + " order by c.sort_order, c.id",
                Map.of("kind", value)).stream().map(Categories::view).toList();
    }

    @Transactional
    ExpenseCategory create(ExpenseCategoryCreate input, AccessProfile actor) {
        String kind = input.getKind().getValue();
        String name = cleanName(input.getName());
        ensureNameFree(kind, name, null);
        Number max = (Number) entityManager.createNativeQuery(
                "select coalesce(max(sort_order), 0) from expense_category where kind = :kind")
                .setParameter("kind", kind).getSingleResult();
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        execute(() -> entityManager.createNativeQuery("""
                insert into expense_category (id, tenant_id, kind, name, sort_order, created_by, created_at)
                values (:id, :tenant, :kind, :name, :sortOrder, cast(:by as uuid), :at)
                """)
                .setParameter("id", id)
                .setParameter("tenant", actor.tenantId())
                .setParameter("kind", kind)
                .setParameter("name", name)
                .setParameter("sortOrder", max.intValue() + 1)
                .setParameter("by", actor.memberId())
                .setParameter("at", now)
                .executeUpdate());
        CategoryRow row = new CategoryRow(id, kind, null, name, max.intValue() + 1, false);
        audit.category(actor, Action.CASHBOOK_CATEGORY_ADDED, id, name,
                List.of(new FieldChange("kind", null, kind), new FieldChange("name", null, name)), now);
        return view(row);
    }

    @Transactional
    ExpenseCategory update(UUID id, ExpenseCategoryUpdate input, AccessProfile actor) {
        CategoryRow row = single(rows("select " + COLUMNS + " from expense_category c where c.id = :id for update",
                Map.of("id", id))).orElseThrow(ApiProblemException::notFound);
        Instant now = clock.instant();
        if (input.getName() != null) {
            String name = cleanName(input.getName());
            if (!name.equals(row.name())) {
                ensureNameFree(row.kind(), name, row.id());
                String old = row.auditName();
                execute(() -> entityManager.createNativeQuery("update expense_category set name = :name where id = :id")
                        .setParameter("name", name).setParameter("id", id).executeUpdate());
                row = new CategoryRow(row.id(), row.kind(), row.systemCode(), name, row.sortOrder(), row.archived());
                audit.category(actor, Action.CASHBOOK_CATEGORY_RENAMED, id, name,
                        List.of(new FieldChange("name", old, name)), now);
            }
        }
        if (input.getArchived() != null && input.getArchived() != row.archived()) {
            boolean archived = input.getArchived();
            entityManager.createNativeQuery("update expense_category set archived = :archived where id = :id")
                    .setParameter("archived", archived).setParameter("id", id).executeUpdate();
            audit.category(actor, Action.CASHBOOK_CATEGORY_ARCHIVED, id, row.auditName(),
                    List.of(new FieldChange("archived", String.valueOf(row.archived()), String.valueOf(archived))),
                    now);
            row = new CategoryRow(row.id(), row.kind(), row.systemCode(), row.name(), row.sortOrder(), archived);
        }
        return view(row);
    }

    /**
     * Sets the order of the non-archived categories of the kind: the submitted ids must be exactly them, each once
     * (the rows are locked first, so the set cannot change under the check), else 400 {@code ids} {@code INVALID}.
     * Positions are renumbered from 1; archived categories keep theirs. One audit entry per moved category.
     */
    @Transactional
    void reorder(ExpenseCategoryOrder input, AccessProfile actor) {
        List<CategoryRow> current = rows("select " + COLUMNS + " from expense_category c where c.kind = :kind "
                + "and not c.archived order by c.sort_order, c.id for update", Map.of("kind", input.getKind().getValue()));
        List<UUID> submitted = input.getIds();
        List<UUID> currentIds = current.stream().map(CategoryRow::id).toList();
        if (submitted.size() != currentIds.size() || !new HashSet<>(submitted).equals(new HashSet<>(currentIds))) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("ids", ProblemCodes.INVALID)));
        }
        Map<UUID, CategoryRow> byId = current.stream().collect(Collectors.toMap(CategoryRow::id, Function.identity()));
        Instant now = clock.instant();
        for (int i = 0; i < submitted.size(); i++) {
            CategoryRow row = byId.get(submitted.get(i));
            int position = i + 1;
            if (row.sortOrder() != position) {
                entityManager.createNativeQuery("update expense_category set sort_order = :position where id = :id")
                        .setParameter("position", position).setParameter("id", row.id()).executeUpdate();
                audit.category(actor, Action.CASHBOOK_CATEGORY_REORDERED, row.id(), row.auditName(),
                        List.of(new FieldChange("position", String.valueOf(row.sortOrder()),
                                String.valueOf(position))), now);
            }
        }
    }

    /** The category; empty when unknown or of another cooperative. Runs in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<CategoryRow> find(UUID id) {
        return single(rows("select " + COLUMNS + " from expense_category c where c.id = :id", Map.of("id", id)));
    }

    /** The categories of the ids that exist, by id. Runs in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    Map<UUID, CategoryRow> findAll(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return rows("select " + COLUMNS + " from expense_category c where c.id in (:ids)", Map.of("ids", ids))
                .stream().collect(Collectors.toMap(CategoryRow::id, Function.identity()));
    }

    /** The contract form of a category. */
    static ExpenseCategory view(CategoryRow row) {
        ExpenseCategory view = new ExpenseCategory(row.id(), ExpenseCategoryKind.fromValue(row.kind()),
                row.sortOrder(), row.archived());
        view.setSystemCode(row.systemCode());
        view.setName(row.name());
        return view;
    }

    /** A quick check against the names of the kind (case-insensitive, archived included); the index decides. */
    private void ensureNameFree(String kind, String name, @Nullable UUID self) {
        List<?> taken = entityManager.createNativeQuery("select c.id from expense_category c where c.kind = :kind "
                + "and lower(c.name) = lower(:name) and c.id <> cast(:self as uuid)")
                .setParameter("kind", kind)
                .setParameter("name", name)
                .setParameter("self", self == null ? new UUID(0, 0) : self)
                .getResultList();
        if (!taken.isEmpty()) {
            throw nameTaken();
        }
    }

    /** Runs a write; a violation of the name index (a concurrent request) is {@code CATEGORY_NAME_TAKEN}. */
    private static void execute(Runnable write) {
        try {
            write.run();
        } catch (PersistenceException | DataIntegrityViolationException e) {
            if (ConstraintViolations.violates(e, NAME_INDEX)) {
                throw nameTaken();
            }
            throw e;
        }
    }

    private List<CategoryRow> rows(String sql, Map<String, ?> parameters) {
        var query = entityManager.createNativeQuery(sql);
        parameters.forEach(query::setParameter);
        List<CategoryRow> rows = new ArrayList<>();
        for (Object result : query.getResultList()) {
            Object[] v = (Object[]) result;
            rows.add(new CategoryRow((UUID) v[0], (String) v[1], (String) v[2], (String) v[3], ((Number) v[4]).intValue(),
                    (Boolean) v[5]));
        }
        return rows;
    }

    private static Optional<CategoryRow> single(List<CategoryRow> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** The trimmed name; the contract limits the raw text to 1 to 100 characters, so only a blank one is left. */
    private static String cleanName(@Nullable String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("name", ProblemCodes.REQUIRED)));
        }
        return name;
    }

    private static ApiProblemException nameTaken() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.CATEGORY_NAME_TAKEN,
                "A category with this name already exists.",
                List.of(new FieldProblem("name", ProblemCodes.CATEGORY_NAME_TAKEN)));
    }
}
