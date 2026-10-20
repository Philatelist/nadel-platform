package app.nadel.members;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
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

    /**
     * Locks the existing members a plan matches ({@code UPDATE} and {@code UNCHANGED} items) with an exclusive row
     * lock ({@code FOR UPDATE}), in id order, until the end of the caller's transaction. A concurrent archiving or
     * edit of one of them waits, so a plan computed after this call stays true until the caller commits. The plots
     * module locks its rows first (a change of owner takes the plot, then its owners), so the caller locks plots,
     * then members. Must run in the caller's transaction.
     */
    void lock(MemberImportPlan plan);

    /**
     * Saves what a plan says: creates the {@code NEW} members with the ids the plan gave them and updates the
     * {@code UPDATE} members, with the same rules as when a member is entered by hand (a blank cell leaves the stored
     * value). One {@code MEMBER_CREATED} or {@code MEMBER_UPDATED} audit event is built per changed member, credited
     * to {@code actor}, and <b>returned, not published</b>: the caller publishes them (the import as
     * {@link app.nadel.audit.AuditBatch}es) in the same transaction. Rows are inserted in batches. Must run in the caller's transaction, which this method never
     * starts or commits.
     * <p>
     * The plan must be the one computed in the same transaction after {@link #lock}; a row that no longer matches
     * what the plan says, or an e-mail that was taken meanwhile, is refused with 409 {@code IMPORT_STALE}.
     *
     * @param tenantId the cooperative of the caller
     * @param rows     the rows the plan was computed from
     * @param plan     the plan of {@code rows}, item for item
     * @param actor    who is credited in the audit log
     * @return the audit events of the changed members, in row order; the caller must publish them
     */
    List<AuditEvent> apply(UUID tenantId, List<MemberImportRow> rows, MemberImportPlan plan, Actor actor);
}
