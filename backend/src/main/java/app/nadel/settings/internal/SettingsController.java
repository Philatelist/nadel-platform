package app.nadel.settings.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.CurrentUser;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import app.nadel.settings.CommonConsumerSplit;
import app.nadel.settings.LossesMode;
import app.nadel.settings.PlotStatusVisibility;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import nadel.contract.api.SettingsApi;
import nadel.contract.model.CooperativeSettings;
import nadel.contract.model.CooperativeSettingsUpdate;
import org.jspecify.annotations.Nullable;
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
    private final TenantDirectory tenants;

    SettingsController(CurrentUser currentUser, SettingsService service, TenantDirectory tenants) {
        this.currentUser = currentUser;
        this.service = service;
        this.tenants = tenants;
    }

    /** Every member of a cooperative may read the settings; the platform operator has no cooperative data. */
    @Override
    public ResponseEntity<CooperativeSettings> getSettings() {
        AccessProfile viewer = currentUser.profile();
        if (viewer.platformOperator() || viewer.tenantId() == null) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, ProblemCodes.PERMISSION_DENIED,
                    "The platform operator has no access to cooperative data.");
        }
        return ResponseEntity.ok(toDto(service.values(viewer), edrpou()));
    }

    /**
     * Only the fields present in the body change; see {@link SettingsService#update}. The ЄДРПОУ code is not part of
     * the update: only the platform operator changes it.
     */
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
                update.getRedDays(),
                update.getOfficialName(),
                update.getLegalAddress(),
                update.getBankName(),
                update.getIban(),
                update.getAutoAllocatePrepayment());
        return ResponseEntity.ok(toDto(service.update(change, currentUser.profile()), edrpou()));
    }

    /** The ЄДРПОУ code of the caller's own cooperative (RLS shows only that {@code tenant} row). */
    private @Nullable String edrpou() {
        return tenants.current().map(TenantSummary::edrpou).orElse(null);
    }

    private static CooperativeSettings toDto(SettingsValues values, @Nullable String edrpou) {
        var dto = new CooperativeSettings(
                nadel.contract.model.PlotStatusVisibility.fromValue(values.memberPlotStatusVisibility().name()),
                nadel.contract.model.LossesMode.fromValue(values.lossesMode().name()),
                values.lossesPercent().toPlainString(),
                nadel.contract.model.CommonConsumerSplit.fromValue(values.commonConsumerDefaultSplit().name()),
                values.redAmount().toPlainString(),
                values.redDays(),
                values.autoAllocatePrepayment());
        dto.setOfficialName(values.requisites().officialName());
        dto.setLegalAddress(values.requisites().legalAddress());
        dto.setBankName(values.requisites().bankName());
        dto.setIban(values.requisites().iban());
        dto.setEdrpou(edrpou);
        return dto;
    }
}
