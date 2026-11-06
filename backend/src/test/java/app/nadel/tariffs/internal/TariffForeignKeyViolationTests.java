package app.nadel.tariffs.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The last guard of deleting a tariff (feature 020): a foreign key violation (SQLSTATE 23503) anywhere in the failure,
 * as a cause or as the next exception of a JDBC batch, becomes 409 {@code TARIFF_IN_USE}; any other integrity failure
 * is not taken for one.
 */
class TariffForeignKeyViolationTests {

    @Test
    void aForeignKeyViolationAsTheCauseIsRecognised() {
        var failure = new DataIntegrityViolationException("delete failed",
                new RuntimeException("wrapped", new SQLException("violates foreign key", "23503")));

        assertThat(TariffCommands.isForeignKeyViolation(failure)).isTrue();
    }

    @Test
    void aForeignKeyViolationAsTheNextExceptionOfABatchIsRecognised() {
        var batch = new BatchUpdateException("Batch entry 0 was aborted", "23000", new int[0]);
        batch.setNextException(new SQLException("violates foreign key", "23503"));

        assertThat(TariffCommands.isForeignKeyViolation(new DataIntegrityViolationException("flush", batch)))
                .isTrue();
    }

    @Test
    void aFailureReachedTwiceInTheChainIsExaminedOnceAndTheCheckEnds() {
        // the driver hands the same exception out as the cause and as the next exception of the batch
        var unique = new SQLException("duplicate key value", "23505");
        var batch = new BatchUpdateException("Batch entry 0 was aborted", "23000", new int[0]);
        batch.setNextException(unique);
        batch.initCause(unique);

        assertThat(TariffCommands.isForeignKeyViolation(new DataIntegrityViolationException("flush", batch)))
                .isFalse();
    }

    @Test
    void anotherIntegrityFailureIsNotOne() {
        var unique = new DataIntegrityViolationException("insert failed",
                new SQLException("duplicate key value", "23505"));

        assertThat(TariffCommands.isForeignKeyViolation(unique)).isFalse();
        assertThat(TariffCommands.isForeignKeyViolation(new DataIntegrityViolationException("no cause"))).isFalse();
    }
}
