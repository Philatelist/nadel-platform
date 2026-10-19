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

    private ProblemCodes() {
    }
}
