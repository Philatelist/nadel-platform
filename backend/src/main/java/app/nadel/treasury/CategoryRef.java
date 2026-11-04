package app.nadel.treasury;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An expense or income category as reports name it (feature 018 §2.9): a seeded category never renamed has its
 * {@code systemCode} and no {@code name} (the reader names it from its catalogs); a renamed or added one has its
 * {@code name} (user content).
 */
public record CategoryRef(UUID id, Kind kind, @Nullable String systemCode, @Nullable String name, int sortOrder,
        boolean archived) {

    public CategoryRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        if (systemCode == null && name == null) {
            throw new IllegalArgumentException("A category has a system code or a name");
        }
    }

    /** The list a category belongs to. */
    public enum Kind {

        /** An expense category («Стаття витрат»). */
        EXPENSE,

        /** An income category of other income («Стаття доходів»). */
        INCOME
    }
}
