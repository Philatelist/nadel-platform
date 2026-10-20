package app.nadel.members;

import java.util.List;
import java.util.UUID;

/**
 * What the Excel import may ask of the members module. The import parses the file; the members module decides what
 * each row means for the cooperative's members, with the same rules as when a member is entered by hand.
 */
public interface MemberImport {

    /**
     * Plans the member rows of an import without saving anything: for each row whether it creates a member, updates
     * one or changes nothing, and every problem found.
     * <p>
     * Members are matched among the active members only. A row with an e-mail matches the member with that e-mail
     * (ignoring case); a row without one matches the member with the same last name, first name (ignoring case) and
     * first phone (compared in international format). A blank value leaves the existing value of a matched member
     * unchanged; a filled phone cell replaces the phones. Runs inside the caller's transaction (the cooperative's
     * data is read under row-level security).
     *
     * @param tenantId the cooperative of the caller
     * @param importId the id of the import, from which the ids of new members are derived
     * @param rows     the rows in file order
     * @return one item per row, in the same order
     */
    MemberImportPlan plan(UUID tenantId, UUID importId, List<MemberImportRow> rows);
}
