package app.nadel.treasury.internal;

import app.nadel.audit.FieldChange;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.PersonalDataCipher;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Seals and opens the personal texts of the treasury (feature 018 §2.3, technical considerations 2.6; ADR 0016, the 014
 * mechanism of {@code members/internal/ContactCipher}): the counterparty and the description of an expense at
 * {@code FieldRef("expense", "counterparty_enc" | "description_enc", expenseId)} and the description of an other income
 * at {@code FieldRef("other_income", "description_enc", incomeId)}, so a value sealed for one row never opens for
 * another, and their audit values at {@link FieldChange#newValueRef} of the audit event id (shown only to
 * readers with {@code MEMBERS_VIEW}). Never log a value. Runs in the cooperative's transaction ({@code tenantId} is the
 * current cooperative).
 */
@Component
class TreasuryCipher {

    static final String EXPENSE = "expense";
    static final String OTHER_INCOME = "other_income";
    static final String COUNTERPARTY = "counterparty_enc";
    static final String DESCRIPTION = "description_enc";

    private final PersonalDataCipher cipher;

    TreasuryCipher(PersonalDataCipher cipher) {
        this.cipher = cipher;
    }

    /** The place of a sealed column of a row. */
    static FieldRef ref(String table, String column, UUID rowId) {
        return new FieldRef(table, column, rowId);
    }

    /** The sealed text, or null when there is none. */
    byte @Nullable [] seal(UUID tenantId, FieldRef ref, @Nullable String text) {
        return text == null ? null : cipher.encrypt(tenantId, ref, text);
    }

    /**
     * The text, or null when none is stored.
     *
     * @throws app.nadel.privacy.PersonalDataUnreadableException when the stored value does not open
     */
    @Nullable String open(UUID tenantId, FieldRef ref, byte @Nullable [] sealed) {
        return sealed == null ? null : cipher.decrypt(tenantId, ref, sealed);
    }

    /**
     * The audit change of a personal field: the new value sealed at {@link FieldChange#newValueRef} of the event id as
     * base64 text, {@code sealed = true}; null when there is no value.
     */
    @Nullable FieldChange auditValue(UUID tenantId, UUID eventId, String field, @Nullable String text) {
        if (text == null) {
            return null;
        }
        byte[] sealed = cipher.encrypt(tenantId, FieldChange.newValueRef(eventId, field), text);
        return new FieldChange(field, null, Base64.getEncoder().encodeToString(sealed), true);
    }
}
