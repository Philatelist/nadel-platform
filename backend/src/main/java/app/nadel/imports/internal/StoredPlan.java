package app.nadel.imports.internal;

import app.nadel.members.MemberImportRow;
import app.nadel.meters.MeterImportRow;
import app.nadel.plots.PlotImportRow;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * Reads the rows of a preview back from {@code import_run.plan} (written by {@code ImportPlanner}), so the commit can
 * plan them again. The stored rows were free of problems, so the parsed sheets come back without any. Personal data:
 * never log it.
 */
final class StoredPlan {

    private static final int VERSION = 1;

    private StoredPlan() {
    }

    /** The four sheets of a stored plan; a plan stored before the Meters sheet existed has no meters. */
    record Sheets(PlotsSheet.Parsed plots, MembersSheet.Parsed members, OwnershipSheet.Parsed ownership,
            MetersSheet.Parsed meters) {
    }

    /**
     * @throws ApiProblemException 409 {@code IMPORT_STALE} when the plan has a version this release does not know (a
     *                             preview stored by another release: the file has to be uploaded again)
     */
    @SuppressWarnings("unchecked")
    static Sheets read(Map<String, Object> plan) {
        if (!Integer.valueOf(VERSION).equals(plan.get("version"))) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.IMPORT_STALE,
                    "The stored preview cannot be read any more; upload the file again.");
        }
        return new Sheets(plots((List<Map<String, Object>>) plan.get("plots")),
                members((List<Map<String, Object>>) plan.get("members")),
                ownership((List<Map<String, Object>>) plan.get("ownership")),
                meters((List<Map<String, Object>>) plan.get("meters")));
    }

    private static PlotsSheet.Parsed plots(@Nullable List<Map<String, Object>> stored) {
        List<PlotsSheet.ParsedRow> rows = new ArrayList<>();
        for (Map<String, Object> map : orEmpty(stored)) {
            BigDecimal area = text(map, "areaM2") == null ? null : new BigDecimal(text(map, "areaM2"));
            // the column only names the cell of a problem, and a stored row has none
            rows.add(new PlotsSheet.ParsedRow(new PlotImportRow(number(map, "row"), text(map, "number"),
                    text(map, "street"), text(map, "kind"), area, text(map, "cadastralNumber"),
                    (Boolean) map.get("hasBuilding"), text(map, "notes")), PlotColumn.AREA_M2));
        }
        return new PlotsSheet.Parsed(rows, List.of());
    }

    @SuppressWarnings("unchecked")
    private static MembersSheet.Parsed members(@Nullable List<Map<String, Object>> stored) {
        List<MemberImportRow> rows = new ArrayList<>();
        for (Map<String, Object> map : orEmpty(stored)) {
            List<String> phones = map.get("phones") == null ? List.of() : (List<String>) map.get("phones");
            rows.add(new MemberImportRow(number(map, "row"), text(map, "externalId"), text(map, "lastName"),
                    text(map, "firstName"), text(map, "middleName"), phones, text(map, "email"),
                    text(map, "postalAddress"), text(map, "deliveryChannel"), text(map, "telegramUsername"),
                    text(map, "viberPhone")));
        }
        return new MembersSheet.Parsed(rows, List.of());
    }

    private static OwnershipSheet.Parsed ownership(@Nullable List<Map<String, Object>> stored) {
        List<OwnershipSheet.ParsedRow> rows = new ArrayList<>();
        for (Map<String, Object> map : orEmpty(stored)) {
            String date = text(map, "validFrom");
            rows.add(new OwnershipSheet.ParsedRow(number(map, "row"), text(map, "plotNumber"),
                    text(map, "memberKey"), text(map, "share"), date == null ? null : LocalDate.parse(date)));
        }
        return new OwnershipSheet.Parsed(rows, List.of());
    }

    private static MetersSheet.Parsed meters(@Nullable List<Map<String, Object>> stored) {
        List<MeterImportRow> rows = new ArrayList<>();
        for (Map<String, Object> map : orEmpty(stored)) {
            String date = text(map, "installedOn");
            String reading = text(map, "initialReading");
            rows.add(new MeterImportRow(number(map, "row"), text(map, "plotNumber"), text(map, "resource"),
                    text(map, "serial"), date == null ? null : LocalDate.parse(date),
                    reading == null ? null : new BigDecimal(reading)));
        }
        return new MetersSheet.Parsed(rows, List.of());
    }

    private static List<Map<String, Object>> orEmpty(@Nullable List<Map<String, Object>> stored) {
        return stored == null ? List.of() : stored;
    }

    private static @Nullable String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static int number(Map<String, Object> map, String key) {
        return ((Number) map.get(key)).intValue();
    }
}
