package app.nadel.audit;

import org.jspecify.annotations.Nullable;

/**
 * One changed field. Values are strings; multi-valued fields (phones, role names) are sorted and joined by the
 * publisher. {@code oldValue} is null for a new record, {@code newValue} null for a cleared field.
 */
public record FieldChange(String field, @Nullable String oldValue, @Nullable String newValue) {
}
