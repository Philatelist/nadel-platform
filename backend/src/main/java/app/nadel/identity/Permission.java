package app.nadel.identity;

/**
 * Fixed list of permissions a role can contain (ADR 0009). Spring Security authority: {@code PERM_<name>}.
 * The list matches the {@code Permission} enum of {@code api/openapi.yaml}.
 */
public enum Permission {
    MEMBERS_VIEW,
    MEMBERS_EDIT,
    ROLES_MANAGE,
    AUDIT_VIEW,
    PLOTS_VIEW,
    PLOTS_EDIT,
    OWNERSHIP_EDIT,
    IMPORT_RUN,
    SETTINGS_MANAGE,
    TARIFFS_VIEW,
    TARIFFS_MANAGE,
    METERS_VIEW,
    METERS_MANAGE,
    READINGS_ENTER,
    READINGS_APPROVE,
    CHARGES_VIEW,
    CHARGES_RUN,
    CHARGES_REVERSE,
    PERIODS_CLOSE;

    public static final String AUTHORITY_PREFIX = "PERM_";

    public String authority() {
        return AUTHORITY_PREFIX + name();
    }
}
