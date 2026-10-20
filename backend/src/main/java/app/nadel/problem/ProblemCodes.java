package app.nadel.problem;

/** Error codes of the API contract (see the {@code code} field of the {@code Problem} schema). */
public final class ProblemCodes {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String MEMBER_NOT_LINKED = "MEMBER_NOT_LINKED";
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String EMAIL_TAKEN = "EMAIL_TAKEN";
    public static final String LAST_CHAIRMAN = "LAST_CHAIRMAN";
    public static final String ROLE_NAME_TAKEN = "ROLE_NAME_TAKEN";
    public static final String ROLE_IN_USE = "ROLE_IN_USE";
    public static final String BASE_ROLE_IMMUTABLE = "BASE_ROLE_IMMUTABLE";
    public static final String PLOT_NUMBER_TAKEN = "PLOT_NUMBER_TAKEN";
    public static final String CADASTRAL_NUMBER_TAKEN = "CADASTRAL_NUMBER_TAKEN";
    public static final String PLOT_ARCHIVED = "PLOT_ARCHIVED";
    public static final String PLOT_COMMON_LAND = "PLOT_COMMON_LAND";
    public static final String SHARES_NOT_ONE = "SHARES_NOT_ONE";
    public static final String DUPLICATE_OWNER = "DUPLICATE_OWNER";
    public static final String OWNERSHIP_DATE_INVALID = "OWNERSHIP_DATE_INVALID";
    public static final String OWNERSHIP_OVERLAP = "OWNERSHIP_OVERLAP";
    public static final String MEMBER_ARCHIVED = "MEMBER_ARCHIVED";
    public static final String MEMBER_OWNS_PLOT = "MEMBER_OWNS_PLOT";
    public static final String PLOT_HAS_OWNERS = "PLOT_HAS_OWNERS";
    public static final String PLOT_HAS_OWNERSHIP_HISTORY = "PLOT_HAS_OWNERSHIP_HISTORY";
    /** 413: the uploaded file is over the size limit. */
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    /** 400: the upload is not an {@code .xlsx} workbook (also a corrupt, encrypted or oversized-when-unpacked one). */
    public static final String FILE_NOT_XLSX = "FILE_NOT_XLSX";
    /** 400: the workbook has more rows than one import accepts. */
    public static final String IMPORT_LIMIT_EXCEEDED = "IMPORT_LIMIT_EXCEEDED";
    /** 409: the preview has problems and cannot be committed. */
    public static final String IMPORT_HAS_ERRORS = "IMPORT_HAS_ERRORS";
    /** 409: the preview has no problems but nothing to create or update, so a commit would only add a no-op entry. */
    public static final String IMPORT_NOTHING_TO_COMMIT = "IMPORT_NOTHING_TO_COMMIT";
    /** 409: the data of the cooperative changed after the preview (or a rule broke while saving); upload again. */
    public static final String IMPORT_STALE = "IMPORT_STALE";
    /** 409: the import was committed before. */
    public static final String IMPORT_ALREADY_COMMITTED = "IMPORT_ALREADY_COMMITTED";
    /** 410: the preview is older than 30 minutes. */
    public static final String IMPORT_EXPIRED = "IMPORT_EXPIRED";

    /** Codes of single fields in {@code errors[]}. */
    public static final String REQUIRED = "REQUIRED";
    public static final String TOO_LONG = "TOO_LONG";
    public static final String INVALID = "INVALID";
    public static final String PHONE_INVALID = "PHONE_INVALID";
    public static final String CHANNEL_CONTACT_MISSING = "CHANNEL_CONTACT_MISSING";
    public static final String CADASTRAL_INVALID = "CADASTRAL_INVALID";
    public static final String AREA_INVALID = "AREA_INVALID";
    public static final String SHARE_INVALID = "SHARE_INVALID";
    public static final String MEMBER_NOT_FOUND = "MEMBER_NOT_FOUND";
    public static final String OWNERSHIP_NOT_FOUND = "OWNERSHIP_NOT_FOUND";

    /** Codes of the rows of an import file (see {@code ImportProblem} in the API contract). */
    public static final String SHEET_MISSING = "SHEET_MISSING";
    public static final String HEADER_MISSING = "HEADER_MISSING";
    public static final String DUPLICATE_IN_FILE = "DUPLICATE_IN_FILE";
    public static final String AREA_BOTH_OR_NONE = "AREA_BOTH_OR_NONE";
    public static final String DATE_INVALID = "DATE_INVALID";
    public static final String NUMBER_INVALID = "NUMBER_INVALID";
    public static final String YES_NO_INVALID = "YES_NO_INVALID";
    /** A member row with neither an e-mail nor a phone cannot be recognised on a later import. */
    public static final String MEMBER_KEY_MISSING = "MEMBER_KEY_MISSING";
    /** The name and first phone of a member row match two or more members of the cooperative. */
    public static final String MEMBER_AMBIGUOUS = "MEMBER_AMBIGUOUS";

    /** An Ownership row names a member key that no usable row of the Members sheet has. */
    public static final String UNKNOWN_MEMBER_KEY = "UNKNOWN_MEMBER_KEY";
    /** An Ownership row names a plot that is neither in the Plots sheet nor an active plot of the cooperative. */
    public static final String UNKNOWN_PLOT = "UNKNOWN_PLOT";
    /** The plot has current owners other than the file's: they are changed with a change of owner, not an import. */
    public static final String OWNERSHIP_CONFLICT = "OWNERSHIP_CONFLICT";
    /** The rows of one plot do not all have the same owner-since date. */
    public static final String OWNERSHIP_DATE_MISMATCH = "OWNERSHIP_DATE_MISMATCH";

    private ProblemCodes() {
    }
}
