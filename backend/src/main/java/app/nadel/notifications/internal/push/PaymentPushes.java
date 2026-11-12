package app.nadel.notifications.internal.push;

import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.PaymentRecorded;
import app.nadel.ledger.PaymentRow;
import app.nadel.notifications.internal.DeliverySettingsService;
import app.nadel.notifications.internal.push.PushFanOut.Push;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * «Платіж зараховано» (feature 021 §2.2): when payments are recorded ({@link PaymentRecorded}) and the cooperative sends
 * payment notices ({@code notify_payments}, {@link DeliverySettingsService#notifyPayments}), the member who paid gets
 * one push {@code PAYMENT_RECORDED} on every device: each payment's payer ({@link PaymentRow#payerMemberId} through
 * {@link LedgerReadModel#paymentsByIds}), one push per payer however many of the event's payments name them (a split
 * over several plots), with the payer's first payment id as the reference. A payment that names no payer pushes to
 * nobody: the plot's owners are not told by push. The ADR 0007 listener pattern through {@link PushFanOut}; a
 * redelivered event queues nothing new.
 */
@Component
class PaymentPushes {

    private final LedgerReadModel ledger;
    private final DeliverySettingsService settings;
    private final PushFanOut fanOut;

    PaymentPushes(LedgerReadModel ledger, DeliverySettingsService settings, PushFanOut fanOut) {
        this.ledger = ledger;
        this.settings = settings;
        this.fanOut = fanOut;
    }

    @Async
    @TransactionalEventListener
    void on(PaymentRecorded event) {
        queue(event);
    }

    /** Queues the pushes of the event; how many jobs were handed to JobRunr. */
    int queue(PaymentRecorded event) {
        return fanOut.send(event.tenantId(), () -> {
            if (!settings.notifyPayments()) {
                return List.of();
            }
            Map<UUID, UUID> firstPayment = new TreeMap<>();
            for (PaymentRow payment : ledger.paymentsByIds(event.paymentIds())) {
                if (payment.payerMemberId() != null) {
                    firstPayment.merge(payment.payerMemberId(), payment.paymentId(),
                            (one, other) -> one.toString().compareTo(other.toString()) <= 0 ? one : other);
                }
            }
            return firstPayment.entrySet().stream().map(entry -> new Push(entry.getKey(), PushCode.PAYMENT_RECORDED,
                    entry.getValue().toString(), null)).toList();
        });
    }
}
