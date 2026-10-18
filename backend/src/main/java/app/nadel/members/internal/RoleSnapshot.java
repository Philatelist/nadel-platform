package app.nadel.members.internal;

import app.nadel.audit.FieldChange;
import app.nadel.identity.Permission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The audited fields of a role as strings, so two states can be compared and the difference written to the audit
 * log. Permissions are sorted by name and joined; an empty value is null.
 */
record RoleSnapshot(String name, @Nullable String description, @Nullable String permissions) {

    static RoleSnapshot of(String name, @Nullable String description, Collection<Permission> permissions) {
        String joined = permissions.isEmpty() ? null
                : String.join(", ", permissions.stream().map(Permission::name).sorted().toList());
        return new RoleSnapshot(name, description, joined);
    }

    /**
     * The name as written to the audit log. A base role that still has its standard name is written as the token
     * {@code base:<BASE_CODE>}, because the standard name is shown in the reader's language by the client; every other
     * role is written under its stored name.
     */
    static String auditName(String name, @Nullable String baseCode, boolean nameCustomized) {
        return baseCode != null && !nameCustomized ? "base:" + baseCode : name;
    }

    /**
     * The description as written to the audit log. A base role that still has its standard description is written as
     * the token {@code base:<BASE_CODE>}, because the standard description is shown in the reader's language by the
     * client; every other role is written under its stored description (possibly null).
     */
    static @Nullable String auditDescription(@Nullable String description, @Nullable String baseCode,
            boolean descriptionCustomized) {
        return baseCode != null && !descriptionCustomized ? "base:" + baseCode : description;
    }

    /** The state before a role exists (or after it is gone): every field empty. */
    static final RoleSnapshot NONE = new RoleSnapshot(null, null, null);

    /** The fields whose value differs, in a fixed order; empty when nothing changed. */
    static List<FieldChange> diff(RoleSnapshot before, RoleSnapshot after) {
        List<FieldChange> changes = new ArrayList<>();
        add(changes, "name", before.name, after.name);
        add(changes, "description", before.description, after.description);
        add(changes, "permissions", before.permissions, after.permissions);
        return changes;
    }

    private static void add(List<FieldChange> changes, String field, @Nullable String before,
            @Nullable String after) {
        if (!Objects.equals(before, after)) {
            changes.add(new FieldChange(field, before, after));
        }
    }
}
