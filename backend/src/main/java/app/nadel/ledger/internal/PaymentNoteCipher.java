package app.nadel.ledger.internal;

import app.nadel.privacy.FieldRef;
import app.nadel.privacy.PersonalDataCipher;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Seals and opens the payer note of a payment (column {@code payment.payer_note_enc}, ADR 0016) with the
 * {@code privacy} cipher at {@code FieldRef("payment", "payer_note", paymentId)}, so a note sealed for one payment never
 * opens for another. Modelled on {@code members/internal/ContactCipher}. The note is personal data: never log it.
 * Runs in the cooperative's transaction ({@code tenantId} is the current cooperative).
 */
@Component
class PaymentNoteCipher {

    static final String TABLE = "payment";
    static final String COLUMN = "payer_note";

    private final PersonalDataCipher cipher;

    PaymentNoteCipher(PersonalDataCipher cipher) {
        this.cipher = cipher;
    }

    /** The place of the note of a payment. */
    static FieldRef ref(UUID paymentId) {
        return new FieldRef(TABLE, COLUMN, paymentId);
    }

    /** The sealed note, or null when there is none (null or blank). */
    byte @Nullable [] seal(UUID tenantId, UUID paymentId, @Nullable String note) {
        return note == null || note.isBlank() ? null : cipher.encrypt(tenantId, ref(paymentId), note);
    }

    /**
     * The note, or null when none is stored.
     *
     * @throws app.nadel.privacy.PersonalDataUnreadableException when the stored value does not open
     */
    @Nullable String open(UUID tenantId, UUID paymentId, byte @Nullable [] sealed) {
        return sealed == null ? null : cipher.decrypt(tenantId, ref(paymentId), sealed);
    }
}
