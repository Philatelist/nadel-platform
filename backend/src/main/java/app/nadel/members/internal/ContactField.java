package app.nadel.members.internal;

import app.nadel.privacy.FieldRef;
import java.util.UUID;

/**
 * The six sealed contact fields of a member and the column each lives in. The column name is also the column of the
 * value's {@link FieldRef} (table {@code member}, row = member id), so a value sealed by the Java migration 22.1, the
 * demo contacts callback or the application opens in every one of them, and only in its own row and column.
 */
enum ContactField {

    PHONES("phones_enc"),
    EMAIL("email_enc"),
    POSTAL_ADDRESS("postal_address_enc"),
    TELEGRAM_USERNAME("telegram_username_enc"),
    VIBER_PHONE("viber_phone_enc"),
    NOTES("notes_enc");

    static final String TABLE = "member";

    private final String column;

    ContactField(String column) {
        this.column = column;
    }

    String column() {
        return column;
    }

    /** The place of this field of a member: the associated data of its encryption. */
    FieldRef ref(UUID memberId) {
        return new FieldRef(TABLE, column, memberId);
    }
}
