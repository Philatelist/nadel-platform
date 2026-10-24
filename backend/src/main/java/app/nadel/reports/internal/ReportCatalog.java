package app.nadel.reports.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.problem.ApiProblemException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportInfo;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The built-in reports in the fixed order of {@link ReportCode}, and which of them a caller may run: a report is
 * available when the caller holds every one of its {@link ReportDefinition#requiredPermissions()}. A code of the
 * contract without a definition yet is 404, a report the caller may not run 403.
 */
@Component
class ReportCatalog {

    private final List<ReportDefinition> definitions;

    ReportCatalog(List<ReportDefinition> definitions) {
        this.definitions = definitions.stream().sorted(Comparator.comparing(ReportDefinition::code)).toList();
        if (this.definitions.stream().map(ReportDefinition::code).distinct().count() != this.definitions.size()) {
            throw new IllegalStateException("Two report definitions have the same code");
        }
    }

    /** The reports the caller may run, in order. */
    List<ReportDefinition> available(AccessProfile caller) {
        return definitions.stream().filter(definition -> mayRun(caller, definition)).toList();
    }

    /** The reports the caller may run, with their filters and the defaults of {@code today}. */
    List<ReportInfo> infos(AccessProfile caller, LocalDate today) {
        return available(caller).stream().map(definition -> new ReportInfo(definition.code(),
                definition.personalData(), definition.filters().stream().map(filter -> filter.info(today)).toList()))
                .toList();
    }

    /**
     * The report the caller is about to run.
     *
     * @throws ApiProblemException   404 {@code NOT_FOUND} when the code has no definition yet
     * @throws AccessDeniedException (403 {@code PERMISSION_DENIED}) when the caller lacks a permission it needs
     */
    ReportDefinition require(ReportCode code, AccessProfile caller) {
        ReportDefinition definition = definitions.stream().filter(candidate -> candidate.code() == code).findFirst()
                .orElseThrow(ApiProblemException::notFound);
        if (!mayRun(caller, definition)) {
            throw new AccessDeniedException("The report needs a permission the caller lacks");
        }
        return definition;
    }

    private static boolean mayRun(AccessProfile caller, ReportDefinition definition) {
        return definition.requiredPermissions().stream().allMatch(caller::can);
    }
}
