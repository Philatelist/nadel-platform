package app.nadel.identity;

/**
 * Platform-level role. In Spring Security it is the authority {@code ROLE_PLATFORM_OPERATOR}. Cooperative roles
 * (Chairman, Treasurer, ...) are data in the database and are never checked by name: code checks
 * {@link Permission} authorities (ADR 0009).
 */
public final class Roles {

    public static final String PLATFORM_OPERATOR = "PLATFORM_OPERATOR";

    private Roles() {
    }
}
