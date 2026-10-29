package app.nadel.notifications.internal;

import app.nadel.identity.CurrentUser;
import app.nadel.notifications.internal.DeliveryChecks.BotCheck;
import app.nadel.notifications.internal.mail.MailOutcome;
import app.nadel.notifications.internal.mail.SmtpSecurity;
import nadel.contract.api.NotificationsApi;
import nadel.contract.model.DeliveryEmailSettings;
import nadel.contract.model.DeliveryEmailSettingsUpdate;
import nadel.contract.model.DeliveryFailureCode;
import nadel.contract.model.DeliverySettings;
import nadel.contract.model.DeliverySettingsUpdate;
import nadel.contract.model.DeliveryTelegramSettings;
import nadel.contract.model.DeliveryTestEmailResult;
import nadel.contract.model.DeliveryTestTelegramResult;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The delivery settings endpoints of tag {@code notifications}. Every one needs {@code SETTINGS_MANAGE}; the platform
 * operator has no permissions, so it gets 403 like any member without it. The secrets are write-only: a response
 * never carries the password or the token, only {@code passwordSet} and {@code tokenLast4}.
 */
@RestController
@RequestMapping("/api/v1")
class DeliverySettingsController implements NotificationsApi {

    private final CurrentUser currentUser;
    private final DeliverySettingsService service;
    private final DeliveryChecks checks;

    DeliverySettingsController(CurrentUser currentUser, DeliverySettingsService service, DeliveryChecks checks) {
        this.currentUser = currentUser;
        this.service = service;
        this.checks = checks;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public ResponseEntity<DeliverySettings> getDeliverySettings() {
        return ResponseEntity.ok(toDto(service.values(currentUser.profile())));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public ResponseEntity<DeliverySettings> updateDeliverySettings(DeliverySettingsUpdate update) {
        DeliveryEmailSettingsUpdate email = update.getEmail();
        var change = new DeliverySettingsService.Change(
                email == null ? null : new DeliverySettingsService.EmailChange(email.getHost(), email.getPort(),
                        email.getSecurity() == null ? null : SmtpSecurity.valueOf(email.getSecurity().getValue()),
                        email.getUser(), email.getPassword(), email.getFromAddress(), email.getFromName()),
                update.getTelegram() == null ? null : update.getTelegram().getBotToken(),
                update.getEmailRatePerSecond(),
                update.getNotifyPayments());
        return ResponseEntity.ok(toDto(service.update(change, currentUser.profile())));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public ResponseEntity<DeliveryTestEmailResult> testDeliveryEmail() {
        MailOutcome outcome = checks.testEmail(currentUser.profile());
        var dto = new DeliveryTestEmailResult(outcome.accepted());
        dto.setServerReply(outcome.serverReply());
        dto.setFailureCode(outcome.failure() == null ? null : DeliveryFailureCode.fromValue(outcome.failure().name()));
        return ResponseEntity.ok(dto);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public ResponseEntity<DeliveryTestTelegramResult> testDeliveryTelegram() {
        BotCheck check = checks.testTelegram(currentUser.profile());
        var dto = new DeliveryTestTelegramResult(check.problem() == null);
        dto.setBotUsername(check.botUsername());
        dto.setCode(check.problem() == null ? null
                : DeliveryTestTelegramResult.CodeEnum.fromValue(check.problem().name()));
        return ResponseEntity.ok(dto);
    }

    private static DeliverySettings toDto(DeliverySettingsValues values) {
        var email = new DeliveryEmailSettings(values.passwordSet(), values.emailConfigured());
        email.setHost(values.host());
        email.setPort(values.port());
        email.setSecurity(values.security() == null ? null
                : nadel.contract.model.SmtpSecurity.fromValue(values.security().name()));
        email.setUser(values.user());
        email.setFromAddress(values.fromAddress());
        email.setFromName(values.fromName());
        var telegram = new DeliveryTelegramSettings(values.botConfigured());
        telegram.setTokenLast4(values.tokenLast4());
        telegram.setBotUsername(values.botUsername());
        return new DeliverySettings(email, telegram, values.emailRatePerSecond(), values.notifyPayments());
    }
}
