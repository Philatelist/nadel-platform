package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import app.nadel.problem.ApiProblemException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportInfo;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

/**
 * The catalog of reports: the fixed order of {@link ReportCode}, a report that needs a permission the caller lacks
 * (the debtors' {@code MEMBERS_VIEW}) is neither listed nor run (403), a code without a definition is 404.
 */
class ReportCatalogTests {

    private final ReportCatalog catalog = new ReportCatalog(List.of(
            new FakeReport(ReportCode.DEBTORS, Set.of(Permission.REPORTS_VIEW, Permission.MEMBERS_VIEW), 1),
            new FakeReport(ReportCode.CHARGES_BY_MONTH, Set.of(Permission.REPORTS_VIEW), 1)));

    @Test
    void aReportNeedingAnotherPermissionIsNotListedWithoutIt() {
        assertThat(catalog.available(caller(Permission.REPORTS_VIEW))).extracting(ReportDefinition::code)
                .containsExactly(ReportCode.CHARGES_BY_MONTH);
        assertThat(catalog.available(caller(Permission.REPORTS_VIEW, Permission.MEMBERS_VIEW)))
                .extracting(ReportDefinition::code).containsExactly(ReportCode.CHARGES_BY_MONTH, ReportCode.DEBTORS);
        assertThat(catalog.available(caller(Permission.MEMBERS_VIEW))).isEmpty();
    }

    @Test
    void theInfosCarryTheFiltersAndThePersonalDataFlag() {
        List<ReportInfo> infos = catalog.infos(caller(Permission.REPORTS_VIEW, Permission.MEMBERS_VIEW),
                LocalDate.of(2026, 1, 15));

        assertThat(infos).extracting(ReportInfo::getPersonalData).containsExactly(false, true);
        assertThat(infos.getFirst().getFilters().getFirst().getDefaults().getFirst().getValue()).isEqualTo("2025-12");
    }

    @Test
    void runningAReportWithoutItsPermissionIsForbiddenAndAnUndefinedOneNotFound() {
        assertThat(catalog.require(ReportCode.CHARGES_BY_MONTH, caller(Permission.REPORTS_VIEW)).code())
                .isEqualTo(ReportCode.CHARGES_BY_MONTH);
        assertThatThrownBy(() -> catalog.require(ReportCode.DEBTORS, caller(Permission.REPORTS_VIEW)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> catalog.require(ReportCode.LAND_TAX, caller(Permission.REPORTS_VIEW)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void twoDefinitionsOfOneCodeAreAProgrammingError() {
        assertThatThrownBy(() -> new ReportCatalog(List.of(
                new FakeReport(ReportCode.WATER, Set.of(Permission.REPORTS_VIEW), 1),
                new FakeReport(ReportCode.WATER, Set.of(Permission.REPORTS_VIEW), 1))))
                .isInstanceOf(IllegalStateException.class);
    }

    static AccessProfile caller(Permission... permissions) {
        return new AccessProfile(UUID.randomUUID(), UUID.randomUUID(), "sub", "Петренко Іван", Set.of(permissions),
                List.of(), false);
    }
}
