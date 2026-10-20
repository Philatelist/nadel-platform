package app.nadel.plots;

import java.util.Map;

/**
 * One problem of an ownership row.
 *
 * @param field  {@code plotNumber}, {@code memberId}, {@code share} or {@code validFrom}
 * @param code   stable code, see {@code uploadImport} in the API contract
 * @param params values for the message; empty for most codes
 */
public record OwnershipImportProblem(String field, String code, Map<String, String> params) {

    public OwnershipImportProblem {
        params = Map.copyOf(params);
    }

    public OwnershipImportProblem(String field, String code) {
        this(field, code, Map.of());
    }
}
