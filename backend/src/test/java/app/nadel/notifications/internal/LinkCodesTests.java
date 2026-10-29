package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.notifications.internal.LinkCodes.Issued;
import app.nadel.notifications.internal.LinkCodes.Purpose;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The one-time codes that link a member's Telegram (feature 017 §2.2): 128 random bits as 22 base64url characters,
 * only the SHA-256 hash stored, valid 30 minutes from the account and 90 days from paper, and usable once, even by two
 * chats racing. The clock is injected.
 */
class LinkCodesTests extends NotificationsTestSupport {

    private static final Instant START = Instant.parse("2026-10-29T10:00:00Z");

    @Autowired
    JdbcTemplate jdbc;

    private final MovableClock clock = new MovableClock(START);
    private LinkCodes codes;
    private UUID member;

    @BeforeEach
    void setUp() {
        codes = new LinkCodes(jdbc, clock);
        member = newMember(DEMO_TENANT, "codes-" + UUID.randomUUID() + "@demo.local");
    }

    @Test
    void aCodeIs128RandomBitsInBase64Url() {
        String one = issue(Purpose.ACCOUNT).code();
        String two = issue(Purpose.ACCOUNT).code();

        assertThat(one).matches("[A-Za-z0-9_-]{22}").isNotEqualTo(two);
        assertThat(java.util.Base64.getUrlDecoder().decode(one)).hasSize(16);
        assertThat(LinkCodes.wellFormed(one)).isTrue();
        assertThat(LinkCodes.wellFormed(one + "A")).isFalse();
        assertThat(LinkCodes.wellFormed("not a code at all!!!!!")).isFalse();
    }

    @Test
    void onlyTheHashIsStored() throws Exception {
        String code = issue(Purpose.ACCOUNT).code();

        byte[] stored = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select code_hash from telegram_link_code where member_id = ?", byte[].class, member));
        assertThat(stored).isEqualTo(MessageDigest.getInstance("SHA-256")
                .digest(code.getBytes(StandardCharsets.US_ASCII)));
        Integer plain = inTenant(DEMO_TENANT, sql -> sql.queryForObject("select count(*) from telegram_link_code "
                + "where position(convert_to(?, 'UTF8') in code_hash) > 0", Integer.class, code));
        assertThat(plain).isZero();
    }

    @Test
    void anAccountCodeLivesThirtyMinutes() {
        Issued issued = issue(Purpose.ACCOUNT);
        Issued expiring = issue(Purpose.ACCOUNT);
        assertThat(issued.expiresAt()).isEqualTo(START.plus(Duration.ofMinutes(30)));

        clock.set(START.plus(Duration.ofMinutes(29)));
        assertThat(holder(issued.code())).contains(member);
        assertThat(redeem(issued.code())).contains(member);

        clock.set(START.plus(Duration.ofMinutes(30)));
        assertThat(holder(expiring.code())).isEmpty();
        assertThat(redeem(expiring.code())).isEmpty();
    }

    @Test
    void aPaperCodeLivesNinetyDays() {
        Issued issued = issue(Purpose.PAPER);
        Issued expiring = issue(Purpose.PAPER);
        assertThat(issued.expiresAt()).isEqualTo(START.plus(Duration.ofDays(90)));

        clock.set(START.plus(Duration.ofDays(89)));
        assertThat(redeem(issued.code())).contains(member);

        clock.set(START.plus(Duration.ofDays(90)).plusSeconds(1));
        assertThat(redeem(expiring.code())).isEmpty();
    }

    @Test
    void aCodeWorksOnceAndAnUnknownOneNever() {
        String code = issue(Purpose.ACCOUNT).code();

        assertThat(redeem(code)).contains(member);
        assertThat(redeem(code)).isEmpty();
        assertThat(holder(code)).isEmpty();
        assertThat(redeem("AAAAAAAAAAAAAAAAAAAAAA")).isEmpty();
    }

    @Test
    void twoChatsRacingWithOneCodeLinkOnce() throws Exception {
        String code = issue(Purpose.ACCOUNT).code();
        Callable<Optional<UUID>> attempt = () -> redeem(code);
        List<Optional<UUID>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<Optional<UUID>>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(attempt));
            }
            for (Future<Optional<UUID>> future : futures) {
                results.add(future.get());
            }
        }

        assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
    }

    @Test
    void codesOfAnotherCooperativeAreInvisible() {
        String code = issue(Purpose.ACCOUNT).code();

        Optional<UUID> elsewhere = inTenant(OTHER_TENANT, sql -> codes.redeem(LinkCodes.hash(code)));
        assertThat(elsewhere).isEmpty();
        assertThat(holder(code)).contains(member);
    }

    private Issued issue(Purpose purpose) {
        return inTenant(DEMO_TENANT, sql -> codes.issue(member, purpose));
    }

    private Optional<UUID> holder(String code) {
        return inTenant(DEMO_TENANT, sql -> codes.holderOf(LinkCodes.hash(code)));
    }

    private Optional<UUID> redeem(String code) {
        return inTenant(DEMO_TENANT, sql -> codes.redeem(LinkCodes.hash(code)));
    }

    /** A clock the test moves. */
    private static final class MovableClock extends Clock {

        private volatile Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
