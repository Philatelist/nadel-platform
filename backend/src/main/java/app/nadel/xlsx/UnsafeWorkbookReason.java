package app.nadel.xlsx;

/** Why {@link WorkbookGuard} refused a file. A neutral code: callers decide what their API answers. */
public enum UnsafeWorkbookReason {

    /** The file name does not end with {@code .xlsx}. */
    WRONG_EXTENSION,
    /** The content does not start with the zip signature: not a zip, or an OLE2 file (old {@code .xls}, encrypted). */
    NOT_A_ZIP,
    /** The archive lacks {@code [Content_Types].xml} or {@code xl/workbook.xml}. */
    NOT_A_WORKBOOK,
    /** The archive has more entries than allowed. */
    TOO_MANY_ENTRIES,
    /** One entry unpacks to more bytes than allowed. */
    ENTRY_TOO_LARGE,
    /** All entries together unpack to more bytes than allowed. */
    TOTAL_TOO_LARGE,
    /** The archive is corrupt, truncated or uses a compression or encryption that cannot be read. */
    UNREADABLE
}
