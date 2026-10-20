package app.nadel.problem;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;

/**
 * Recognises a violated database constraint by its name in whatever shape the failure reaches the application.
 * <p>
 * A single statement fails with a Hibernate {@code ConstraintViolationException} that knows the constraint. A flushed
 * JDBC batch fails with a {@code BatchUpdateException} instead, whose own message is "Batch entry N insert into ... was
 * aborted: ERROR: duplicate key value violates unique constraint "name"" and whose real error is the next exception of
 * the chain ({@link SQLException#getNextException()}), not its cause. This walks causes and next exceptions and accepts
 * a unique violation (SQLSTATE 23505) or an exclusion violation (23P01) that names the constraint, or a Hibernate
 * exception that names it.
 * <p>
 * Never log or return the messages it inspects: the batch message contains the values of the row.
 */
public final class ConstraintViolations {

    private static final Set<String> UNIQUE_OR_EXCLUSION = Set.of("23505", "23P01");

    private ConstraintViolations() {
    }

    /** Whether the failure, or anything it wraps, is a violation of the constraint or unique index of that name. */
    public static boolean violates(Throwable failure, String constraint) {
        String quoted = "\"" + constraint + "\"";
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable t = pending.poll();
            if (!seen.add(t)) {
                continue;
            }
            if (t instanceof ConstraintViolationException hibernate && constraint.equals(hibernate.getConstraintName())) {
                return true;
            }
            if (t instanceof SQLException sql) {
                if (UNIQUE_OR_EXCLUSION.contains(sql.getSQLState()) && mentions(sql, quoted)) {
                    return true;
                }
                add(pending, sql.getNextException());
            } else if (mentions(t, quoted)) {
                return true;
            }
            add(pending, t.getCause());
        }
        return false;
    }

    private static boolean mentions(Throwable t, String quoted) {
        return t.getMessage() != null && t.getMessage().contains(quoted);
    }

    private static void add(Deque<Throwable> pending, @Nullable Throwable next) {
        if (next != null) {
            pending.add(next);
        }
    }
}
