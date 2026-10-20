package app.nadel.members;

/** What an import row does to the members of the cooperative. */
public enum MemberImportAction {
    /** No active member matches the row: a member is created. */
    NEW,
    /** An active member matches the row and at least one field differs. */
    UPDATE,
    /** An active member matches the row and every field is already as in the file. */
    UNCHANGED
}
