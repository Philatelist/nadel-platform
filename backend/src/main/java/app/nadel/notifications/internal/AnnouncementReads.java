package app.nadel.notifications.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The member's side of the board's announcements (feature 021, follow-up): the announcements a member received and
 * the marks of the ones they opened (table {@code announcement_read}, V40). A member received an announcement when a
 * delivery of it to them exists ({@code delivery}, kind {@code ANNOUNCEMENT}, any channel, paper included): the same
 * notion as {@link DeliveryRows#announcementRecipients}. Every method runs in the caller's transaction of the bound
 * cooperative (RLS). The text is never logged.
 */
@Component
class AnnouncementReads {

    /** An announcement the member received, with its text, and whether they opened it. */
    record Received(UUID id, Instant sentAt, String text, boolean read) {

        @Override
        public String toString() {
            return "Received[id=" + id + ", read=" + read + "]";
        }
    }

    private static final String RECIPIENT = "exists (select 1 from delivery d where d.kind = 'ANNOUNCEMENT' "
            + "and d.announcement_id = a.id and d.member_id = ?)";

    private final JdbcTemplate jdbc;

    AnnouncementReads(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The announcements the member received that were sent at {@code since} or later, newest first. */
    List<Received> receivedBy(UUID memberId, Instant since) {
        return jdbc.query("select a.id, a.created_at, a.text, exists (select 1 from announcement_read r "
                + "where r.announcement_id = a.id and r.member_id = ?) from announcement a where " + RECIPIENT
                + " and a.created_at >= ? order by a.created_at desc, a.id desc",
                (rs, n) -> new Received(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getString(3),
                        rs.getBoolean(4)),
                memberId, memberId, since.atOffset(ZoneOffset.UTC));
    }

    /** Whether the member received the announcement; false for an unknown id or one of another cooperative. */
    boolean received(UUID announcementId, UUID memberId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from announcement a where a.id = ? and "
                + RECIPIENT + ")", Boolean.class, announcementId, memberId));
    }

    /** Marks the announcement as opened by the member at {@code now}; a mark that exists stays as it is. */
    void markRead(UUID tenantId, UUID announcementId, UUID memberId, Instant now) {
        jdbc.update("insert into announcement_read (tenant_id, announcement_id, member_id, read_at) "
                + "values (?, ?, ?, ?) on conflict do nothing", tenantId, announcementId, memberId,
                now.atOffset(ZoneOffset.UTC));
    }
}
