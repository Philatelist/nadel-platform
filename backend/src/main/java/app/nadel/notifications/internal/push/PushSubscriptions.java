package app.nadel.notifications.internal.push;

import app.nadel.privacy.FieldRef;
import app.nadel.privacy.IndexKind;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.tenancy.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The devices of members that receive push messages (feature 021 §2.2), table {@code push_subscription} (V39). The
 * endpoint and the two keys are sealed with the cooperative's data key (ADR 0016) at
 * {@code FieldRef("push_subscription", "endpoint_enc" | "ua_public_enc" | "auth_enc", id)} (the column
 * {@code p256dh_enc} has the place name {@value #P256DH_PLACE}: place names have no digits), the keys as base64url
 * text; the endpoint is found by its blind index ({@link IndexKind#PUSH_ENDPOINT} of the endpoint as sent, stripped) in
 * {@code endpoint_index}, so one endpoint is one row per cooperative. The row id is made here, before sealing, since
 * it is part of the sealed values' place. Every method runs in a transaction of the bound cooperative (RLS). Nothing is
 * logged and no endpoint or key leaves through {@link #toString}.
 */
@Component
class PushSubscriptions {

    static final String TABLE = "push_subscription";

    /**
     * The place name of the column {@code p256dh_enc} in its {@link FieldRef}: place names have no digits (they are
     * joined into the associated data), so the browser key is named after RFC 8291's {@code ua_public}.
     */
    static final String P256DH_PLACE = "ua_public_enc";

    /** The most devices a member keeps; registering one more drops the oldest. */
    static final int MAX_PER_MEMBER = 10;

    /** After this many refusals in a row a device is deleted. */
    static final int MAX_CONSECUTIVE_FAILURES = 5;

    /** A device as its member sees it: never the endpoint or the keys. */
    record Device(UUID id, @Nullable String label, Instant createdAt, @Nullable Instant lastSentAt) {
    }

    /** A device that receives a message: its id, its member and the key pair it was made with. */
    record Recipient(UUID id, UUID memberId, byte[] serverKeyId) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Recipient that && id.equals(that.id) && memberId.equals(that.memberId)
                    && Arrays.equals(serverKeyId, that.serverKeyId);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return "Recipient[id=" + id + ", memberId=" + memberId + "]";
        }
    }

    /** Everything needed to send to a device, opened. Never printed. */
    record Target(UUID id, UUID memberId, String endpoint, byte[] p256dh, byte[] auth, String language,
            byte[] serverKeyId) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Target that && id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return "Target[id=" + id + "]";
        }
    }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final PersonalDataCipher cipher;

    PushSubscriptions(JdbcTemplate jdbc, PersonalDataCipher cipher) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.cipher = cipher;
    }

    /**
     * Saves the subscription for the member: a new device, or the device of the same endpoint updated (its keys,
     * label, language and key pair, the refusals cleared) and moved to this member when another member had it (then
     * it counts as new). Then the member's oldest devices beyond {@value #MAX_PER_MEMBER} are dropped.
     *
     * @param endpoint the endpoint as sent, stripped and already allowed
     * @param p256dh   the browser key, a point of the curve
     * @param auth     the 16-byte auth secret
     */
    Device save(UUID memberId, String endpoint, byte[] p256dh, byte[] auth, @Nullable String label, String language,
            byte[] serverKeyId) {
        UUID tenantId = tenant();
        byte[] index = cipher.blindIndex(tenantId, IndexKind.PUSH_ENDPOINT, endpoint);
        UUID id = null;
        for (int attempt = 0; attempt < 2 && id == null; attempt++) {
            List<UUID[]> existing = jdbc.query("select id, member_id from push_subscription where endpoint_index = ? "
                    + "for update", (rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)},
                    (Object) index);
            if (!existing.isEmpty()) {
                id = existing.getFirst()[0];
                boolean moved = !memberId.equals(existing.getFirst()[1]);
                jdbc.update("update push_subscription set member_id = ?, endpoint_enc = ?, p256dh_enc = ?, "
                        + "auth_enc = ?, server_key_id = ?, label = ?, language = ?, consecutive_failures = 0, "
                        + "created_at = case when ? then now() else created_at end, "
                        + "last_sent_at = case when ? then null else last_sent_at end where id = ?",
                        memberId, seal(tenantId, "endpoint_enc", id, endpoint),
                        seal(tenantId, P256DH_PLACE, id, P256.base64Url(p256dh)),
                        seal(tenantId, "auth_enc", id, P256.base64Url(auth)), serverKeyId, label, language, moved,
                        moved, id);
            } else {
                UUID candidate = UUID.randomUUID();
                int inserted = jdbc.update("insert into push_subscription (id, tenant_id, member_id, endpoint_enc, "
                        + "p256dh_enc, auth_enc, endpoint_index, server_key_id, label, language) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (tenant_id, endpoint_index) do nothing",
                        candidate, tenantId, memberId, seal(tenantId, "endpoint_enc", candidate, endpoint),
                        seal(tenantId, P256DH_PLACE, candidate, P256.base64Url(p256dh)),
                        seal(tenantId, "auth_enc", candidate, P256.base64Url(auth)), index, serverKeyId, label,
                        language);
                // a concurrent registration of the same endpoint won: the next round updates its row
                id = inserted == 1 ? candidate : null;
            }
        }
        if (id == null) {
            throw new IllegalStateException("The device could not be saved");
        }
        jdbc.update("delete from push_subscription where id in (select id from push_subscription where member_id = ? "
                + "order by created_at desc, id desc offset ?)", memberId, MAX_PER_MEMBER);
        UUID saved = id;
        return of(memberId).stream().filter(device -> device.id().equals(saved)).findFirst()
                .orElseThrow(() -> new IllegalStateException("The device was dropped"));
    }

    /** The member's devices, newest first. */
    List<Device> of(UUID memberId) {
        return jdbc.query("select id, label, created_at, last_sent_at from push_subscription where member_id = ? "
                + "order by created_at desc, id desc", (rs, n) -> new Device(rs.getObject(1, UUID.class),
                        rs.getString(2), rs.getTimestamp(3).toInstant(), instant(rs.getTimestamp(4))), memberId);
    }

    /** Deletes the member's device; false when the member has no device with the id. */
    boolean delete(UUID memberId, UUID id) {
        return jdbc.update("delete from push_subscription where id = ? and member_id = ?", id, memberId) == 1;
    }

    /** The devices of the members. */
    List<Recipient> devicesOf(Collection<UUID> memberIds) {
        if (memberIds.isEmpty()) {
            return List.of();
        }
        return named.query("select id, member_id, server_key_id from push_subscription where member_id in (:members) "
                + "order by member_id, created_at, id", new MapSqlParameterSource("members", memberIds),
                (rs, n) -> new Recipient(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBytes(3)));
    }

    /**
     * How many of the members have at least one device made with the key pair {@code serverKeyId} (the devices that can
     * still receive a message).
     */
    int membersWithDevices(Collection<UUID> memberIds, byte[] serverKeyId) {
        if (memberIds.isEmpty()) {
            return 0;
        }
        Integer count = named.queryForObject("select count(distinct member_id) from push_subscription "
                + "where member_id in (:members) and server_key_id = :key", new MapSqlParameterSource()
                        .addValue("members", memberIds).addValue("key", serverKeyId), Integer.class);
        return count == null ? 0 : count;
    }

    /** The device opened for sending; empty when it no longer exists. */
    Optional<Target> target(UUID id) {
        UUID tenantId = tenant();
        return jdbc.query("select member_id, endpoint_enc, p256dh_enc, auth_enc, language, server_key_id "
                + "from push_subscription where id = ?", (rs, n) -> new Target(id, rs.getObject(1, UUID.class),
                        open(tenantId, "endpoint_enc", id, rs.getBytes(2)),
                        bytes(open(tenantId, P256DH_PLACE, id, rs.getBytes(3))),
                        bytes(open(tenantId, "auth_enc", id, rs.getBytes(4))), rs.getString(5), rs.getBytes(6)), id)
                .stream().findFirst();
    }

    /** The push service took a message: the time noted, the refusals cleared. */
    void delivered(UUID id, Instant at) {
        jdbc.update("update push_subscription set last_sent_at = ?, consecutive_failures = 0 where id = ?",
                Timestamp.from(at), id);
    }

    /**
     * The push service refused a message: one more refusal in a row, and the device deleted at
     * {@value #MAX_CONSECUTIVE_FAILURES}. Whether it was deleted.
     */
    boolean rejected(UUID id) {
        jdbc.update("update push_subscription set consecutive_failures = least(consecutive_failures + 1, 32000) "
                + "where id = ?", id);
        return jdbc.update("delete from push_subscription where id = ? and consecutive_failures >= ?", id,
                MAX_CONSECUTIVE_FAILURES) == 1;
    }

    /** Deletes every device of the member (access switched off); how many there were. */
    int deleteAllOf(UUID memberId) {
        return jdbc.update("delete from push_subscription where member_id = ?", memberId);
    }

    /** Deletes the device (gone at the push service, or made with another key pair). */
    void delete(UUID id) {
        jdbc.update("delete from push_subscription where id = ?", id);
    }

    private byte[] seal(UUID tenantId, String column, UUID id, String value) {
        return cipher.encrypt(tenantId, new FieldRef(TABLE, column, id), value);
    }

    private String open(UUID tenantId, String column, UUID id, byte[] sealed) {
        return cipher.decrypt(tenantId, new FieldRef(TABLE, column, id), sealed);
    }

    private static byte[] bytes(String base64Url) {
        return P256.fromBase64Url(base64Url).orElseThrow(() -> new IllegalStateException("A stored key is unreadable"));
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static UUID tenant() {
        return TenantContext.currentTenantId().orElseThrow(() -> new IllegalStateException("No cooperative"));
    }
}
