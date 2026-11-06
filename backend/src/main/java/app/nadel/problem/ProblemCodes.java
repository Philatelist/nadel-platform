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
    /**
     * 500: a stored personal value could not be decrypted (malformed, copied from another row or sealed with another
     * key). The answer never carries a value; the server log names the table, column and row id only.
     */
    public static final String PERSONAL_DATA_UNREADABLE = "PERSONAL_DATA_UNREADABLE";
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
    /** 409: a charge was made from a version of the tariff (a reversed one included), so it cannot be deleted. */
    public static final String TARIFF_IN_USE = "TARIFF_IN_USE";
    /** 409: the tariff is archived; it gets no new version, version change or scope change and no second archive. */
    public static final String TARIFF_ARCHIVED = "TARIFF_ARCHIVED";
    /** 409: an unreversed charge was made from the tariff for the chosen end date or a later day. */
    public static final String TARIFF_CHARGED_AFTER_END = "TARIFF_CHARGED_AFTER_END";
    /** 413: the uploaded file is over the size limit. */
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    /** 400: the upload is not an {@code .xlsx} workbook (also a corrupt, encrypted or oversized-when-unpacked one). */
    public static final String FILE_NOT_XLSX = "FILE_NOT_XLSX";
    /**
     * 400: the IBAN is not a valid Ukrainian IBAN ({@code UA} and 27 digits with a correct mod-97 check); also the code
     * of the field {@code iban} in {@code errors[]}.
     */
    public static final String IBAN_INVALID = "IBAN_INVALID";
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
    /** 409: a plot with a lasting water connection is archived or turned into common land. */
    public static final String PLOT_HAS_WATER_CONNECTION = "PLOT_HAS_WATER_CONNECTION";
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
    /** 409: a plot that has charges (any kind, reversed or not) is never deleted; the ledger keeps them. */
    public static final String PLOT_HAS_CHARGES = "PLOT_HAS_CHARGES";
    /** 409: a plot that has payments is never deleted; the ledger keeps them (asked after {@link #PLOT_HAS_CHARGES}). */
    public static final String PLOT_HAS_PAYMENTS = "PLOT_HAS_PAYMENTS";
    /** 400: the allocation lines of a payment together exceed the payment ({@code errors[{field: lines}]}). */
    public static final String ALLOCATION_EXCEEDS_PAYMENT = "ALLOCATION_EXCEEDS_PAYMENT";
    /** 400: an allocation line exceeds what is open on its charge ({@code errors[{field: lines[j].amount}]}). */
    public static final String ALLOCATION_EXCEEDS_CHARGE = "ALLOCATION_EXCEEDS_CHARGE";
    /**
     * 400: the parts of a payment split over several plots do not add up to the payment
     * ({@code errors[{field: split.parts}]}).
     */
    public static final String SPLIT_TOTAL_MISMATCH = "SPLIT_TOTAL_MISMATCH";
    /**
     * 409: the payment was reversed before (a payment is reversed once and a reversed payment is not reallocated), or
     * the id names a reversal row.
     */
    public static final String PAYMENT_ALREADY_REVERSED = "PAYMENT_ALREADY_REVERSED";
    /** 409: a bank payment has no printed cash receipt (feature 016 §2.8). */
    public static final String RECEIPT_NOT_AVAILABLE = "RECEIPT_NOT_AVAILABLE";
    /**
     * 409: an invoice is built while requisites it needs are not set (feature 016 §2.2); the problem names them in
     * {@code missing} ({@code IBAN}, {@code EDRPOU}).
     */
    public static final String REQUISITES_MISSING = "REQUISITES_MISSING";
    /** 409: an invoice is built for common land, an archived plot or a plot without a current owner (feature 016). */
    public static final String PLOT_NOT_INVOICEABLE = "PLOT_NOT_INVOICEABLE";
    /** 409: a debt certificate that is already annulled is annulled again (feature 016 §2.7). */
    public static final String CERTIFICATE_ALREADY_ANNULLED = "CERTIFICATE_ALREADY_ANNULLED";
    /**
     * 422: a month has more invoiceable plots than one build of its invoices takes (feature 016 §2.5); the problem
     * carries the limit in {@code limit}.
     */
    public static final String INVOICE_BUILD_TOO_LARGE = "INVOICE_BUILD_TOO_LARGE";
    /**
     * 409: a build of the month's invoices is asked while another build of the same month (or an on-request invoice of
     * it) runs; nothing is built (feature 016 §2.5).
     */
    public static final String INVOICE_BUILD_RUNNING = "INVOICE_BUILD_RUNNING";
    /** 409: a check of the cooperative's mailbox or bot is asked while it is not set up (feature 017 §2.1). */
    public static final String DELIVERY_NOT_CONFIGURED = "DELIVERY_NOT_CONFIGURED";
    /**
     * 400: a mailbox's mail server is not one the server may connect to: a port other than 25, 465 or 587, or a host
     * that resolves to a loopback, link-local, private or metadata address (feature 017 §2.1, outside the dev profile).
     */
    public static final String SMTP_HOST_NOT_ALLOWED = "SMTP_HOST_NOT_ALLOWED";
    /** 409: a test letter is asked by a member who has no e-mail of their own to receive it (feature 017 §2.1). */
    public static final String NO_OWN_EMAIL = "NO_OWN_EMAIL";
    /** 409: a Telegram link is asked while the cooperative has no working bot (feature 017 §2.2). */
    public static final String TELEGRAM_NOT_CONFIGURED = "TELEGRAM_NOT_CONFIGURED";
    /** 409: a paper delivery, or one that is still queued, is sent again (feature 017 §2.4). */
    public static final String DELIVERY_NOT_RETRYABLE = "DELIVERY_NOT_RETRYABLE";
    /** 409: a member without an e-mail is invited; the e-mail is the login (feature 020 §2.1). */
    public static final String MEMBER_EMAIL_MISSING = "MEMBER_EMAIL_MISSING";
    /** 409: a member who is already invited is invited again; the invitation is sent again instead (feature 020). */
    public static final String INVITATION_PENDING = "INVITATION_PENDING";
    /** 409: a member who already has access is invited (feature 020). */
    public static final String ACCESS_ALREADY_ACTIVE = "ACCESS_ALREADY_ACTIVE";
    /**
     * 409: the member's e-mail is already a sign-in login in another cooperative (or in none); the answer never says
     * which (feature 020 §2.1).
     */
    public static final String EMAIL_IN_OTHER_COOPERATIVE = "EMAIL_IN_OTHER_COOPERATIVE";
    /**
     * 409: the sign-in account with the member's e-mail is already linked to another member of this cooperative, or
     * the stored account belongs to another cooperative (feature 020).
     */
    public static final String SIGN_IN_ACCOUNT_IN_USE = "SIGN_IN_ACCOUNT_IN_USE";
    /** 409: the invitation is sent again for a member who is not invited (feature 020). */
    public static final String NOT_INVITED = "NOT_INVITED";
    /** 409: access is turned off for a member who was never invited (feature 020). */
    public static final String ACCESS_NOT_GRANTED = "ACCESS_NOT_GRANTED";
    /** 409: a member turns off their own access; another holder of the permission must do it (feature 020). */
    public static final String OWN_ACCESS = "OWN_ACCESS";
    /**
     * 502: the invitation letter could not be sent; the problem carries the delivery failure in {@code failureCode},
     * the access status is unchanged and an earlier link still works (feature 020).
     */
    public static final String INVITATION_NOT_SENT = "INVITATION_NOT_SENT";
    /** 503: the sign-in service (Keycloak) could not be reached or did not answer in time (feature 020). */
    public static final String SIGN_IN_SERVICE_UNAVAILABLE = "SIGN_IN_SERVICE_UNAVAILABLE";
    /**
     * 404 of the public invitation endpoints: the link is unknown, used, replaced, expired, or the member's access is
     * off or the member is archived; one code for every case, so nothing leaks (feature 020).
     */
    public static final String INVITATION_INVALID = "INVITATION_INVALID";
    /**
     * 429 of the public invitation endpoints: too many requests from one client address, or for one invitation e-mail,
     * within the window ({@code nadel.invitations.rate-limit.*}, feature 020); nothing was checked or changed.
     */
    public static final String TOO_MANY_REQUESTS = "TOO_MANY_REQUESTS";
    /**
     * 400: an announcement's text is longer than 2000 characters (Unicode code points, after the surrounding spaces are
     * removed; feature 017 §2.6); also with the field code {@code TOO_LONG} of {@code text} in {@code errors[]}.
     */
    public static final String ANNOUNCEMENT_TOO_LONG = "ANNOUNCEMENT_TOO_LONG";
    /**
     * 409: the opening balance of a money account is entered again; it is entered once and never changed (feature 018
     * §2.1).
     */
    public static final String OPENING_BALANCE_ALREADY_SET = "OPENING_BALANCE_ALREADY_SET";
    /**
     * 409: a cash book entry other than a member payment is made on an account that has no opening balance yet (feature
     * 018 §2.1).
     */
    public static final String OPENING_BALANCE_MISSING = "OPENING_BALANCE_MISSING";
    /**
     * 400: a cash book entry other than a member payment is dated before the account's opening date (feature 018 §2.1);
     * {@code errors[]} names the date field.
     */
    public static final String BEFORE_OPENING_BALANCE = "BEFORE_OPENING_BALANCE";
    /**
     * 409: a cash book entry would make the cash balance negative on its date or a later day (feature 018 §2.2); the
     * problem carries the lowest balance and its day in {@code balance} and {@code date}.
     */
    public static final String CASH_INSUFFICIENT = "CASH_INSUFFICIENT";
    /** 409: the cash book entry was reversed before; an entry is reversed once (feature 018 §2.8). */
    public static final String ENTRY_ALREADY_REVERSED = "ENTRY_ALREADY_REVERSED";
    /** 409: the difference of a cash count is booked again; a difference is booked once (feature 018 §2.6). */
    public static final String DIFFERENCE_ALREADY_BOOKED = "DIFFERENCE_ALREADY_BOOKED";
    /** 409: the difference of a cash count that matches the books (0.00) is booked (feature 018 §2.6). */
    public static final String NO_DIFFERENCE = "NO_DIFFERENCE";
    /**
     * 409: a journal line is reversed directly that is not a booked difference, or is a reversal line itself; other
     * entries are reversed through their own records (feature 018 §2.8).
     */
    public static final String MOVEMENT_NOT_REVERSIBLE = "MOVEMENT_NOT_REVERSIBLE";
    /**
     * 400: the four figures of a bank statement do not agree (opening + incoming − outgoing ≠ closing, feature 018
     * §2.7); also the code of the field {@code closing} in {@code errors[]}.
     */
    public static final String STATEMENT_INCONSISTENT = "STATEMENT_INCONSISTENT";
    /**
     * 409: a bank month is marked reconciled without its statement or while the closing difference between the
     * statement and the books is not 0.00 (feature 018 §2.7; the other differences do not block).
     */
    public static final String BANK_MONTH_NOT_BALANCED = "BANK_MONTH_NOT_BALANCED";
    /**
     * 409: a reconciled bank month gets its statement entered again, is marked reconciled again or gets a bank
     * difference (feature 018 §2.7); it leaves that status only when a bank line dated in it is added.
     */
    public static final String BANK_MONTH_RECONCILED = "BANK_MONTH_RECONCILED";
    /** 409: a bank difference is booked for a month whose statement is not entered (feature 018 §2.7). */
    public static final String BANK_STATEMENT_MISSING = "BANK_STATEMENT_MISSING";
    /**
     * 409: another expense or income category of the same list has the name, ignoring case (feature 018 §2.9); also
     * the code of the field {@code name} in {@code errors[]}.
     */
    public static final String CATEGORY_NAME_TAKEN = "CATEGORY_NAME_TAKEN";
    /**
     * 400: an expense attachment is not a JPEG, PNG or PDF by its content, or a JPEG or PNG that cannot be read
     * (feature 018 §2.3); also the code of the field {@code files[i]} in {@code errors[]}.
     */
    public static final String FILE_TYPE_NOT_ALLOWED = "FILE_TYPE_NOT_ALLOWED";
    /**
     * 400: more files come with an expense than it takes (five, reused ones included; feature 018 §2.3); also the code
     * of the field {@code files} in {@code errors[]}.
     */
    public static final String TOO_MANY = "TOO_MANY";
    /** 409: the plot's prepayment is allocated by hand while it has no prepayment or no open charge. */
    public static final String NOTHING_TO_ALLOCATE = "NOTHING_TO_ALLOCATE";
    /** 409: the charge was reversed before; a charge is reversed at most once. */
    public static final String CHARGE_ALREADY_REVERSED = "CHARGE_ALREADY_REVERSED";
    /** 409: the charge is a reversal, which cannot itself be reversed. */
    public static final String REVERSAL_NOT_REVERSIBLE = "REVERSAL_NOT_REVERSIBLE";
    /** 409: the charge is an adjustment, which is never reversed; a later recalculation adjusts the original again. */
    public static final String ADJUSTMENT_NOT_REVERSIBLE = "ADJUSTMENT_NOT_REVERSIBLE";
    /** 409: the charge is booked in a closed billing month; its correction is an adjustment (a recalculation). */
    public static final String CHARGE_IN_CLOSED_MONTH = "CHARGE_IN_CLOSED_MONTH";
    /** 409: a billing month is closed while an earlier month is still open; months close in order. */
    public static final String PERIOD_CLOSE_ORDER = "PERIOD_CLOSE_ORDER";
    /** 409: the billing month is closed; a closed month never changes and is never closed again. */
    public static final String PERIOD_CLOSED = "PERIOD_CLOSED";
    /** 409: a month after the current one (Europe/Kyiv) cannot be closed or charged. */
    public static final String PERIOD_IN_FUTURE = "PERIOD_IN_FUTURE";

    /** 409: a run is booked with the digest of a preview that no longer matches what it would create. */
    public static final String PREVIEW_OUTDATED = "PREVIEW_OUTDATED";
    /** 409: a losses run is asked while the cooperative charges losses as a fixed percentage. */
    public static final String LOSSES_MODE_NOT_PROPORTIONAL = "LOSSES_MODE_NOT_PROPORTIONAL";
    /** 409: a booked losses run charged a node of the new run for a day of its range. */
    public static final String LOSSES_RANGE_OVERLAP = "LOSSES_RANGE_OVERLAP";
    /**
     * 409: a common consumer run is booked while the {@code COMMON_CONSUMERS} charge type has no per-kWh tariff for the
     * range (or is inactive or archived): nothing can be priced.
     */
    public static final String NO_COMMON_CONSUMERS_TARIFF = "NO_COMMON_CONSUMERS_TARIFF";
    /** 409: a booked common consumer run charged a consumer of the new run for a day of its range. */
    public static final String COMMON_CONSUMER_RANGE_OVERLAP = "COMMON_CONSUMER_RANGE_OVERLAP";
    /** 409: the run was reversed before; a run is reversed once. */
    public static final String RUN_ALREADY_REVERSED = "RUN_ALREADY_REVERSED";
    /** 409: the charges of the run are booked in a closed billing month; their correction is an adjustment. */
    public static final String RUN_IN_CLOSED_MONTH = "RUN_IN_CLOSED_MONTH";
    /** 409: a manual recalculation finds nothing to change (expected equals effective for every charge). */
    public static final String NOTHING_TO_RECALCULATE = "NOTHING_TO_RECALCULATE";
    /** 409: a manual recalculation spans more than 24 months. */
    public static final String RECALCULATION_RANGE_TOO_LONG = "RECALCULATION_RANGE_TOO_LONG";

    /** 409: the plot is connected to water on one of the days of the new connection (or has a lasting one). */
    public static final String WATER_ALREADY_CONNECTED = "WATER_ALREADY_CONNECTED";
    /** 409: the connection has ended already, or the plot has no lasting connection to that water source. */
    public static final String WATER_NOT_CONNECTED = "WATER_NOT_CONNECTED";
    /** 409: the water source is not an active common consumer meter of the cooperative. */
    public static final String WATER_SOURCE_INVALID = "WATER_SOURCE_INVALID";
    /** 409: plots are or were connected to the meter as their water source, so it is not deleted. */
    public static final String WATER_SOURCE_IN_USE = "WATER_SOURCE_IN_USE";

    /**
     * 400: the filters do not fit the report (a required filter is missing, a range ends before it starts, an unknown
     * charge type); {@code errors[]} names each query parameter.
     */
    public static final String REPORT_FILTER_INVALID = "REPORT_FILTER_INVALID";
    /** 422: a sheet of the report would have more than 20 000 rows; the user narrows the filters. */
    public static final String REPORT_TOO_LARGE = "REPORT_TOO_LARGE";
    /**
     * 400: the uploaded file is not a usable report template; {@code errors[]} has one entry on {@code file} whose code
     * names why ({@link #NOT_XLSX}, {@link #MACROS}, {@link #ACTIVE_CONTENT}, {@link #BROKEN_ZIP},
     * {@link #UNPACKED_TOO_LARGE}, {@link #TWO_ROW_SETS}, {@link #ARRAY_ACROSS_ROW}).
     */
    public static final String TEMPLATE_INVALID = "TEMPLATE_INVALID";
    /** 422: the template file is over 2 MB; checked before the file is read. */
    public static final String TEMPLATE_TOO_LARGE = "TEMPLATE_TOO_LARGE";
    /** 409: the template is archived: it is not generated from and takes no new version. */
    public static final String TEMPLATE_ARCHIVED = "TEMPLATE_ARCHIVED";
    /** 409: another active template of the cooperative has the name, ignoring case; {@code errors[]} names it. */
    public static final String TEMPLATE_NAME_TAKEN = "TEMPLATE_NAME_TAKEN";
    /** 409: an active template is in the slot already; a new file goes in as its new version. */
    public static final String TEMPLATE_SLOT_TAKEN = "TEMPLATE_SLOT_TAKEN";
    /**
     * 409: another version of the template was stored at the same moment (the backstop of the version number's unique
     * key behind the row lock on the template); the client sends the file again.
     */
    public static final String TEMPLATE_VERSION_CONFLICT = "TEMPLATE_VERSION_CONFLICT";

    /** Codes of {@code errors[{field: file}]} with {@link #TEMPLATE_INVALID}. */
    public static final String NOT_XLSX = "NOT_XLSX";
    public static final String MACROS = "MACROS";
    public static final String ACTIVE_CONTENT = "ACTIVE_CONTENT";
    public static final String BROKEN_ZIP = "BROKEN_ZIP";
    public static final String UNPACKED_TOO_LARGE = "UNPACKED_TOO_LARGE";
    public static final String TWO_ROW_SETS = "TWO_ROW_SETS";
    public static final String ARRAY_ACROSS_ROW = "ARRAY_ACROSS_ROW";

    /** Codes of single fields in {@code errors[]}. */
    public static final String REQUIRED = "REQUIRED";
    public static final String TOO_LONG = "TOO_LONG";
    /** Under the minimum length (a password under 8 characters). */
    public static final String TOO_SHORT = "TOO_SHORT";
    /** The sign-in service refused a password by its own rules (field {@code password}). */
    public static final String PASSWORD_POLICY = "PASSWORD_POLICY";
    public static final String INVALID = "INVALID";
    /** An archived record (an expense category) named for a new entry; it stays only on old ones. */
    public static final String ARCHIVED = "ARCHIVED";
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
    /** The end date of a tariff archive that is not after the start of its last version (field {@code endsOn}). */
    public static final String TARIFF_END_BEFORE_LAST_VERSION = "TARIFF_END_BEFORE_LAST_VERSION";
    /** An amount that must be greater than zero (an opening balance) is zero or negative. */
    public static final String AMOUNT_NOT_POSITIVE = "AMOUNT_NOT_POSITIVE";
    /** A date that must not be after today in the cooperative's time zone (an opening balance) is in the future. */
    public static final String DATE_IN_FUTURE = "DATE_IN_FUTURE";
    /** A value the server does not handle (a recalculation cause type it does not know). */
    public static final String UNSUPPORTED = "UNSUPPORTED";
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
    /** An Opening balances row names a charge type that no charge type of the cooperative is called. */
    public static final String UNKNOWN_CHARGE_TYPE = "UNKNOWN_CHARGE_TYPE";
    /**
     * An Opening balances row: the plot already has an opening balance of the charge type and date with another
     * amount that is not reversed.
     */
    public static final String OPENING_BALANCE_EXISTS = "OPENING_BALANCE_EXISTS";
    /** The water column of a Plots row names a water source that two or more active common consumer meters share. */
    public static final String WATER_SOURCE_AMBIGUOUS = "WATER_SOURCE_AMBIGUOUS";

    private ProblemCodes() {
    }
}
