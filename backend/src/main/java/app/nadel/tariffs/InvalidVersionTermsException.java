package app.nadel.tariffs;

import java.util.List;

/** The terms of a tariff version break the one-off rule; says which parts do. */
public final class InvalidVersionTermsException extends IllegalArgumentException {

    private final transient List<VersionTerms.Violation> violations;

    InvalidVersionTermsException(List<VersionTerms.Violation> violations) {
        super("The version terms are not valid: " + violations);
        this.violations = List.copyOf(violations);
    }

    public List<VersionTerms.Violation> violations() {
        return violations;
    }
}
