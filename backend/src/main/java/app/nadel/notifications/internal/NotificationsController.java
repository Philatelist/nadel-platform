package app.nadel.notifications.internal;

import app.nadel.identity.CurrentUser;
import app.nadel.notifications.internal.DeliveryChecks.BotCheck;
import app.nadel.notifications.internal.mail.MailOutcome;
import app.nadel.notifications.internal.mail.SmtpSecurity;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.api.NotificationsApi;
import nadel.contract.model.AnnouncementChannel;
import nadel.contract.model.AnnouncementCounts;
import nadel.contract.model.AnnouncementDetails;
import nadel.contract.model.AnnouncementPage;
import nadel.contract.model.AnnouncementPaperMember;
import nadel.contract.model.AnnouncementRequest;
import nadel.contract.model.AnnouncementSendResult;
import nadel.contract.model.AnnouncementSummary;
import nadel.contract.model.DeliveryChannel;
import nadel.contract.model.DeliveryCounts;
import nadel.contract.model.DeliveryKind;
import nadel.contract.model.DeliveryMethod;
import nadel.contract.model.DeliveryNote;
import nadel.contract.model.DeliveryPage;
import nadel.contract.model.DeliveryRow;
import nadel.contract.model.DeliveryStatus;
import nadel.contract.model.DeliverySummaryCount;
import nadel.contract.model.InvoiceSendRequest;
import nadel.contract.model.InvoiceSendResult;
import nadel.contract.model.PrintDoneResult;
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
import org.jspecify.annotations.Nullable;
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
 *   <li>Sending invoices and the delivery screen: every one needs {@code INVOICES_ISSUE} ({@link DeliveryPlanner},
 *       {@link DeliveryRegister}).</li>
 *   <li>Announcements: every one needs {@code ANNOUNCEMENTS_SEND} ({@link Announcements}).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
class NotificationsController implements NotificationsApi {

    private final CurrentUser currentUser;
    private final DeliverySettingsService service;
    private final DeliveryChecks checks;
    private final MemberNotifications mine;
    private final DeliveryPlanner planner;
    private final DeliveryRegister register;
    private final Announcements announcements;

    NotificationsController(CurrentUser currentUser, DeliverySettingsService service, DeliveryChecks checks,
            MemberNotifications mine, DeliveryPlanner planner, DeliveryRegister register,
            Announcements announcements) {
        this.currentUser = currentUser;
        this.service = service;
        this.checks = checks;
        this.mine = mine;
        this.planner = planner;
        this.register = register;
        this.announcements = announcements;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_ANNOUNCEMENTS_SEND')")
    public ResponseEntity<AnnouncementSendResult> sendAnnouncement(Boolean preview, AnnouncementRequest request) {
        var plan = announcements.send(new Announcements.Request(request.getText(), request.getStreets(),
                request.getChannels().stream().map(channel -> Channel.valueOf(channel.getValue()))
                        .collect(Collectors.toSet())), Boolean.TRUE.equals(preview),
                currentUser.profile());
        var dto = new AnnouncementSendResult(new DeliveryCounts(plan.telegram(), plan.email(), plan.paper()),
                plan.paperMembers().stream().map(member -> new AnnouncementPaperMember(member.memberId(),
                        member.name())).toList());
        dto.setAnnouncementId(plan.announcementId());
        return ResponseEntity.ok(dto);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_ANNOUNCEMENTS_SEND')")
    public ResponseEntity<AnnouncementPage> listAnnouncements(Integer page, Integer size) {
        Announcements.Page found = announcements.list(page, size);
        List<AnnouncementSummary> items = found.items().stream().map(view -> {
            AnnouncementRows.Stored a = view.announcement();
            var dto = new AnnouncementSummary(a.id(), a.createdAt().atOffset(ZoneOffset.UTC), a.streets(),
                    channels(a.channels()), toDto(view.counts()));
            dto.setAuthorName(view.authorName());
            return dto;
        }).toList();
        int totalPages = (int) ((found.total() + size - 1) / size);
        return ResponseEntity.ok(new AnnouncementPage(items, page, size, found.total(), totalPages));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_ANNOUNCEMENTS_SEND')")
    public ResponseEntity<AnnouncementDetails> getAnnouncement(UUID id, Integer page, Integer size) {
        Announcements.View view = announcements.find(id);
        AnnouncementRows.Stored a = view.announcement();
        DeliveryPage deliveries = toDto(register.list(new DeliveryRows.Filter(null, DeliveryRows.ANNOUNCEMENT, null,
                null, a.id()), page, size), page, size);
        var dto = new AnnouncementDetails(a.id(), a.createdAt().atOffset(ZoneOffset.UTC),
                Objects.requireNonNull(a.text()), a.streets(), channels(a.channels()), toDto(view.counts()),
                deliveries);
        dto.setAuthorName(view.authorName());
        return ResponseEntity.ok(dto);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_INVOICES_ISSUE')")
    public ResponseEntity<InvoiceSendResult> sendMonthInvoices(String month, Boolean preview,
            @Nullable InvoiceSendRequest request) {
        boolean includeNothingDue = request != null && Boolean.TRUE.equals(request.getIncludeNothingDue());
        return ResponseEntity.ok(toDto(planner.sendMonth(YearMonth.parse(month), includeNothingDue,
                Boolean.TRUE.equals(preview), currentUser.profile())));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_INVOICES_ISSUE')")
    public ResponseEntity<InvoiceSendResult> sendInvoice(UUID id) {
        return ResponseEntity.ok(toDto(planner.sendInvoice(id, currentUser.profile())));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_INVOICES_ISSUE')")
    public ResponseEntity<DeliveryPage> listDeliveries(@Nullable String month, @Nullable DeliveryKind kind,
            @Nullable DeliveryStatus status, @Nullable DeliveryMethod channel, Integer page, Integer size) {
        var filter = new DeliveryRows.Filter(month == null ? null : YearMonth.parse(month),
                kind == null ? null : kind.getValue(), status == null ? null : status.getValue(),
                channel == null ? null : Channel.valueOf(channel.getValue()));
        return ResponseEntity.ok(toDto(register.list(filter, page, size), page, size));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_INVOICES_ISSUE')")
    public ResponseEntity<Void> retryDelivery(UUID id) {
        register.retry(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_INVOICES_ISSUE')")
    public ResponseEntity<PrintDoneResult> markMonthPrinted(String month) {
        return ResponseEntity.ok(new PrintDoneResult(register.printed(YearMonth.parse(month))));
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

    private static DeliveryPage toDto(DeliveryRegister.Page found, int page, int size) {
        List<DeliveryRow> items = found.rows().stream().map(NotificationsController::toDto).toList();
        List<DeliverySummaryCount> summary = found.summary().stream().map(count -> new DeliverySummaryCount(
                DeliveryMethod.fromValue(count.channel().name()), DeliveryStatus.fromValue(count.status()),
                count.count())).toList();
        int totalPages = (int) ((found.total() + size - 1) / size);
        return new DeliveryPage(items, page, size, found.total(), totalPages, summary);
    }

    private static AnnouncementCounts toDto(DeliveryRows.AnnouncementCounts counts) {
        return new AnnouncementCounts(counts.sent(), counts.failed(), counts.queued(), counts.paper());
    }

    private static List<AnnouncementChannel> channels(List<Channel> channels) {
        return channels.stream().map(channel -> AnnouncementChannel.fromValue(channel.name())).toList();
    }

    private static InvoiceSendResult toDto(DeliveryPlanner.Plan plan) {
        return new InvoiceSendResult(new DeliveryCounts(plan.telegram(), plan.email(), plan.paper()), plan.queued());
    }

    private static DeliveryRow toDto(DeliveryRegister.Row row) {
        DeliveryRows.Listed d = row.delivery();
        var dto = new DeliveryRow(d.id(), DeliveryKind.fromValue(d.kind()), DeliveryMethod.fromValue(d.channel().name()),
                DeliveryStatus.fromValue(d.status()), d.attempts());
        dto.setMonth(d.month() == null ? null : d.month().toString());
        dto.setPlotNumber(row.plotNumber());
        dto.setAnnouncementId(d.announcementId());
        dto.setMemberName(row.memberName());
        dto.setFailureCode(d.failureCode() == null ? null : DeliveryFailureCode.fromValue(d.failureCode()));
        dto.setNote(d.note() == null ? null : DeliveryNote.fromValue(d.note()));
        dto.setSentAt(d.sentAt() == null ? null : d.sentAt().atOffset(ZoneOffset.UTC));
        return dto;
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
