package app.nadel.settings.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.CurrentUser;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import app.nadel.settings.CommonConsumerSplit;
import app.nadel.settings.LossesMode;
import app.nadel.settings.PlotStatusVisibility;
import nadel.contract.api.SettingsApi;
import nadel.contract.model.CooperativeSettings;
import nadel.contract.model.CooperativeSettingsUpdate;
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
        return ResponseEntity.ok(toDto(service.values(viewer)));
    }

    /** Only the fields present in the body change; see {@link SettingsService#update}. */
    @Override
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public ResponseEntity<CooperativeSettings> updateSettings(CooperativeSettingsUpdate update) {
        var change = new SettingsService.Change(
                update.getMemberPlotStatusVisibility() == null ? null
                        : PlotStatusVisibility.valueOf(update.getMemberPlotStatusVisibility().getValue()),
                update.getLossesMode() == null ? null : LossesMode.valueOf(update.getLossesMode().getValue()),
                update.getLossesPercent(),
                update.getCommonConsumerDefaultSplit() == null ? null
                        : CommonConsumerSplit.valueOf(update.getCommonConsumerDefaultSplit().getValue()),
                update.getRedAmount(),
                update.getRedDays());
        return ResponseEntity.ok(toDto(service.update(change, currentUser.profile())));
    }

    private static CooperativeSettings toDto(SettingsValues values) {
        return new CooperativeSettings(
                nadel.contract.model.PlotStatusVisibility.fromValue(values.memberPlotStatusVisibility().name()),
                nadel.contract.model.LossesMode.fromValue(values.lossesMode().name()),
                values.lossesPercent().toPlainString(),
                nadel.contract.model.CommonConsumerSplit.fromValue(values.commonConsumerDefaultSplit().name()),
                values.redAmount().toPlainString(),
                values.redDays());
    }
}
