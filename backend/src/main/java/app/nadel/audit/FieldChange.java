package app.nadel.audit;

import app.nadel.privacy.FieldRef;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One changed field. Values are strings; multi-valued fields (phones, role names) are sorted and joined by the
 * publisher. {@code oldValue} is null for a new record, {@code newValue} null for a cleared field.
 * <p>
 * {@code sealed} means the field holds personal data and both values (when not null) are already sealed by the
 * publisher with {@code privacy.PersonalDataCipher} at {@link #oldValueRef} and {@link #newValueRef} of the event id,
 * as base64 text; the outbox therefore never holds them in the clear. The audit log stores them as
 * {@code {"enc": "<base64>"}} and opens them only for a reader who may view members (see the audit README).
 */
public record FieldChange(String field, @Nullable String oldValue, @Nullable String newValue, boolean sealed) {

    /** The {@code audit_entry} table: the table of the field reference of a sealed value. */
    public static final String TABLE = "audit_entry";

    /** A change of a field that holds no personal data: the values are stored as they are. */
    public FieldChange(String field, @Nullable String oldValue, @Nullable String newValue) {
        this(field, oldValue, newValue, false);
    }

    /**
     * Reads a change from the outbox. A publication stored before the flag existed has no {@code sealed} property and
     * holds no sealed value (the Java migration 24 sealed the personal ones), so a missing flag is false.
     */
    @JsonCreator
    static FieldChange fromJson(@JsonProperty("field") String field,
            @JsonProperty("oldValue") @Nullable String oldValue, @JsonProperty("newValue") @Nullable String newValue,
            @JsonProperty("sealed") @Nullable Boolean sealed) {
        return new FieldChange(field, oldValue, newValue, Boolean.TRUE.equals(sealed));
    }

    /**
     * Where the sealed old value of {@code field} lives: {@code FieldRef("audit_entry", "changes.<field>.old",
     * entryId)}, the field name in snake case ({@code postalAddress} is {@code postal_address}).
     *
     * @throws IllegalArgumentException when the field name has characters other than letters and {@code _}
     */
    public static FieldRef oldValueRef(UUID entryId, String field) {
        return new FieldRef(TABLE, "changes." + snakeCase(field) + ".old", entryId);
    }

    /** Where the sealed new value of {@code field} lives; see {@link #oldValueRef}. */
    public static FieldRef newValueRef(UUID entryId, String field) {
        return new FieldRef(TABLE, "changes." + snakeCase(field) + ".new", entryId);
    }

    private static String snakeCase(String field) {
        return field.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
    }
}
