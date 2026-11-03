package app.nadel.notifications.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The board's announcements (table {@code announcement}, V29, append-only): the text as entered (user content, not
 * sealed), the audience ({@code {"streets": [...]}}, empty for every active member), the channels chosen, the author
 * and the time. Every method runs in the caller's transaction of the bound cooperative (RLS). The text is never logged.
 */
@Component
class AnnouncementRows {

    /** The streets of the audience as a text array (empty for every member). */
    private static final String STREETS = "array(select jsonb_array_elements_text(coalesce(audience -> 'streets', "
            + "'[]'::jsonb)))";

    /** One announcement; {@code text} only when it was asked for. */
    record Stored(UUID id, @Nullable String text, List<String> streets, List<Channel> channels,
            @Nullable UUID createdBy, Instant createdAt) {

        @Override
        public String toString() {
            return "Stored[id=" + id + "]";
        }
    }

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    AnnouncementRows(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Saves an announcement; its id. */
    UUID insert(UUID tenantId, String text, List<String> streets, Collection<Channel> channels,
            @Nullable UUID createdBy, Instant now) {
        String audience = json.writeValueAsString(Map.of("streets", streets));
        String codes = "{" + String.join(",", channels.stream().map(Channel::name).toList()) + "}";
        return jdbc.queryForObject("insert into announcement (tenant_id, text, audience, channels, created_by, "
                + "created_at) values (?, ?, ?::jsonb, ?::text[], ?, ?) returning id", UUID.class, tenantId, text,
                audience, codes, createdBy, now.atOffset(ZoneOffset.UTC));
    }

    /** A page of announcements without their text, newest first. */
    List<Stored> page(int page, int size) {
        return jdbc.query("select id, null, " + STREETS + ", channels, created_by, created_at from announcement "
                + "order by created_at desc, id limit ? offset ?", AnnouncementRows::stored, size, (long) page * size);
    }

    /** How many announcements the cooperative has. */
    long count() {
        Long count = jdbc.queryForObject("select count(*) from announcement", Long.class);
        return count == null ? 0 : count;
    }

    /** The announcement with its text; empty for an unknown id or one of another cooperative. */
    Optional<Stored> find(UUID id) {
        return jdbc.query("select id, text, " + STREETS + ", channels, created_by, created_at from announcement "
                + "where id = ?", AnnouncementRows::stored, id).stream().findFirst();
    }

    private static Stored stored(ResultSet rs, int row) throws SQLException {
        return new Stored(rs.getObject(1, UUID.class), rs.getString(2), texts(rs.getArray(3)),
                texts(rs.getArray(4)).stream().map(Channel::valueOf).toList(), rs.getObject(5, UUID.class),
                rs.getTimestamp(6).toInstant());
    }

    private static List<String> texts(@Nullable Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return new ArrayList<>(Arrays.asList((String[]) array.getArray()));
    }
}
