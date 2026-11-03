package app.nadel.treasury.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The descriptions of the stored receipt files, without their content ({@link ExpenseAttachmentRecord}). A plain
 * {@link Repository}: nothing here can load or write a file. Under RLS like every query of the application.
 */
interface ExpenseAttachmentRepository extends Repository<ExpenseAttachmentRecord, UUID> {

    /** The files of the expense in their order. */
    @Query("select a from ExpenseAttachmentRecord a where a.expenseId = :expenseId order by a.position")
    List<ExpenseAttachmentRecord> findByExpense(@Param("expenseId") UUID expenseId);

    /** The file {@code position} of the expense exists. */
    @Query("select count(a) > 0 from ExpenseAttachmentRecord a where a.expenseId = :expenseId "
            + "and a.position = :position")
    boolean exists(@Param("expenseId") UUID expenseId, @Param("position") short position);

    /** How many files each of the expenses has, as {@code [expenseId, count]}; expenses without files are absent. */
    @Query("select a.expenseId, count(a) from ExpenseAttachmentRecord a where a.expenseId in :expenseIds "
            + "group by a.expenseId")
    List<Object[]> countByExpense(@Param("expenseIds") Collection<UUID> expenseIds);
}
