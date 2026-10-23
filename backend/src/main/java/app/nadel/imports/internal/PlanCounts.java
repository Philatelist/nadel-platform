package app.nadel.imports.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records per sheet by what the import does with them. Stored as JSON in {@code import_run.counts} and returned in the
 * preview. {@code waterConnections} counts the water column of the Plots sheet (feature 011).
 */
record PlanCounts(EntityCounts plots, EntityCounts members, EntityCounts ownerships, EntityCounts meters,
        EntityCounts waterConnections, EntityCounts openingBalances) {

    /** The keys of {@link #toMap()}, in order. */
    static final List<String> KEYS = List.of("plots", "members", "ownerships", "meters", "waterConnections",
            "openingBalances");

    /** The number of records of one kind that are created, updated or already as in the file. */
    record EntityCounts(int created, int updated, int unchanged) {

        static final EntityCounts NONE = new EntityCounts(0, 0, 0);

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("created", created);
            map.put("updated", updated);
            map.put("unchanged", unchanged);
            return map;
        }
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("plots", plots.toMap());
        map.put("members", members.toMap());
        map.put("ownerships", ownerships.toMap());
        map.put("meters", meters.toMap());
        map.put("waterConnections", waterConnections.toMap());
        map.put("openingBalances", openingBalances.toMap());
        return map;
    }

    /** True when the import would create or update at least one record. */
    boolean changesAnything() {
        return plots.created() + plots.updated() + members.created() + members.updated()
                + ownerships.created() + ownerships.updated() + meters.created() + meters.updated()
                + waterConnections.created() + waterConnections.updated()
                + openingBalances.created() + openingBalances.updated() > 0;
    }
}
