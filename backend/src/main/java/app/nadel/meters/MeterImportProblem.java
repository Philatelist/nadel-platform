package app.nadel.meters;

import java.util.Map;

/**
 * One problem of a meter row.
 *
 * @param field  {@code plotNumber}, {@code resource}, {@code serial}, {@code installedOn} or {@code initialReading}
 * @param code   stable code, see {@code uploadImport} in the API contract
 * @param params values for the message ({@code plot} for {@code METER_ACTIVE_EXISTS}); empty for most codes
 */
public record MeterImportProblem(String field, String code, Map<String, String> params) {

    public MeterImportProblem {
        params = Map.copyOf(params);
    }

    public MeterImportProblem(String field, String code) {
        this(field, code, Map.of());
    }
}
