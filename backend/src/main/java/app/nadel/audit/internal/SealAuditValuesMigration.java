package app.nadel.audit.internal;

import app.nadel.audit.FieldChange;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.OwnerKeyring;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Java migration 24 (ADR 0016): seals the personal values that audit entries and pending audit publications hold in
 * the clear, so that neither {@code audit_entry} nor {@code event_publication} keeps a member's contacts readable.
 * <ul>
 *   <li>{@code audit_entry} rows of entity type {@code MEMBER}: each string value ({@code old}, {@code new}) of the
 *       personal fields becomes {@code {"enc": "<base64>"}}, sealed at {@link FieldChange#oldValueRef} /
 *       {@link FieldChange#newValueRef} of the entry id, exactly as the application writes them now. This UPDATE as
 *       the schema owner is the one documented exception to the append-only rule of the audit log (the application
 *       role still cannot update it).</li>
 *   <li>Unfinished {@code event_publication} rows of an {@code AuditEvent} or an {@code AuditBatch} (completed rows are
 *       deleted, {@code completion-mode: delete}): the serialized changes of {@code MEMBER} events get their personal
 *       values sealed as base64 text with {@code sealed: true}, as {@code members} publishes them now; the listener
 *       then stores them as above when the publication is resubmitted.</li>
 * </ul>
 * Runs in Flyway's migration transaction on the owner connection through {@link OwnerKeyring} (no JPA). Idempotent: a
 * value that is already sealed is left as it is. Fixed checksum; the log has counts only.
 */
@Component
class SealAuditValuesMigration implements JavaMigration {

    static final String VERSION = "24";
    /** Fixed: the migration's code is its definition, and an edit that changes what it does needs a new version. */
    private static final int CHECKSUM = 240_000_001;
    private static final int PAGE = 500;
    private static final Logger log = LoggerFactory.getLogger(SealAuditValuesMigration.class);

    /**
     * The fields of a {@code MEMBER} audit entry that hold personal data, as {@code members} names them (its
     * {@code MemberAuditPublisher}). Listed here because {@code audit} never depends on {@code members}.
     */
    static final Set<String> MEMBER_PERSONAL_FIELDS = Set.of("phones", "email", "postalAddress", "telegramUsername",
            "viberPhone", "notes");

    static final String AUDIT_EVENT = "app.nadel.audit.AuditEvent";
    static final String AUDIT_BATCH = "app.nadel.audit.AuditBatch";

    private static final String SELECT_ENTRIES = """
            select id, tenant_id, cast(changes as text) from audit_entry
            where entity_type = 'MEMBER' and (cast(? as uuid) is null or id > ?)
              and exists (select 1 from jsonb_array_elements(changes) c where c ->> 'field' = any (?))
            order by id limit ?
            """;
    private static final String UPDATE_ENTRY = "update audit_entry set changes = cast(? as jsonb) where id = ?";
    private static final String SELECT_PUBLICATIONS = """
            select id, serialized_event from event_publication
            where completion_date is null and event_type in (?, ?) and (cast(? as uuid) is null or id > ?)
            order by id limit ?
            """;
    private static final String UPDATE_PUBLICATION = "update event_publication set serialized_event = ? where id = ?";

    private final OwnerKeyring keyring;

    SealAuditValuesMigration(OwnerKeyring keyring) {
        this.keyring = keyring;
    }

    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion(VERSION);
    }

    @Override
    public String getDescription() {
        return "seal personal values in the audit log";
    }

    @Override
    public Integer getChecksum() {
        return CHECKSUM;
    }

    @Override
    public boolean canExecuteInTransaction() {
        return true;
    }

    @Override
    public void migrate(Context context) throws SQLException {
        Counts counts = seal(context.getConnection());
        log.info("Audit values sealed: {} of {} member entries, {} of {} pending audit publications",
                counts.entriesSealed, counts.entriesRead, counts.publicationsSealed, counts.publicationsRead);
    }

    /** Seals the personal values of the audit log and of pending audit publications on the given owner connection. */
    Counts seal(Connection connection) throws SQLException {
        OwnerKeyring.Session session = keyring.open(connection);
        Counts counts = new Counts();
        sealEntries(connection, session, counts);
        sealPublications(connection, session, counts);
        return counts;
    }

    private static void sealEntries(Connection connection, OwnerKeyring.Session session, Counts counts)
            throws SQLException {
        UUID after = null;
        List<Row> page;
        do {
            page = entries(connection, after);
            try (PreparedStatement update = connection.prepareStatement(UPDATE_ENTRY)) {
                for (Row row : page) {
                    counts.entriesRead++;
                    after = row.id;
                    JsonNode changes = JsonMapper.shared().readTree(row.json);
                    OwnerKeyring.TenantCipher cipher = session.forTenant(row.tenantId);
                    boolean changed = false;
                    for (JsonNode change : changes) {
                        String field = change.path("field").asString("");
                        if (change instanceof ObjectNode node && MEMBER_PERSONAL_FIELDS.contains(field)) {
                            changed |= sealStored(node, "old", cipher, FieldChange.oldValueRef(row.id, field));
                            changed |= sealStored(node, "new", cipher, FieldChange.newValueRef(row.id, field));
                        }
                    }
                    if (changed) {
                        update.setString(1, JsonMapper.shared().writeValueAsString(changes));
                        update.setObject(2, row.id, Types.OTHER);
                        update.addBatch();
                        counts.entriesSealed++;
                    }
                }
                update.executeBatch();
            }
        } while (page.size() == PAGE);
    }

    /** Seals a stored string value into {@code {"enc": ...}}; null and already sealed values are left alone. */
    private static boolean sealStored(ObjectNode change, String key, OwnerKeyring.TenantCipher cipher, FieldRef ref) {
        JsonNode value = change.get(key);
        if (value == null || !value.isString()) {
            return false;
        }
        ObjectNode sealed = change.putObject(key);
        sealed.put("enc", Base64.getEncoder().encodeToString(cipher.encrypt(ref, value.asString())));
        return true;
    }

    private static void sealPublications(Connection connection, OwnerKeyring.Session session, Counts counts)
            throws SQLException {
        UUID after = null;
        List<Row> page;
        do {
            page = publications(connection, after);
            try (PreparedStatement update = connection.prepareStatement(UPDATE_PUBLICATION)) {
                for (Row row : page) {
                    counts.publicationsRead++;
                    after = row.id;
                    JsonNode event = JsonMapper.shared().readTree(row.json);
                    boolean changed = false;
                    if (event.has("entries")) {
                        for (JsonNode entry : event.path("entries")) {
                            changed |= sealEvent(entry, session);
                        }
                    } else {
                        changed = sealEvent(event, session);
                    }
                    if (changed) {
                        update.setString(1, JsonMapper.shared().writeValueAsString(event));
                        update.setObject(2, row.id, Types.OTHER);
                        update.addBatch();
                        counts.publicationsSealed++;
                    }
                }
                update.executeBatch();
            }
        } while (page.size() == PAGE);
    }

    /** Seals the personal changes of one serialized {@code AuditEvent} of a member; true when something changed. */
    private static boolean sealEvent(JsonNode event, OwnerKeyring.Session session) {
        if (!"MEMBER".equals(event.path("entityType").asString(""))) {
            return false;
        }
        UUID eventId = UUID.fromString(event.path("eventId").asString());
        OwnerKeyring.TenantCipher cipher = session.forTenant(UUID.fromString(event.path("tenantId").asString()));
        boolean changed = false;
        for (JsonNode change : event.path("changes")) {
            String field = change.path("field").asString("");
            if (change instanceof ObjectNode node && MEMBER_PERSONAL_FIELDS.contains(field)
                    && !node.path("sealed").asBoolean(false)) {
                sealSerialized(node, "oldValue", cipher, eventId, field, FieldChange::oldValueRef);
                sealSerialized(node, "newValue", cipher, eventId, field, FieldChange::newValueRef);
                node.put("sealed", true);
                changed = true;
            }
        }
        return changed;
    }

    private static void sealSerialized(ObjectNode change, String key, OwnerKeyring.TenantCipher cipher, UUID eventId,
            String field, BiFunction<UUID, String, FieldRef> ref) {
        JsonNode value = change.get(key);
        if (value != null && value.isString()) {
            change.put(key, Base64.getEncoder().encodeToString(
                    cipher.encrypt(ref.apply(eventId, field), value.asString())));
        }
    }

    private static List<Row> entries(Connection connection, @Nullable UUID after) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(SELECT_ENTRIES)) {
            Array fields = connection.createArrayOf("text", MEMBER_PERSONAL_FIELDS.toArray());
            select.setObject(1, after, Types.OTHER);
            select.setObject(2, after, Types.OTHER);
            select.setArray(3, fields);
            select.setInt(4, PAGE);
            return rows(select, true);
        }
    }

    private static List<Row> publications(Connection connection, @Nullable UUID after) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(SELECT_PUBLICATIONS)) {
            select.setString(1, AUDIT_EVENT);
            select.setString(2, AUDIT_BATCH);
            select.setObject(3, after, Types.OTHER);
            select.setObject(4, after, Types.OTHER);
            select.setInt(5, PAGE);
            return rows(select, false);
        }
    }

    private static List<Row> rows(PreparedStatement select, boolean withTenant) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                rows.add(withTenant
                        ? new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3))
                        : new Row(rs.getObject(1, UUID.class), null, rs.getString(2)));
            }
        }
        return rows;
    }

    /** What the migration did: counts only. */
    static final class Counts {
        int entriesRead;
        int entriesSealed;
        int publicationsRead;
        int publicationsSealed;
    }

    /** A row to seal: its id, its cooperative (audit entries only) and its JSON. */
    private record Row(UUID id, @Nullable UUID tenantId, String json) {
    }
}
