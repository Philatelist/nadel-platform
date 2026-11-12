package app.nadel.notifications.internal.push;

import app.nadel.identity.AccessProfile;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * «Сповіщення на цьому пристрої» (feature 021 §2.2): the signed-in member's own devices with push on. The member is
 * always the one behind the token, never a request value; the platform operator (no cooperative, no member) is refused
 * with 403 {@code PERMISSION_DENIED}. No permission is needed. Registering a device while push is off is 409
 * {@code PUSH_DISABLED}; listing and deleting work either way, so a device can always be turned off. Another member's
 * device, another cooperative's or an unknown id is 404 {@code NOT_FOUND}. Not audited: turning push on or off is the
 * member's own setting, like the browser's permission. Logs carry the cooperative and the member only.
 */
@Service
public class PushDevices {

    private static final Logger log = LoggerFactory.getLogger(PushDevices.class);

    /** What the account needs to decide whether to offer push: on or off, and the key to subscribe with. */
    public record Config(boolean enabled, @Nullable String publicKey) {
    }

    /** A device as its member sees it. */
    public record Device(UUID id, @Nullable String label, Instant createdAt, @Nullable Instant lastSentAt) {
    }

    /** A browser's subscription and the device's details, as the request brings them (already bean-validated). */
    public record Registration(String endpoint, String p256dh, String auth, @Nullable String label, String language) {

        /** Never prints the endpoint or the keys. */
        @Override
        public String toString() {
            return "Registration[language=" + language + "]";
        }
    }

    private final VapidKeys keys;
    private final PushEndpointPolicy policy;
    private final PushSubscriptions subscriptions;

    PushDevices(VapidKeys keys, PushEndpointPolicy policy, PushSubscriptions subscriptions) {
        this.keys = keys;
        this.policy = policy;
        this.subscriptions = subscriptions;
    }

    /** Whether push is on, with the public key when it is. */
    public Config config(AccessProfile me) {
        memberOf(me);
        return keys.enabled() ? new Config(true, keys.publicKeyText()) : new Config(false, null);
    }

    /** The member's devices, newest first. */
    @Transactional(readOnly = true)
    public List<Device> list(AccessProfile me) {
        return subscriptions.of(memberOf(me)).stream().map(PushDevices::toDevice).toList();
    }

    /**
     * Saves the browser's subscription for the member (see {@link PushSubscriptions#save}). 409 {@code PUSH_DISABLED}
     * while push is off; 400 {@code VALIDATION_FAILED} for an endpoint that is not an allowed push service or keys
     * that are not a P-256 point and a 16-byte secret.
     */
    @Transactional
    public Device register(AccessProfile me, Registration registration) {
        UUID memberId = memberOf(me);
        if (!keys.enabled()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PUSH_DISABLED,
                    "Push notifications are off on this server.");
        }
        List<FieldProblem> problems = new ArrayList<>();
        String endpoint = registration.endpoint().strip();
        Optional<URI> uri = policy.syntaxAllowed(endpoint);
        if (uri.isEmpty()) {
            problems.add(new FieldProblem("endpoint", ProblemCodes.INVALID));
        }
        byte[] p256dh = P256.fromBase64Url(registration.p256dh())
                .filter(point -> P256.publicKey(point).isPresent()).orElse(null);
        if (p256dh == null) {
            problems.add(new FieldProblem("keys.p256dh", ProblemCodes.INVALID));
        }
        byte[] auth = P256.fromBase64Url(registration.auth()).filter(secret -> secret.length == 16).orElse(null);
        if (auth == null) {
            problems.add(new FieldProblem("keys.auth", ProblemCodes.INVALID));
        }
        if (!problems.isEmpty() || p256dh == null || auth == null) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", problems);
        }
        String label = registration.label() == null || registration.label().isBlank() ? null
                : registration.label().strip();
        PushSubscriptions.Device saved = subscriptions.save(memberId, endpoint, p256dh, auth, label,
                registration.language(), keys.serverKeyId());
        log.info("Push device saved for member {} of cooperative {}", memberId, me.tenantId());
        return toDevice(saved);
    }

    /**
     * How many of the members would get a push now: those with at least one device made with the current key pair; 0
     * while push is off. For the announcement form (feature 021 follow-up); runs in the caller's transaction of the
     * bound cooperative.
     */
    public int membersWithPush(Collection<UUID> memberIds) {
        if (!keys.enabled()) {
            return 0;
        }
        return subscriptions.membersWithDevices(memberIds, keys.serverKeyId());
    }

    /** Deletes the member's device; 404 {@code NOT_FOUND} when the member has none with the id. */
    @Transactional
    public void delete(AccessProfile me, UUID id) {
        UUID memberId = memberOf(me);
        if (!subscriptions.delete(memberId, id)) {
            throw ApiProblemException.notFound();
        }
        log.info("Push device deleted by member {} of cooperative {}", memberId, me.tenantId());
    }

    private static Device toDevice(PushSubscriptions.Device device) {
        return new Device(device.id(), device.label(), device.createdAt(), device.lastSentAt());
    }

    private static UUID memberOf(AccessProfile me) {
        if (me.platformOperator() || me.tenantId() == null || me.memberId() == null) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, ProblemCodes.PERMISSION_DENIED,
                    "Only a member of a cooperative has push devices.");
        }
        return me.memberId();
    }
}
