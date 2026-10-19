package app.nadel.settings.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.CurrentUser;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import app.nadel.settings.PlotStatusVisibility;
import nadel.contract.api.SettingsApi;
import nadel.contract.model.CooperativeSettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class SettingsController implements SettingsApi {

    private final CurrentUser currentUser;
    private final SettingsService service;

    SettingsController(CurrentUser currentUser, SettingsService service) {
        this.currentUser = currentUser;
        this.service = service;
    }

    /** Every member of a cooperative may read the settings; the platform operator has no cooperative data. */
    @Override
    public ResponseEntity<CooperativeSettings> getSettings() {
        AccessProfile viewer = currentUser.profile();
        if (viewer.platformOperator() || viewer.tenantId() == null) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, ProblemCodes.PERMISSION_DENIED,
                    "The platform operator has no access to cooperative data.");
        }
        return ResponseEntity.ok(toDto(service.visibility(viewer)));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public ResponseEntity<CooperativeSettings> updateSettings(CooperativeSettings cooperativeSettings) {
        PlotStatusVisibility requested = PlotStatusVisibility.valueOf(
                cooperativeSettings.getMemberPlotStatusVisibility().getValue());
        return ResponseEntity.ok(toDto(service.update(requested, currentUser.profile())));
    }

    private static CooperativeSettings toDto(PlotStatusVisibility visibility) {
        return new CooperativeSettings(nadel.contract.model.PlotStatusVisibility.fromValue(visibility.name()));
    }
}
