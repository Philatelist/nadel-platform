package app.nadel.treasury.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The stored receipt files with their content ({@link ExpenseAttachmentContent}): the insert of a new upload and the
 * download of one file. Deliberately no {@code findAll} or list query. Under RLS like every query of the application.
 */
interface ExpenseAttachmentContentRepository extends Repository<ExpenseAttachmentContent, UUID> {

    /** Inserts the file (files are never changed). */
    ExpenseAttachmentContent save(ExpenseAttachmentContent attachment);

    /** Writes the pending inserts now, so a violated constraint surfaces inside the request. */
    void flush();

    /** The file {@code position} of the expense; empty when it has none or the expense is not visible. */
    @Query("select a from ExpenseAttachmentContent a where a.expenseId = :expenseId and a.position = :position")
    Optional<ExpenseAttachmentContent> findFile(@Param("expenseId") UUID expenseId,
            @Param("position") short position);
}
