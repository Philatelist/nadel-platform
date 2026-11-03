package app.nadel.notifications.internal;

import app.nadel.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time codes that link a member's Telegram (feature 017 §2.2), table {@code telegram_link_code}. A code is 128
 * random bits ({@link SecureRandom}) written as base64url without padding (22 characters, fit for a Telegram deep
 * link's {@code start} parameter); only its SHA-256 hash is stored, so the table never holds a usable code. A code of
 * the account ({@link Purpose#ACCOUNT}) is valid 30 minutes, one printed on a paper invoice ({@link Purpose#PAPER}) 90
 * days. It works once: {@link #redeem} marks it used by a conditional update, so two chats racing with the same code
 * cannot both win.
 * <p>
 * Every method runs in a transaction of the bound cooperative (RLS). The time is the injected {@link Clock}'s, so
 * expiry is testable. Never log a code.
 */
@Component
class LinkCodes {

    /** Where a code is handed to the member, and so how long it lives. */
    enum Purpose {
        ACCOUNT(Duration.ofMinutes(30)),
        PAPER(Duration.ofDays(90));

        private final Duration validity;

        Purpose(Duration validity) {
            this.validity = validity;
        }

        Duration validity() {
            return validity;
        }
    }

    /** A new code and when it stops working. Never log the code. */
    record Issued(String code, Instant expiresAt) {

        @Override
        public String toString() {
            return "Issued[expiresAt=" + expiresAt + "]";
        }
    }

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final int BYTES = 16;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    LinkCodes(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** A new code of the member, valid for the purpose's time from now. */
    @Transactional
    Issued issue(UUID memberId, Purpose purpose) {
        byte[] raw = new byte[BYTES];
        random.nextBytes(raw);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(purpose.validity());
        jdbc.update("insert into telegram_link_code (tenant_id, code_hash, member_id, purpose, created_at, expires_at) "
                + "values (?, ?, ?, ?, ?, ?)", boundTenant(), hash(code), memberId, purpose.name(),
                utc(now), utc(expiresAt));
        return new Issued(code, expiresAt);
    }

    /** The member a code links, while it is unused and not expired; nothing is changed. */
    @Transactional(readOnly = true)
    Optional<UUID> holderOf(byte[] codeHash) {
        List<UUID> members = jdbc.queryForList("select member_id from telegram_link_code "
                + "where code_hash = ? and used_at is null and expires_at > ?", UUID.class, codeHash,
                utc(clock.instant()));
        return members.stream().findFirst();
    }

    /**
     * Uses the code up: the member it links, only for the one caller that finds it unused and not expired; empty for
     * an unknown, used or expired code.
     */
    @Transactional
    Optional<UUID> redeem(byte[] codeHash) {
        OffsetDateTime now = utc(clock.instant());
        List<UUID> members = jdbc.queryForList("update telegram_link_code set used_at = ? "
                + "where code_hash = ? and used_at is null and expires_at > ? returning member_id", UUID.class, now,
                codeHash, now);
        return members.stream().findFirst();
    }

    /** Whether the text has the shape of a code (so a hash is computed only for something that could be one). */
    static boolean wellFormed(String text) {
        return FORMAT.matcher(text).matches();
    }

    /** The SHA-256 hash of a code, as stored. */
    static byte[] hash(String code) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static UUID boundTenant() {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("No cooperative is bound"));
    }
}
