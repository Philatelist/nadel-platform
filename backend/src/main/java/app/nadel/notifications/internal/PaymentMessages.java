package app.nadel.notifications.internal;

import app.nadel.documents.DocumentFormats;
import app.nadel.documents.DocumentsFacade;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.PaymentRecorded;
import app.nadel.ledger.PaymentReversed;
import app.nadel.ledger.PaymentRow;
import app.nadel.ledger.PaymentSource;
import app.nadel.ledger.PlotDebts;
import app.nadel.notifications.internal.ChannelResolver.Resolution;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.plots.PlotOwners;
import app.nadel.tenancy.TenantContext;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment and reversal messages (feature 017 §2.4): planned when the ledger publishes {@link PaymentRecorded} or
 * {@link PaymentReversed}, written when the dispatcher sends them.
 * <p>
 * <b>Planning.</b> The ADR 0007 listener pattern: {@code @Async @TransactionalEventListener} without
 * {@code @Transactional}, the event's cooperative bound ({@link TenantContext#runAs}) before the transaction opens.
 * Nothing while the cooperative's «Повідомляти про платежі» ({@code notify_payments}) is off. Otherwise, for each
 * payment of the event ({@link LedgerReadModel#paymentsByIds}; a payment is always of one plot, so a split over
 * several plots gives one message per plot), every current owner of its plot ({@link PlotOwners}) whose channel comes
 * out as e-mail or Telegram ({@link RecipientChannels}) gets one queued delivery of kind {@code PAYMENT} or
 * {@code REVERSAL}; paper owners get nothing. A delivery is created once per (payment, kind, member, channel) — {@code INSERT … ON CONFLICT DO
 * NOTHING} on the table's unique key — so a redelivered event creates nothing and sends nothing again. The lanes with
 * new rows are woken after the commit ({@link DeliveryQueue}).
 * <p>
 * <b>The message</b> ({@link #of}, at send time, never stored): «Отримано 500,00 ₴ по ділянці 9 (28.10.2026). Борг:
 * 1 234,56 ₴», with «Аванс: …» instead when the plot owes nothing and has a prepayment, or «Платіж від 28.10.2026 на
 * 500,00 ₴ сторновано. Борг: …». The debt and prepayment are the plot's as of today in Kyiv
 * ({@link LedgerReadModel#debtsAsOf}, every charge type), so the message says what the account says when it goes. A
 * cash payment carries its receipt ({@link DocumentsFacade#receiptPdf}). Texts from {@code messages_uk.properties}.
 * Logs carry ids and counts only.
 */
@Component
class PaymentMessages {

    static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    private static final Logger log = LoggerFactory.getLogger(PaymentMessages.class);
    private static final Set<Channel> SENT_CHANNELS = EnumSet.of(Channel.EMAIL, Channel.TELEGRAM);

    private final LedgerReadModel ledger;
    private final DocumentsFacade documents;
    private final PlotCatalog plots;
    private final PlotOwners owners;
    private final RecipientChannels recipients;
    private final DeliverySettingsService settings;
    private final DeliveryRows rows;
    private final DeliveryQueue queue;
    private final TenantDirectory tenants;
    private final Clock clock;
    private final TransactionTemplate transaction;

    PaymentMessages(LedgerReadModel ledger, DocumentsFacade documents, PlotCatalog plots, PlotOwners owners,
            RecipientChannels recipients, DeliverySettingsService settings, DeliveryRows rows, DeliveryQueue queue,
            TenantDirectory tenants, Clock clock, PlatformTransactionManager transactionManager) {
        this.ledger = ledger;
        this.documents = documents;
        this.plots = plots;
        this.owners = owners;
        this.recipients = recipients;
        this.settings = settings;
        this.rows = rows;
        this.queue = queue;
        this.tenants = tenants;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Async
    @TransactionalEventListener
    void on(PaymentRecorded event) {
        plan(event.tenantId(), event.paymentIds(), DeliveryRows.PAYMENT);
    }

    @Async
    @TransactionalEventListener
    void on(PaymentReversed event) {
        plan(event.tenantId(), event.paymentIds(), DeliveryRows.REVERSAL);
    }

    /** Plans the messages of the payments in a transaction of the cooperative; how many deliveries were created. */
    int plan(UUID tenantId, List<UUID> paymentIds, String kind) {
        Integer created = TenantContext.runAs(tenantId, () -> transaction.execute(status ->
                planRows(tenantId, paymentIds, kind)));
        log.debug("Payment messages ({}) of cooperative {}: {} payments, {} deliveries created", kind, tenantId,
                paymentIds.size(), created);
        return created == null ? 0 : created;
    }

    private int planRows(UUID tenantId, List<UUID> paymentIds, String kind) {
        DeliverySettingsValues values = settings.current();
        if (!values.notifyPayments()) {
            return 0;
        }
        List<PaymentRow> payments = ledger.paymentsByIds(paymentIds);
        if (payments.isEmpty()) {
            return 0;
        }
        Map<UUID, List<UUID>> plotOwners = owners.currentOwnerIds(payments.stream().map(PaymentRow::plotId)
                .distinct().toList());
        Map<UUID, Resolution> channels = recipients.resolve(plotOwners.values().stream().flatMap(List::stream)
                .distinct().toList(), values);
        Instant now = clock.instant();
        Set<Channel> woken = EnumSet.noneOf(Channel.class);
        int created = 0;
        for (PaymentRow payment : payments) {
            for (UUID member : plotOwners.getOrDefault(payment.plotId(), List.of()).stream().distinct().toList()) {
                Resolution resolution = channels.get(member);
                if (resolution == null || !SENT_CHANNELS.contains(resolution.channel())) {
                    continue;
                }
                if (rows.insertPayment(tenantId, kind, payment.paymentId(), payment.billingMonth(), member,
                        resolution.channel(), now)) {
                    created++;
                    woken.add(resolution.channel());
                }
            }
        }
        woken.forEach(channel -> queue.wakeAfterCommit(tenantId, channel));
        return created;
    }

    /**
     * The message of a payment ({@code PAYMENT}) or reversal ({@code REVERSAL}) delivery, in the caller's transaction
     * of the bound cooperative; empty when the payment or its plot does not exist.
     */
    Optional<DeliveryMessage> of(UUID paymentId, String kind) {
        Optional<PaymentRow> found = ledger.paymentsByIds(List.of(paymentId)).stream().findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PaymentRow payment = found.get();
        Optional<PlotFacts> plot = plots.find(payment.plotId());
        if (plot.isEmpty()) {
            return Optional.empty();
        }
        NotificationTexts texts = NotificationTexts.uk();
        LocalDate today = LocalDate.ofInstant(clock.instant(), KYIV);
        PlotDebts debts = ledger.debtsAsOf(today, Set.of(), List.of(payment.plotId())).get(payment.plotId());
        String balance = debts.total().signum() <= 0 && debts.prepayment().signum() > 0
                ? texts.text("payment.balance.prepayment", Map.of("amount", DocumentFormats.money(debts.prepayment())))
                : texts.text("payment.balance.debt", Map.of("amount", DocumentFormats.money(debts.total())));
        String number = plot.get().number();
        Map<String, String> params = Map.of("amount", DocumentFormats.money(payment.amount()), "plot", number, "day",
                DocumentFormats.day(payment.receivedOn()), "balance", balance);
        String sender = tenants.current().map(TenantSummary::name).orElse("");
        if (DeliveryRows.REVERSAL.equals(kind)) {
            return Optional.of(new DeliveryMessage(texts.text("payment.email.subject.reversed", params),
                    texts.text("payment.reversed", params), null, null, null, null, sender));
        }
        byte[] receipt = payment.source() == PaymentSource.CASH
                ? documents.receiptPdf(paymentId).orElse(null)
                : null;
        String fileName = receipt == null ? null
                : "receipt_" + (payment.receiptNumber() == null ? paymentId : payment.receiptNumber()) + ".pdf";
        return Optional.of(new DeliveryMessage(texts.text("payment.email.subject.received", params),
                texts.text("payment.received", params), null, fileName, receipt,
                receipt == null ? null : texts.text("payment.email.attached"), sender));
    }
}
