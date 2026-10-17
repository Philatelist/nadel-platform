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
    IMPORT_RUN;

    public static final String AUTHORITY_PREFIX = "PERM_";

    public String authority() {
        return AUTHORITY_PREFIX + name();
    }
}
