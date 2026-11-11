package app.nadel.members.internal;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The invitation limits (feature 020, owner decisions on PR 71). Only two things are counted, never a page load:
 * <ul>
 *   <li><b>sent invitations</b> (an invitation or a resend whose letter went out): at most
 *       {@code nadel.invitations.rate-limit.max-requests} (default 5) for one member and at most
 *       {@code nadel.invitations.rate-limit.max-sends-per-address} (default 30) from one client address within
 *       {@code nadel.invitations.rate-limit.window} (default 15 minutes); one more is 429 {@code TOO_MANY_REQUESTS}
 *       before anything is sent ({@link #ensureSendAllowed}, then {@link #recordSend} once the letter is out);</li>
 *   <li><b>failed links</b> (the public endpoints refused the link as 404 {@code INVITATION_INVALID}: unknown, used,
 *       replaced or expired): at most {@code max-requests} from one client address within the window; past that such
 *       a link from that address is 429 {@code TOO_MANY_REQUESTS} instead ({@link #countFailedLink}). A usable link
 *       is never counted and never refused, from any address: the token is 256 random bits, so guessing is not the
 *       risk; the limit only stops floods of wrong or expired links.</li>
 * </ul>
 * A refused request is not counted, so a counter frees itself one window after its oldest counted event. The check
 * and the record of a send are two steps, so two sends at the very same moment may pass one over the limit.
 * <p>
 * A sliding log in the memory of this instance: per key the times of the counted events within the window. At most
 * {@code nadel.invitations.rate-limit.max-keys} keys are kept; past that the key used least recently is dropped. With
 * several backend instances each counts on its own (ADR 0020). The keys hold the client address, or the cooperative
 * and the member id, never an e-mail or a token, and are never logged.
 */
@Component
class InvitationRateLimiter {

    private final int maxRequests;
    private final int maxSendsPerAddress;
    private final Duration window;
    private final Clock clock;
    private final Map<String, ArrayDeque<Instant>> counted;

    InvitationRateLimiter(@Value("${nadel.invitations.rate-limit.max-requests}") int maxRequests,
            @Value("${nadel.invitations.rate-limit.max-sends-per-address}") int maxSendsPerAddress,
            @Value("${nadel.invitations.rate-limit.window}") Duration window,
            @Value("${nadel.invitations.rate-limit.max-keys}") int maxKeys, Clock clock) {
        if (maxRequests < 1 || maxSendsPerAddress < 1 || maxKeys < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("nadel.invitations.rate-limit needs positive values");
        }
        this.maxRequests = maxRequests;
        this.maxSendsPerAddress = maxSendsPerAddress;
        this.window = window;
        this.clock = clock;
        this.counted = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ArrayDeque<Instant>> eldest) {
                return size() > maxKeys;
            }
        };
    }

    /**
     * The address of the current request ({@code request.getRemoteAddr()}, which behind the proxy is the client through
     * {@code server.forward-headers-strategy}). Read per call: the command profile runs without a web server.
     */
    static String clientAddress() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest()
                .getRemoteAddr();
    }

    /**
     * Counts a link the public endpoints refused as {@code INVITATION_INVALID} for the address; once the address used
     * up its failed links within the window, 429 {@code TOO_MANY_REQUESTS} instead, and that refusal is not counted.
     */
    synchronized void countFailedLink(String address) {
        String key = failedKey(address);
        if (count(key) >= maxRequests) {
            throw tooMany();
        }
        record(key);
    }

    /** 429 {@code TOO_MANY_REQUESTS} when the member, or the address, used up its sent invitations within the window. */
    synchronized void ensureSendAllowed(String address, UUID tenantId, UUID memberId) {
        if (count(memberKey(tenantId, memberId)) >= maxRequests
                || count(sendAddressKey(address)) >= maxSendsPerAddress) {
            throw tooMany();
        }
    }

    /** Counts a sent invitation for the member and the address. */
    synchronized void recordSend(String address, UUID tenantId, UUID memberId) {
        record(memberKey(tenantId, memberId));
        record(sendAddressKey(address));
    }

    private static String failedKey(String address) {
        return "failed|" + address;
    }

    private static String sendAddressKey(String address) {
        return "sent|" + address;
    }

    private static String memberKey(UUID tenantId, UUID memberId) {
        return "member|" + tenantId + "|" + memberId;
    }

    /** The events of the key within the window, the older ones dropped; a key never counted is not created. */
    private int count(String key) {
        ArrayDeque<Instant> times = counted.get(key);
        if (times == null) {
            return 0;
        }
        Instant oldestCounted = clock.instant().minus(window);
        while (!times.isEmpty() && !times.peekFirst().isAfter(oldestCounted)) {
            times.pollFirst();
        }
        return times.size();
    }

    private void record(String key) {
        count(key);
        counted.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(clock.instant());
    }

    private static ApiProblemException tooMany() {
        return new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, ProblemCodes.TOO_MANY_REQUESTS,
                "Too many requests; try again later.");
    }
}
