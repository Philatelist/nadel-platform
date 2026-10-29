package app.nadel.notifications.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The delivery register and queue (table {@code delivery}, V29): one row per message to one member through one channel.
 * A row holds ids, codes and the month only, never an address, a chat or a text. Every method runs in the caller's
 * transaction of the bound cooperative (RLS); only the sending state of a row ever changes (the table's guard).
 */
@Component
class DeliveryRows {

    /** The statuses. */
    static final String QUEUED = "QUEUED";
    static final String SENT = "SENT";
    static final String FAILED = "FAILED";
    static final String TO_PRINT = "TO_PRINT";
    static final String PRINTED = "PRINTED";

    /** The kinds of a payment message: a payment received, a payment reversed. */
    static final String PAYMENT = "PAYMENT";
    static final String REVERSAL = "REVERSAL";

    /** The kind of an announcement's delivery. */
    static final String ANNOUNCEMENT = "ANNOUNCEMENT";

    /**
     * One row as the sender reads it: the invoice of an invoice delivery, the payment of a payment message, the
     * announcement of an announcement's delivery.
     */
    record Queued(UUID id, String kind, @Nullable UUID invoiceId, @Nullable UUID paymentId,
            @Nullable UUID announcementId, UUID memberId, Channel channel, int attempts) {
    }

    /** One row of the delivery screen. */
    record Listed(UUID id, String kind, @Nullable YearMonth month, @Nullable UUID invoiceId,
            @Nullable UUID announcementId, UUID memberId, Channel channel, String status,
            @Nullable String failureCode, @Nullable String note, int attempts, @Nullable Instant sentAt) {
    }

    /** The deliveries of one announcement now: sent, failed, still queued, and on paper. */
    record AnnouncementCounts(int sent, int failed, int queued, int paper) {

        static final AnnouncementCounts NONE = new AnnouncementCounts(0, 0, 0, 0);
    }

    /** How many rows have a channel and a status. */
    record Count(Channel channel, String status, int count) {
    }

    /** The filters of the delivery screen, and the announcement of its detail page; null is "any". */
    record Filter(@Nullable YearMonth month, @Nullable String kind, @Nullable String status,
            @Nullable Channel channel, @Nullable UUID announcementId) {

        Filter(@Nullable YearMonth month, @Nullable String kind, @Nullable String status, @Nullable Channel channel) {
            this(month, kind, status, channel, null);
        }
    }

    /** A row of the retry: its channel and status. */
    record State(Channel channel, String status) {
    }

    /** An invoice delivery's key: the version, the member and the channel. */
    record InvoiceKey(UUID invoiceId, UUID memberId, Channel channel) {
    }

    private final JdbcTemplate jdbc;

    DeliveryRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The invoice deliveries that exist for the given invoice versions. */
    Set<InvoiceKey> invoiceKeys(Collection<UUID> invoiceIds) {
        if (invoiceIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.query("select invoice_id, member_id, channel from delivery "
                + "where kind = 'INVOICE' and invoice_id = any(?::uuid[])",
                (rs, n) -> new InvoiceKey(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        Channel.valueOf(rs.getString(3))), array(invoiceIds)));
    }

    /**
     * Creates an invoice delivery unless the same version already goes to the member through the channel; true when
     * it was created. E-mail and Telegram rows are queued due at {@code now}, paper rows wait to be printed.
     */
    boolean insertInvoice(UUID tenantId, UUID invoiceId, int version, YearMonth month, UUID memberId,
            Channel channel, @Nullable String note, Instant now) {
        boolean paper = channel == Channel.PAPER;
        return jdbc.update("insert into delivery (tenant_id, kind, invoice_id, invoice_version, billing_month, "
                + "member_id, channel, note, status, next_attempt_at, created_at) "
                + "values (?, 'INVOICE', ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing", tenantId, invoiceId,
                version, month.atDay(1), memberId, channel.name(), note, paper ? TO_PRINT : QUEUED,
                paper ? null : utc(now), utc(now)) == 1;
    }

    /**
     * Creates a payment or reversal message ({@link #PAYMENT}, {@link #REVERSAL}) of the payment to the member through
     * an e-mail or Telegram channel, queued due at {@code now}, unless it exists already (the unique key
     * {@code (tenant_id, payment_id, kind, member_id, channel)}); true when it was created. The month is the payment's
     * billing month, so the delivery screen lists it with that month.
     */
    boolean insertPayment(UUID tenantId, String kind, UUID paymentId, YearMonth month, UUID memberId,
            Channel channel, Instant now) {
        if (!PAYMENT.equals(kind) && !REVERSAL.equals(kind)) {
            throw new IllegalArgumentException("Not a payment message: " + kind);
        }
        if (channel == Channel.PAPER) {
            throw new IllegalArgumentException("Payment messages are never printed");
        }
        return jdbc.update("insert into delivery (tenant_id, kind, payment_id, billing_month, member_id, channel, "
                + "status, next_attempt_at, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing",
                tenantId, kind, paymentId, month.atDay(1), memberId, channel.name(), QUEUED, utc(now), utc(now)) == 1;
    }

    /**
     * Creates the delivery of an announcement to the member through the channel, unless it exists already (the unique
     * key {@code (tenant_id, announcement_id, member_id, channel)}); true when it was created. E-mail and Telegram rows
     * are queued due at {@code now}; a paper row waits as {@code TO_PRINT}: the board informs the member in person.
     * An announcement has no billing month.
     */
    boolean insertAnnouncement(UUID tenantId, UUID announcementId, UUID memberId, Channel channel,
            @Nullable String note, Instant now) {
        boolean paper = channel == Channel.PAPER;
        return jdbc.update("insert into delivery (tenant_id, kind, announcement_id, member_id, channel, note, status, "
                + "next_attempt_at, created_at) values (?, 'ANNOUNCEMENT', ?, ?, ?, ?, ?, ?, ?) on conflict do nothing",
                tenantId, announcementId, memberId, channel.name(), note, paper ? TO_PRINT : QUEUED,
                paper ? null : utc(now), utc(now)) == 1;
    }

    /** The counts of each given announcement's deliveries; an announcement without any is absent. */
    Map<UUID, AnnouncementCounts> announcementCounts(Collection<UUID> announcementIds) {
        Map<UUID, AnnouncementCounts> counts = new HashMap<>();
        if (announcementIds.isEmpty()) {
            return counts;
        }
        jdbc.query("select announcement_id, count(*) filter (where status = 'SENT'), "
                + "count(*) filter (where status = 'FAILED'), count(*) filter (where status = 'QUEUED'), "
                + "count(*) filter (where channel = 'PAPER') from delivery "
                + "where kind = 'ANNOUNCEMENT' and announcement_id = any(?::uuid[]) group by announcement_id",
                (ResultSet rs) -> {
                    counts.put(rs.getObject(1, UUID.class), new AnnouncementCounts(rs.getInt(2), rs.getInt(3),
                            rs.getInt(4), rs.getInt(5)));
                }, array(announcementIds));
        return counts;
    }

    /**
     * The queued rows of the channel and kinds due at {@code now} (no next attempt time, or one not after now), the longest
     * waiting first, without the ones in {@code skip}.
     */
    List<Queued> due(Channel channel, Instant now, int limit, Collection<UUID> skip, Collection<String> kinds) {
        return jdbc.query("select id, kind, invoice_id, payment_id, announcement_id, member_id, channel, attempts "
                + "from delivery where status = 'QUEUED' and channel = ? and kind = any(?::text[]) "
                + "and (next_attempt_at is null or next_attempt_at <= ?) "
                + "and not (id = any(?::uuid[])) order by next_attempt_at nulls first, created_at, id limit ?",
                (rs, n) -> new Queued(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class),
                        Channel.valueOf(rs.getString(7)), rs.getInt(8)),
                channel.name(), texts(kinds), utc(now), array(skip), limit);
    }

    /** The earliest time a queued row of the channel and kinds is due; empty when none is queued. */
    Optional<Instant> nextDue(Channel channel, Instant now, Collection<String> kinds) {
        List<Instant> found = jdbc.query("select min(coalesce(next_attempt_at, ?)) from delivery "
                + "where status = 'QUEUED' and channel = ? and kind = any(?::text[])", (rs, n) -> instant(rs, 1),
                utc(now), channel.name(), texts(kinds));
        return found.stream().filter(Objects::nonNull).findFirst();
    }

    /** Records an accepted message: SENT now, the attempt counted. False when the row changed meanwhile. */
    boolean sent(UUID id, int attempts, Instant now) {
        return jdbc.update("update delivery set status = 'SENT', failure_code = null, attempts = ?, "
                + "next_attempt_at = null, sent_at = ? where id = ? and status = 'QUEUED' and attempts = ?",
                attempts + 1, utc(now), id, attempts) == 1;
    }

    /** Records a failed last attempt: FAILED with the code, the attempt counted. */
    boolean failed(UUID id, int attempts, String code) {
        return jdbc.update("update delivery set status = 'FAILED', failure_code = ?, attempts = ?, "
                + "next_attempt_at = null where id = ? and status = 'QUEUED' and attempts = ?", code, attempts + 1, id,
                attempts) == 1;
    }

    /** Records a failed attempt that will be tried again at {@code next}; the code says why it failed. */
    boolean retryLater(UUID id, int attempts, String code, Instant next) {
        return jdbc.update("update delivery set failure_code = ?, attempts = ?, next_attempt_at = ? "
                + "where id = ? and status = 'QUEUED' and attempts = ?", code, attempts + 1, utc(next), id,
                attempts) == 1;
    }

    /** Moves a row's next attempt to {@code next} without counting an attempt (Telegram asked to wait). */
    boolean postpone(UUID id, int attempts, Instant next) {
        return jdbc.update("update delivery set next_attempt_at = ? where id = ? and status = 'QUEUED' "
                + "and attempts = ?", utc(next), id, attempts) == 1;
    }

    /** The channel and status of a row, locked for the retry; empty for an unknown id. */
    Optional<State> lock(UUID id) {
        return jdbc.query("select channel, status from delivery where id = ? for update",
                (rs, n) -> new State(Channel.valueOf(rs.getString(1)), rs.getString(2)), id).stream().findFirst();
    }

    /** Queues a row again from its first attempt («Надіслати ще раз»). */
    void requeue(UUID id, Instant now) {
        jdbc.update("update delivery set status = 'QUEUED', failure_code = null, attempts = 0, "
                + "resends = resends + 1, next_attempt_at = ? where id = ?", utc(now), id);
    }

    /** The month's invoice paper copies waiting to be printed become printed; how many. */
    int printed(YearMonth month) {
        return jdbc.update("update delivery set status = 'PRINTED' where kind = 'INVOICE' and billing_month = ? "
                + "and status = 'TO_PRINT'", month.atDay(1));
    }

    /** A page of rows, newest first. */
    List<Listed> list(Filter filter, int page, int size) {
        List<Object> params = new ArrayList<>();
        String where = where(filter, params, true);
        params.add(size);
        params.add((long) page * size);
        return jdbc.query("select id, kind, billing_month, invoice_id, announcement_id, member_id, channel, status, "
                + "failure_code, note, attempts, sent_at from delivery" + where
                + " order by created_at desc, id limit ? offset ?",
                (rs, n) -> {
                    LocalDate month = rs.getObject(3, LocalDate.class);
                    return new Listed(rs.getObject(1, UUID.class), rs.getString(2),
                            month == null ? null : YearMonth.from(month), rs.getObject(4, UUID.class),
                            rs.getObject(5, UUID.class), rs.getObject(6, UUID.class),
                            Channel.valueOf(rs.getString(7)), rs.getString(8), rs.getString(9), rs.getString(10),
                            rs.getInt(11), instant(rs, 12));
                }, params.toArray());
    }

    /** How many rows match. */
    long count(Filter filter) {
        List<Object> params = new ArrayList<>();
        Long count = jdbc.queryForObject("select count(*) from delivery" + where(filter, params, true), Long.class,
                params.toArray());
        return count == null ? 0 : count;
    }

    /** The rows per channel and status for the month and kind of the filter (its status and channel left out). */
    List<Count> summary(Filter filter) {
        List<Object> params = new ArrayList<>();
        return jdbc.query("select channel, status, count(*) from delivery" + where(filter, params, false)
                + " group by channel, status order by channel, status",
                (rs, n) -> new Count(Channel.valueOf(rs.getString(1)), rs.getString(2), rs.getInt(3)),
                params.toArray());
    }

    /** The WHERE clause of the filter; values only as parameters. */
    private static String where(Filter filter, List<Object> params, boolean all) {
        List<String> conditions = new ArrayList<>();
        if (filter.month() != null) {
            conditions.add("billing_month = ?");
            params.add(filter.month().atDay(1));
        }
        if (filter.kind() != null) {
            conditions.add("kind = ?");
            params.add(filter.kind());
        }
        if (filter.announcementId() != null) {
            conditions.add("announcement_id = ?");
            params.add(filter.announcementId());
        }
        if (all && filter.status() != null) {
            conditions.add("status = ?");
            params.add(filter.status());
        }
        if (all && filter.channel() != null) {
            conditions.add("channel = ?");
            params.add(filter.channel().name());
        }
        return conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
    }

    private static @Nullable Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp time = rs.getTimestamp(column);
        return time == null ? null : time.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String texts(Collection<String> codes) {
        return "{" + String.join(",", codes) + "}";
    }

    private static String array(Collection<UUID> ids) {
        return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
    }
}
