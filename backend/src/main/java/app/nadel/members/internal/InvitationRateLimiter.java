package app.nadel.members.internal;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The limit of the public invitation endpoints (feature 020, owner decision on PR 71): at most
 * {@code nadel.invitations.rate-limit.max-requests} requests (default 5) within
 * {@code nadel.invitations.rate-limit.window} (default 15 minutes) from one client address, and for the e-mail of one
 * invitation, counted for each endpoint ({@link Endpoint}) apart. A request over a limit is 429
 * {@code TOO_MANY_REQUESTS} and is not counted, so a counter frees itself one window after its oldest admitted request.
 * <p>
 * A sliding log in the memory of this instance: per key the times of the admitted requests within the window, at most
 * {@code max-requests} of them. At most {@code nadel.invitations.rate-limit.max-keys} keys are kept; past that the key
 * used least recently is dropped. With several backend instances each counts on its own (ADR 0020). The keys hold the
 * client address and the cooperative with the e-mail's blind index, never an e-mail or a token, and are never logged.
 */
@Component
class InvitationRateLimiter {

    /** The endpoint a request is counted for. */
    enum Endpoint {
        INSPECT, ACCEPT
    }

    private final int maxRequests;
    private final Duration window;
    private final Clock clock;
    private final Map<String, ArrayDeque<Instant>> admitted;

    InvitationRateLimiter(@Value("${nadel.invitations.rate-limit.max-requests}") int maxRequests,
            @Value("${nadel.invitations.rate-limit.window}") Duration window,
            @Value("${nadel.invitations.rate-limit.max-keys}") int maxKeys, Clock clock) {
        if (maxRequests < 1 || maxKeys < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("nadel.invitations.rate-limit needs positive values");
        }
        this.maxRequests = maxRequests;
        this.window = window;
        this.clock = clock;
        this.admitted = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ArrayDeque<Instant>> eldest) {
                return size() > maxKeys;
            }
        };
    }

    /** Counts a request from the client address; 429 {@code TOO_MANY_REQUESTS} over the limit. */
    void acquireForAddress(Endpoint endpoint, String address) {
        acquire("address|" + endpoint + "|" + address);
    }

    /**
     * Counts a request for an invitation e-mail, named by {@code emailKey} (the cooperative and the e-mail's blind
     * index); 429 {@code TOO_MANY_REQUESTS} over the limit.
     */
    void acquireForEmail(Endpoint endpoint, String emailKey) {
        acquire("email|" + endpoint + "|" + emailKey);
    }

    private synchronized void acquire(String key) {
        Instant now = clock.instant();
        Instant oldestCounted = now.minus(window);
        ArrayDeque<Instant> times = admitted.computeIfAbsent(key, k -> new ArrayDeque<>(maxRequests));
        while (!times.isEmpty() && !times.peekFirst().isAfter(oldestCounted)) {
            times.pollFirst();
        }
        if (times.size() >= maxRequests) {
            throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, ProblemCodes.TOO_MANY_REQUESTS,
                    "Too many requests; try again later.");
        }
        times.addLast(now);
    }
}
