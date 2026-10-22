package app.nadel.problem;

/** Error codes of the API contract (see the {@code code} field of the {@code Problem} schema). */
public final class ProblemCodes {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String MEMBER_NOT_LINKED = "MEMBER_NOT_LINKED";
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";
    public static final String NOT_FOUND = "NOT_FOUND";
    /**
     * 404 of the plot-scoped meter endpoints a member may call: the plot is unknown, of another cooperative or not one
     * the caller may see. Every case is the same answer, so nothing leaks.
     */
    public static final String PLOT_NOT_FOUND = "PLOT_NOT_FOUND";
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
    /** 409: another non-archived charge type has the name, or it is the default name of a seeded type not renamed. */
    public static final String CHARGE_TYPE_NAME_TAKEN = "CHARGE_TYPE_NAME_TAKEN";
    /** 409: the charge type is archived and can be neither changed nor archived again. */
    public static final String CHARGE_TYPE_ARCHIVED = "CHARGE_TYPE_ARCHIVED";
    /** 409: the submitted order is not the current set of non-archived charge types. */
    public static final String CHARGE_TYPE_ORDER_STALE = "CHARGE_TYPE_ORDER_STALE";
    /** 409: another version of the tariff starts on the same date. */
    public static final String TARIFF_VERSION_DATE_TAKEN = "TARIFF_VERSION_DATE_TAKEN";
    /** 409: a charge uses the tariff version, so it can be neither corrected nor deleted (a correction is a recalculation). */
    public static final String TARIFF_VERSION_IN_USE = "TARIFF_VERSION_IN_USE";
    /** 409: the only version of a tariff cannot be deleted; a tariff always has a version. */
    public static final String TARIFF_LAST_VERSION = "TARIFF_LAST_VERSION";
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
    /** 409: a plot that has a meter is deleted or turned into common land, or a plot with an active meter is archived. */
    public static final String PLOT_HAS_METER = "PLOT_HAS_METER";
    /** 409: the plot already has an active meter of the resource. */
    public static final String METER_ACTIVE_EXISTS = "METER_ACTIVE_EXISTS";
    /** 409: common land has no plot meters. */
    public static final String METER_COMMON_LAND = "METER_COMMON_LAND";
    /** 409: a meter with readings is never deleted; it is removed instead. */
    public static final String METER_HAS_READINGS = "METER_HAS_READINGS";
    /** 409: the meter is removed, so it can be neither removed again nor deleted. */
    public static final String METER_REMOVED = "METER_REMOVED";
    /** 409: the meter has readings waiting for approval, so it is neither removed nor replaced until they are decided. */
    public static final String METER_HAS_PENDING_READINGS = "METER_HAS_PENDING_READINGS";
    /** 409: a change of the electricity tree breaks its rules; {@code errors[]} names the rule ({@code TREE_*}). */
    public static final String METER_TREE_INVALID = "METER_TREE_INVALID";
    /** 409: the meter has meters under it in the tree, so it is not deleted, nor removed while they work under it. */
    public static final String METER_HAS_CHILDREN = "METER_HAS_CHILDREN";
    /** 409: a reading (also the initial or final reading of a meter) dated after today (Europe/Kyiv). */
    public static final String READING_DATE_IN_FUTURE = "READING_DATE_IN_FUTURE";
    /** 409: a reading dated before the installation or after the removal of its meter. */
    public static final String READING_DATE_OUTSIDE_METER = "READING_DATE_OUTSIDE_METER";
    /** 409: a reading lower than the previous one or higher than the next one, or dated before a later one. */
    public static final String READING_OUT_OF_ORDER = "READING_OUT_OF_ORDER";
    /** 409: a charge uses the readings around the date, so they are no longer corrected, deleted or inserted into. */
    public static final String READING_IN_USE = "READING_IN_USE";
    /**
     * 409: the reading is not counted (pending, rejected or superseded), so it can be neither corrected nor deleted; or it
     * is not pending, so it can be neither approved nor rejected.
     */
    public static final String READING_STATUS_INVALID = "READING_STATUS_INVALID";

    /** Codes of single fields in {@code errors[]}. */
    public static final String REQUIRED = "REQUIRED";
    public static final String TOO_LONG = "TOO_LONG";
    public static final String INVALID = "INVALID";
    public static final String PHONE_INVALID = "PHONE_INVALID";
    public static final String CHANNEL_CONTACT_MISSING = "CHANNEL_CONTACT_MISSING";
    public static final String CADASTRAL_INVALID = "CADASTRAL_INVALID";
    public static final String AREA_INVALID = "AREA_INVALID";
    public static final String SHARE_INVALID = "SHARE_INVALID";
    /** A tariff rate that is not a non-negative number with at most 10 integer and 4 decimal digits. */
    public static final String RATE_INVALID = "RATE_INVALID";
    /** One-off without a fixed one-time amount, or a fixed one-time amount without one-off. */
    public static final String FREQUENCY_BASIS_MISMATCH = "FREQUENCY_BASIS_MISMATCH";
    /** Common land named in the scope of a tariff (selected plots or exclusions); it has no tariffs. */
    public static final String TARIFF_COMMON_LAND = "TARIFF_COMMON_LAND";
    /** The same plot listed twice in one list of a request. */
    public static final String DUPLICATE = "DUPLICATE";
    public static final String MEMBER_NOT_FOUND = "MEMBER_NOT_FOUND";
    public static final String OWNERSHIP_NOT_FOUND = "OWNERSHIP_NOT_FOUND";
    /** A meter reading that is not a non-negative number with at most 11 integer and 3 decimal digits. */
    public static final String METER_VALUE_INVALID = "METER_VALUE_INVALID";
    /** With {@link #METER_TREE_INVALID}: a water meter has no tree. */
    public static final String TREE_WATER = "TREE_WATER";
    /** With {@link #METER_TREE_INVALID}: a quarter meter under another quarter meter. */
    public static final String TREE_QUARTER_UNDER_QUARTER = "TREE_QUARTER_UNDER_QUARTER";
    /** With {@link #METER_TREE_INVALID}: the kinds of the meter and the parent do not allow the link. */
    public static final String TREE_PARENT_KIND = "TREE_PARENT_KIND";
    /** With {@link #METER_TREE_INVALID}: the parent is removed or not yet installed on the effective date. */
    public static final String TREE_PARENT_INACTIVE = "TREE_PARENT_INACTIVE";
    /** With {@link #METER_TREE_INVALID}: an effective date before the meter's latest change in the tree. */
    public static final String TREE_DATE_BEFORE_LATEST = "TREE_DATE_BEFORE_LATEST";

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
