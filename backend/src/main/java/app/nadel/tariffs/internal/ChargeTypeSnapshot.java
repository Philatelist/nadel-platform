package app.nadel.tariffs.internal;

import app.nadel.audit.FieldChange;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The audited fields of a charge type as strings, so two states can be compared and the difference written to the
 * audit log. An empty description is null; flags are {@code true} or {@code false}. The name is written as
 * {@link #auditName}: the token {@code base:<CODE>} while a seeded type keeps its standard name, otherwise the stored
 * name.
 */
record ChargeTypeSnapshot(
        @Nullable String name,
        @Nullable String description,
        @Nullable String active,
        String archived) {

    /** The state before a charge type exists: nothing set and not archived. */
    static final ChargeTypeSnapshot NONE = new ChargeTypeSnapshot(null, null, null, "false");

    static ChargeTypeSnapshot of(ChargeTypeRecord type) {
        return new ChargeTypeSnapshot(auditName(type), type.getDescription(), String.valueOf(type.isActive()),
                String.valueOf(type.getArchivedAt() != null));
    }

    /**
     * The state a charge type would have with these values, not archived. {@code name} is the stored name and
     * {@code nameCustomized} whether it counts as renamed.
     */
    static ChargeTypeSnapshot of(@Nullable String code, String name, boolean nameCustomized,
            @Nullable String description, boolean active) {
        return new ChargeTypeSnapshot(auditName(code, name, nameCustomized), description, String.valueOf(active),
                "false");
    }

    /** The name of the type as written to the audit log, see {@link #auditName(String, String, boolean)}. */
    static String auditName(ChargeTypeRecord type) {
        return auditName(type.getCode(), type.getName(), type.isNameCustomized());
    }

    /**
     * The name as written to the audit log. A seeded type that still has its standard name is written as the token
     * {@code base:<CODE>} (for example {@code base:MEMBERSHIP}), because the standard name is shown in the reader's
     * language by the client; every other type is written under its stored name.
     */
    static String auditName(@Nullable String code, String name, boolean nameCustomized) {
        return code != null && !nameCustomized ? "base:" + code : name;
    }

    /** The fields whose value differs, in a fixed order; empty when nothing changed. */
    static List<FieldChange> diff(ChargeTypeSnapshot before, ChargeTypeSnapshot after) {
        List<FieldChange> changes = new ArrayList<>();
        add(changes, "name", before.name, after.name);
        add(changes, "description", before.description, after.description);
        add(changes, "active", before.active, after.active);
        add(changes, "archived", before.archived, after.archived);
        return changes;
    }

    private static void add(List<FieldChange> changes, String field, @Nullable String before,
            @Nullable String after) {
        if (!Objects.equals(before, after)) {
            changes.add(new FieldChange(field, before, after));
        }
    }
}
