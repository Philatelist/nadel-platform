package app.nadel.identity;

import java.util.Set;

/** Platform roles. In Spring Security they are authorities with the ROLE_ prefix. */
public final class Roles {

    public static final String CHAIRMAN = "CHAIRMAN";
    public static final String TREASURER = "TREASURER";
    public static final String ELECTRICIAN = "ELECTRICIAN";
    public static final String MEMBER = "MEMBER";
    public static final String PLATFORM_OPERATOR = "PLATFORM_OPERATOR";

    public static final Set<String> ALL = Set.of(CHAIRMAN, TREASURER, ELECTRICIAN, MEMBER, PLATFORM_OPERATOR);

    private Roles() {
    }
}
