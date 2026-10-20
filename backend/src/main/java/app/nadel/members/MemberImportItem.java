package app.nadel.members;

import app.nadel.problem.FieldProblem;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The plan for one row. A row with problems has no action and no member id: it cannot be imported.
 *
 * @param row           the Excel row number of the input row
 * @param action        null when there are problems
 * @param memberId      the matched member, or the id the new member will get; null when there are problems
 * @param changedFields names of the fields an update changes ({@code lastName}, {@code firstName},
 *                      {@code middleName}, {@code phones}, {@code email}, {@code postalAddress},
 *                      {@code deliveryChannel}, {@code telegramUsername}, {@code viberPhone}); empty otherwise
 * @param problems      one problem per invalid field, named as in the member form ({@code phones} for any phone,
 *                      {@code externalId} for the key); {@code MEMBER_KEY_MISSING} is reported on {@code email} and
 *                      {@code MEMBER_AMBIGUOUS} on {@code phones}
 */
public record MemberImportItem(
        int row,
        @Nullable MemberImportAction action,
        @Nullable UUID memberId,
        List<String> changedFields,
        List<FieldProblem> problems) {

    public MemberImportItem {
        changedFields = List.copyOf(changedFields);
        problems = List.copyOf(problems);
    }
}
