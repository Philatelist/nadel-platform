package app.nadel.audit;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * SPI implemented by the module that owns members. The audit log stores only the id of the acting member; when
 * entries are read, their current display names are looked up here, so {@code audit} never depends on
 * {@code members} (the dependency is {@code members} to {@code audit}, as for publishing events).
 */
public interface ActorNames {

    /**
     * Current display names ("Last First Middle") of members of the current cooperative. The caller has bound the
     * cooperative (a transaction of the tenant-aware transaction manager). Ids of members that do not exist (any
     * more) are absent from the result.
     */
    Map<UUID, String> displayNames(Collection<UUID> memberIds);
}
