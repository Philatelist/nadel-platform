package app.nadel.notifications.internal;

import app.nadel.identity.CurrentUser;
import app.nadel.notifications.internal.DeliveryChecks.BotCheck;
import app.nadel.notifications.internal.mail.MailOutcome;
import app.nadel.notifications.internal.mail.SmtpSecurity;
import java.time.ZoneOffset;
import nadel.contract.api.NotificationsApi;
import nadel.contract.model.DeliveryChannel;
import nadel.contract.model.DeliveryEmailSettings;
import nadel.contract.model.DeliveryEmailSettingsUpdate;
import nadel.contract.model.DeliveryFailureCode;
import nadel.contract.model.DeliverySettings;
import nadel.contract.model.DeliverySettingsUpdate;
import nadel.contract.model.DeliveryTelegramSettings;
import nadel.contract.model.DeliveryTestEmailResult;
import nadel.contract.model.DeliveryTestTelegramResult;
import nadel.contract.model.MyNotifications;
import nadel.contract.model.MyNotificationsUpdate;
import nadel.contract.model.TelegramLinkOffer;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code notifications} (one controller per generated interface).
 * <ul>
 *   <li>The delivery settings: every one needs {@code SETTINGS_MANAGE}; the platform operator has no permissions, so
 *       it gets 403 like any member without it. The secrets are write-only: a response never carries the password or
 *       the token, only {@code passwordSet} and {@code tokenLast4}.</li>
 *   <li>The member's own «Сповіщення» ({@code /me/notifications}, {@code /me/telegram-link}): no permission, always
 *       the member behind the token ({@link MemberNotifications}); the platform operator gets 403.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
class NotificationsController implements NotificationsApi {

    private final CurrentUser currentUser;
    private final DeliverySettingsService service;
    private final DeliveryChecks checks;
    private final MemberNotifications mine;

    NotificationsController(CurrentUser currentUser, DeliverySettingsService service, DeliveryChecks checks,
            MemberNotifications mine) {
        this.currentUser = currentUser;
        this.service = service;
        this.checks = checks;
        this.mine = mine;
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

    @Override
    public ResponseEntity<MyNotifications> getMyNotifications() {
        return ResponseEntity.ok(toDto(mine.view(currentUser.profile())));
    }

    @Override
    public ResponseEntity<MyNotifications> updateMyNotifications(MyNotificationsUpdate update) {
        return ResponseEntity.ok(toDto(mine.choose(currentUser.profile(),
                app.nadel.members.DeliveryChannel.valueOf(update.getChannel().getValue()))));
    }

    @Override
    public ResponseEntity<TelegramLinkOffer> createMyTelegramLink() {
        MemberNotifications.LinkOffer offer = mine.offerLink(currentUser.profile());
        return ResponseEntity.ok(new TelegramLinkOffer(offer.deepLink(), offer.expiresAt().atOffset(ZoneOffset.UTC)));
    }

    @Override
    public ResponseEntity<Void> deleteMyTelegramLink() {
        mine.unlink(currentUser.profile());
        return ResponseEntity.noContent().build();
    }

    private static MyNotifications toDto(MemberNotifications.View view) {
        return new MyNotifications(channel(view.channel()),
                view.available().stream().map(NotificationsController::channel).toList(), view.telegramLinked(),
                view.botAvailable());
    }

    private static DeliveryChannel channel(app.nadel.members.DeliveryChannel channel) {
        return DeliveryChannel.fromValue(channel.name());
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
