package app.nadel.members.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Component;

/**
 * The invitation links of members (feature 020, table {@code member_invitation}): only the SHA-256 of a token is
 * stored, never the token. The application may set only {@code sent_at}, {@code used_at} and {@code revoked_at} of a row
 * (column grants of V36) and never deletes one, so a link's history stays. Plain JDBC in the caller's transaction (RLS
 * limits the rows to the bound cooperative), except {@link #tenantOf}, which finds the cooperative of a token hash
 * before one is bound.
 */
@Component
class MemberInvitationRows {

    /**
     * One invitation link.
     *
     * @param emailBidx the member's e-mail blind index when the link was made: the link dies when the e-mail changes
     */
    record Invitation(UUID id, UUID memberId, byte[] emailBidx, Instant expiresAt, @Nullable Instant sentAt,
            @Nullable Instant usedAt, @Nullable Instant revokedAt) {

        /** Sent, not used, not replaced or turned off, and not expired at {@code now}. */
        boolean usable(Instant now) {
            return sentAt != null && usedAt == null && revokedAt == null && now.isBefore(expiresAt);
        }
    }

    private static final String COLUMNS = "id, member_id, email_bidx, expires_at, sent_at, used_at, revoked_at";

    private final JdbcTemplate jdbc;

    MemberInvitationRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A new link that is not sent yet: it does not count as the member's open link until {@link #markSent}. */
    UUID insertPending(UUID tenantId, UUID memberId, byte[] tokenHash, byte[] emailBidx, Instant createdAt,
            Instant expiresAt, @Nullable UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into member_invitation (id, tenant_id, member_id, token_hash, email_bidx, created_at, "
                + "expires_at, created_by) values (?, ?, ?, ?, ?, ?, ?, ?)", id, tenantId, memberId, tokenHash,
                emailBidx, utc(createdAt), utc(expiresAt), new SqlParameterValue(Types.OTHER, createdBy));
        return id;
    }

    /**
     * Revokes the member's other open links, then marks this one sent: one open link per member
     * ({@code member_invitation_open_uq}), the newest.
     */
    void markSent(UUID id, UUID memberId, Instant at) {
        jdbc.update("update member_invitation set revoked_at = ? where member_id = ? and id <> ? "
                + "and sent_at is not null and used_at is null and revoked_at is null", utc(at), memberId, id);
        jdbc.update("update member_invitation set sent_at = ? where id = ? and sent_at is null", utc(at), id);
    }

    /** Revokes one link (a pending one whose letter was not sent). */
    void revoke(UUID id, Instant at) {
        jdbc.update("update member_invitation set revoked_at = ? where id = ? and revoked_at is null", utc(at), id);
    }

    /**
     * Revokes every link of the member that is not used or revoked yet, a pending one included (access disabled, the
     * member archived, the e-mail changed); answers how many.
     */
    int revokeUnused(UUID memberId, Instant at) {
        return jdbc.update("update member_invitation set revoked_at = ? where member_id = ? and used_at is null "
                + "and revoked_at is null", utc(at), memberId);
    }

    /** Whether the member has a sent link, not used, revoked or expired at {@code now}, made for this e-mail. */
    boolean hasOpen(UUID memberId, byte[] emailBidx, Instant now) {
        Integer count = jdbc.queryForObject("select count(*) from member_invitation where member_id = ? "
                + "and email_bidx = ? and sent_at is not null and used_at is null and revoked_at is null "
                + "and expires_at > ?", Integer.class, memberId, emailBidx, utc(now));
        return count != null && count > 0;
    }

    /** Marks a link used. */
    void markUsed(UUID id, Instant at) {
        jdbc.update("update member_invitation set used_at = ? where id = ? and used_at is null", utc(at), id);
    }

    /**
     * The cooperative of a token hash, without a bound cooperative: {@code members_invitation_tenant}, the
     * {@code SECURITY DEFINER} function of V36 (ADR 0020), returns only the id. Runs in a transaction like every query.
     */
    Optional<UUID> tenantOf(byte[] tokenHash) {
        return Optional.ofNullable(jdbc.queryForObject("select members_invitation_tenant(?)", UUID.class, tokenHash));
    }

    /** The link of a token hash in the bound cooperative. */
    Optional<Invitation> findByHash(byte[] tokenHash) {
        return first(jdbc.query("select " + COLUMNS + " from member_invitation where token_hash = ?",
                (rs, n) -> invitation(rs), tokenHash));
    }

    /** The link with a row lock ({@code FOR UPDATE}) until the end of the transaction. */
    Optional<Invitation> lock(UUID id) {
        return first(jdbc.query("select " + COLUMNS + " from member_invitation where id = ? for update",
                (rs, n) -> invitation(rs), id));
    }

    private static Optional<Invitation> first(List<Invitation> rows) {
        return rows.stream().findFirst();
    }

    private static Invitation invitation(ResultSet rs) throws SQLException {
        return new Invitation(rs.getObject("id", UUID.class), rs.getObject("member_id", UUID.class),
                rs.getBytes("email_bidx"), instant(rs, "expires_at"), instant(rs, "sent_at"), instant(rs, "used_at"),
                instant(rs, "revoked_at"));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
