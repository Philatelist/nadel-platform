package app.nadel.plots;

import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of the Ownership sheet after the cells were read: the plot number as typed, the member the row's key stands
 * for, the share as typed ({@code 1/2}) and the owner-since date.
 *
 * @param row        the Excel row number, echoed in the plan
 * @param memberId   the member of the row (an existing one or the id a new one will get); null when the key could not
 *                   be resolved, which the caller has already reported: the plot of such a row is checked no further
 * @param validFrom  null for an empty cell or an unreadable date, which the caller has already reported
 */
public record OwnershipImportRow(
        int row,
        @Nullable String plotNumber,
        @Nullable UUID memberId,
        @Nullable String share,
        @Nullable LocalDate validFrom) {
}
