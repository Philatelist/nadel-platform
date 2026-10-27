package app.nadel.xlsx;

/**
 * {@link WorkbookGuard} refused a file. Carries only the {@link UnsafeWorkbookReason}; the message is a fixed English
 * sentence and never contains the file name or anything read from the file. The failure of the zip library, if any, is
 * deliberately not kept as the cause: its message can quote entry names.
 */
public final class UnsafeWorkbookException extends RuntimeException {

    private final UnsafeWorkbookReason reason;

    UnsafeWorkbookException(UnsafeWorkbookReason reason) {
        super("The file is not a safe .xlsx workbook: " + reason);
        this.reason = reason;
    }

    public UnsafeWorkbookReason reason() {
        return reason;
    }
}
