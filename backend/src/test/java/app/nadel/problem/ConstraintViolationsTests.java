package app.nadel.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** The shapes in which pgjdbc, Hibernate and Spring report a violated constraint, single statement and batch. */
class ConstraintViolationsTests {

    private static final String UNIQUE = "plot_tenant_number_uq";
    private static final String SERVER_MESSAGE = "ERROR: duplicate key value violates unique constraint \""
            + UNIQUE + "\"";

    @Test
    void aHibernateExceptionThatNamesTheConstraint() {
        Throwable failure = new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", new SQLException("x", "23505"),
                        "insert", UNIQUE));

        assertThat(ConstraintViolations.violates(failure, UNIQUE)).isTrue();
    }

    @Test
    void aBatchWhoseOwnMessageNamesTheConstraint() {
        // what pgjdbc throws when a flushed batch fails: the statement and its values, then the server's message
        BatchUpdateException batch = new BatchUpdateException(
                "Batch entry 1 insert into plot (number) values ('A') was aborted: " + SERVER_MESSAGE
                        + "  Call getNextException to see other errors in the batch.", "23505", 0, new int[0], null);

        assertThat(ConstraintViolations.violates(wrapped(batch), UNIQUE)).isTrue();
    }

    @Test
    void aBatchWhoseNextExceptionNamesTheConstraint() {
        BatchUpdateException batch = new BatchUpdateException("Batch entry 0 was aborted", "23505", 0, new int[0],
                null);
        batch.setNextException(new SQLException(SERVER_MESSAGE, "23505"));

        assertThat(ConstraintViolations.violates(wrapped(batch), UNIQUE)).isTrue();
    }

    @Test
    void anExclusionViolationIsRecognisedToo() {
        SQLException exclusion = new SQLException(
                "ERROR: conflicting key value violates exclusion constraint \"ownership_no_overlap_ex\"", "23P01");

        assertThat(ConstraintViolations.violates(wrapped(exclusion), "ownership_no_overlap_ex")).isTrue();
    }

    @Test
    void anotherConstraintIsNotRecognised() {
        BatchUpdateException batch = new BatchUpdateException("Batch entry 0 was aborted: " + SERVER_MESSAGE,
                "23505", 0, new int[0], null);
        batch.setNextException(new SQLException(SERVER_MESSAGE, "23505"));

        assertThat(ConstraintViolations.violates(wrapped(batch), "member_tenant_email_uq")).isFalse();
    }

    @Test
    void aViolationOfAnotherKindThatMentionsTheNameIsNotRecognised() {
        SQLException foreignKey = new SQLException("ERROR: insert violates foreign key constraint \"" + UNIQUE + "\"",
                "23503");

        assertThat(ConstraintViolations.violates(wrapped(foreignKey), UNIQUE)).isFalse();
    }

    @Test
    void aCycleInTheChainEnds() {
        SQLException first = new SQLException("first", "23505");
        SQLException second = new SQLException("second", "23505");
        first.setNextException(second);
        second.setNextException(first);

        assertThat(ConstraintViolations.violates(first, UNIQUE)).isFalse();
    }

    private static Throwable wrapped(SQLException sql) {
        return new DataIntegrityViolationException("could not execute batch", new RuntimeException("flush", sql));
    }
}
