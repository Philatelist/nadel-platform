package app.nadel.notifications.internal.push;

import app.nadel.notifications.internal.push.PushSubscriptions.Recipient;
import app.nadel.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns the pushes of an event into {@link SendPush} jobs, one per device of each recipient (feature 021 §2.2), for
 * the event listeners of this package. While push is off it does nothing. Otherwise it binds the event's cooperative
 * ({@link TenantContext#runAs}, ADR 0007) and, in one short transaction: asks the listener for the pushes (the
 * listener reads the recipients there, through other modules' public APIs), reads the recipients' devices, deletes the
 * devices made with another key pair, and claims the throttle of a throttled code ({@link PushThrottle}) for the
 * recipients that have a device. The jobs are queued after the transaction ({@link PushQueue}) with deterministic ids
 * (cooperative, device, code, reference, attempt), so a redelivered event queues nothing new. A member with several
 * pushes of the same code and reference gets one, and of a throttled code one at most. Logs carry the cooperative,
 * the codes and counts only.
 */
@Component
class PushFanOut {

    /**
     * One push to all the devices of a member.
     *
     * @param ref   what the message is about, the same for a redelivered event (part of the job id)
     * @param month the {@code month} parameter of a code that has one ({@code YYYY-MM}), else null
     */
    record Push(UUID memberId, PushCode code, String ref, @Nullable String month) {

        Push {
            Objects.requireNonNull(memberId, "memberId");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(ref, "ref");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(PushFanOut.class);

    private final VapidKeys keys;
    private final PushSubscriptions subscriptions;
    private final PushThrottle throttle;
    private final PushQueue queue;
    private final Clock clock;
    private final TransactionTemplate transaction;

    PushFanOut(VapidKeys keys, PushSubscriptions subscriptions, PushThrottle throttle, PushQueue queue, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.keys = keys;
        this.subscriptions = subscriptions;
        this.throttle = throttle;
        this.queue = queue;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Queues the pushes for the cooperative; how many jobs were handed to JobRunr.
     *
     * @param pushes called once in a transaction of the bound cooperative; never while push is off
     */
    int send(UUID tenantId, Supplier<List<Push>> pushes) {
        if (!keys.enabled()) {
            return 0;
        }
        return TenantContext.runAs(tenantId, () -> {
            byte[] serverKeyId = keys.serverKeyId();
            Instant now = clock.instant();
            Set<PushCode> codes = EnumSet.noneOf(PushCode.class);
            List<SendPush> jobs = transaction.execute(status -> {
                List<Push> wanted = List.copyOf(new LinkedHashSet<>(pushes.get()));
                wanted.forEach(push -> codes.add(push.code()));
                return jobs(tenantId, wanted, serverKeyId, now);
            });
            int queued = 0;
            for (SendPush job : jobs == null ? List.<SendPush>of() : jobs) {
                if (queue.enqueue(job)) {
                    queued++;
                }
            }
            if (!codes.isEmpty()) {
                log.info("Push {} of cooperative {}: {} devices",
                        codes.stream().map(Enum::name).collect(Collectors.joining(",")), tenantId, queued);
            }
            return queued;
        });
    }

    private List<SendPush> jobs(UUID tenantId, List<Push> wanted, byte[] serverKeyId, Instant now) {
        if (wanted.isEmpty()) {
            return List.of();
        }
        Set<UUID> members = new LinkedHashSet<>();
        wanted.forEach(push -> members.add(push.memberId()));
        List<Recipient> found = subscriptions.devicesOf(members);
        found.stream().filter(device -> !Arrays.equals(device.serverKeyId(), serverKeyId))
                .forEach(device -> subscriptions.delete(device.id()));
        Map<UUID, List<Recipient>> devices = new LinkedHashMap<>();
        found.stream().filter(device -> Arrays.equals(device.serverKeyId(), serverKeyId))
                .forEach(device -> devices.computeIfAbsent(device.memberId(), key -> new ArrayList<>()).add(device));

        Map<PushCode, Set<UUID>> throttled = new EnumMap<>(PushCode.class);
        for (Push push : wanted) {
            if (push.code().throttle() != null && devices.containsKey(push.memberId())) {
                throttled.computeIfAbsent(push.code(), code -> new LinkedHashSet<>()).add(push.memberId());
            }
        }
        Map<PushCode, Set<UUID>> claimed = new EnumMap<>(PushCode.class);
        throttled.forEach((code, candidates) -> claimed.put(code, throttle.claim(code, candidates, now)));

        List<SendPush> jobs = new ArrayList<>();
        for (Push push : wanted) {
            // a throttled code goes once per member: the claim is taken by the member's first push of it
            Set<UUID> claimable = claimed.get(push.code());
            if (push.code().throttle() != null && (claimable == null || !claimable.remove(push.memberId()))) {
                continue;
            }
            for (Recipient device : devices.getOrDefault(push.memberId(), List.of())) {
                jobs.add(new SendPush(tenantId, device.id(), push.code().name(), push.ref(), push.month(), 0, now));
            }
        }
        return jobs;
    }
}
