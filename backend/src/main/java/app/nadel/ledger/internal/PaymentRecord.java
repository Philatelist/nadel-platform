package app.nadel.ledger.internal;

import app.nadel.ledger.PaymentSource;
import app.nadel.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * One row of table {@code payment} (V27, feature 015 §2.1): money received for one plot, or the reversal of such a
 * payment. Append-only like {@link ChargeRecord}: the application role has no {@code UPDATE} or {@code DELETE} on the
 * table and a trigger refuses both to everyone else, so the entity is {@link Immutable} and only ever inserted. A
 * mistake is cancelled by a {@link PaymentKind#PAYMENT_REVERSAL} row; moving the money between charges is a release
 * plus new {@link AllocationRecord}s.
 * <p>
 * {@code payerNoteEnc} is the payer note sealed by the {@code privacy} cipher with the field reference
 * {@code ("payment", "payer_note", id)}; the record never holds the plain note. Because the seal is bound to the row
 * id, the caller chooses the id first ({@link #payment}).
 */
@Entity
@Immutable
@Table(name = "payment")
class PaymentRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "plot_id", nullable = false, updatable = false)
    private UUID plotId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentSource source;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "received_on", nullable = false, updatable = false)
    private LocalDate receivedOn;

    @Column(name = "billing_month_id", nullable = false, updatable = false)
    private UUID billingMonthId;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @Column(name = "receipt_number", updatable = false)
    private Long receiptNumber;

    @Column(name = "bank_reference", updatable = false)
    private String bankReference;

    @Column(name = "payer_member_id", updatable = false)
    private UUID payerMemberId;

    @Column(name = "payer_note_enc", updatable = false)
    private byte[] payerNoteEnc;

    @Column(name = "reverses_payment_id", updatable = false)
    private UUID reversesPaymentId;

    @Column(updatable = false)
    private String reason;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentRecord() {
    }

    /**
     * A payment (kind {@code PAYMENT}) of {@code amount} received on {@code receivedOn} for the plot, booked in
     * {@code billingMonthId}. {@code groupId} is shared by the parts of one payment split over several plots (a single
     * payment is its own group: pass its id). A cash payment carries the group's receipt number, a bank payment none
     * and optionally the bank document number. {@code payerNoteEnc} is the sealed payer note, null when there is none.
     *
     * @throws IllegalArgumentException when the amount is not above zero, a cash payment has no receipt number (or one
     *                                  below 1), a bank payment has one, or a cash payment has a bank reference
     */
    static PaymentRecord payment(UUID id, UUID tenantId, UUID plotId, PaymentSource source, Money amount,
            LocalDate receivedOn, UUID billingMonthId, UUID groupId, @Nullable Long receiptNumber,
            @Nullable String bankReference, @Nullable UUID payerMemberId, byte @Nullable [] payerNoteEnc,
            @Nullable UUID createdBy, Instant now) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("A payment is above zero");
        }
        if ((source == PaymentSource.CASH) != (receiptNumber != null)) {
            throw new IllegalArgumentException("Exactly a cash payment carries a receipt number");
        }
        if (receiptNumber != null && receiptNumber < 1) {
            throw new IllegalArgumentException("A receipt number starts at 1");
        }
        if (bankReference != null && source != PaymentSource.BANK) {
            throw new IllegalArgumentException("Only a bank payment carries a bank reference");
        }
        PaymentRecord payment = new PaymentRecord();
        payment.id = Objects.requireNonNull(id, "id");
        payment.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        payment.plotId = Objects.requireNonNull(plotId, "plotId");
        payment.kind = PaymentKind.PAYMENT;
        payment.source = Objects.requireNonNull(source, "source");
        payment.amount = amount.value();
        payment.receivedOn = Objects.requireNonNull(receivedOn, "receivedOn");
        payment.billingMonthId = Objects.requireNonNull(billingMonthId, "billingMonthId");
        payment.groupId = Objects.requireNonNull(groupId, "groupId");
        payment.receiptNumber = receiptNumber;
        payment.bankReference = bankReference;
        payment.payerMemberId = payerMemberId;
        payment.payerNoteEnc = payerNoteEnc;
        payment.createdBy = createdBy;
        payment.createdAt = Objects.requireNonNull(now, "now");
        return payment;
    }

    /**
     * The reversal of {@code original}: kind {@code PAYMENT_REVERSAL}, the negated amount, the same plot, source, group
     * and {@code received_on} (like a charge reversal keeps the debt date of its charge), booked in
     * {@code billingMonthId}, with the reason, pointing at the original. It carries no receipt number (the original
     * keeps its own), no bank reference, payer or payer note.
     *
     * @throws IllegalArgumentException when {@code original} is a reversal itself or the reason is blank
     */
    static PaymentRecord reversalOf(PaymentRecord original, UUID billingMonthId, String reason,
            @Nullable UUID createdBy, Instant now) {
        if (original.kind != PaymentKind.PAYMENT) {
            throw new IllegalArgumentException("A reversal cancels a payment, not a reversal");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reversal has a reason");
        }
        PaymentRecord reversal = new PaymentRecord();
        reversal.id = UUID.randomUUID();
        reversal.tenantId = original.tenantId;
        reversal.plotId = original.plotId;
        reversal.kind = PaymentKind.PAYMENT_REVERSAL;
        reversal.source = original.source;
        reversal.amount = original.getAmount().negate().value();
        reversal.receivedOn = original.receivedOn;
        reversal.billingMonthId = Objects.requireNonNull(billingMonthId, "billingMonthId");
        reversal.groupId = original.groupId;
        reversal.reversesPaymentId = original.id;
        reversal.reason = reason;
        reversal.createdBy = createdBy;
        reversal.createdAt = Objects.requireNonNull(now, "now");
        return reversal;
    }

    UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    UUID getPlotId() {
        return plotId;
    }

    PaymentKind getKind() {
        return kind;
    }

    PaymentSource getSource() {
        return source;
    }

    /** Above zero for a payment, below zero for a reversal. */
    Money getAmount() {
        return Money.of(amount);
    }

    LocalDate getReceivedOn() {
        return receivedOn;
    }

    UUID getBillingMonthId() {
        return billingMonthId;
    }

    UUID getGroupId() {
        return groupId;
    }

    /** The receipt number of a cash payment; null for a bank payment and for every reversal. */
    @Nullable Long getReceiptNumber() {
        return receiptNumber;
    }

    @Nullable String getBankReference() {
        return bankReference;
    }

    @Nullable UUID getPayerMemberId() {
        return payerMemberId;
    }

    /** The sealed payer note; null when there is none. Only the payment note cipher opens it. */
    byte @Nullable [] getPayerNoteEnc() {
        return payerNoteEnc;
    }

    /** The payment a reversal cancels; null for a payment. */
    @Nullable UUID getReversesPaymentId() {
        return reversesPaymentId;
    }

    /** The reason of a reversal (user content); null for a payment. */
    @Nullable String getReason() {
        return reason;
    }

    /** The member who entered the row. */
    @Nullable UUID getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
