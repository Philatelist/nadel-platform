package app.nadel.tariffs.internal;

import app.nadel.audit.FieldChange;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The audited fields of a charge type as strings, so two states can be compared and the difference written to the
 * audit log. An empty description is null; flags are {@code true} or {@code false}.
 */
record ChargeTypeSnapshot(
        @Nullable String name,
        @Nullable String description,
        @Nullable String active,
        String archived) {

    /** The state before a charge type exists: nothing set and not archived. */
    static final ChargeTypeSnapshot NONE = new ChargeTypeSnapshot(null, null, null, "false");

    static ChargeTypeSnapshot of(ChargeTypeRecord type) {
        return new ChargeTypeSnapshot(type.getName(), type.getDescription(), String.valueOf(type.isActive()),
                String.valueOf(type.getArchivedAt() != null));
    }

    /** The state a charge type would have with these values, not archived. */
    static ChargeTypeSnapshot of(String name, @Nullable String description, boolean active) {
        return new ChargeTypeSnapshot(name, description, String.valueOf(active), "false");
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
