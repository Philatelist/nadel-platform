package app.nadel.notifications.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Drives {@link BotSupervisor#tick} every {@code nadel.telegram.supervisor-interval} (20 s), starting one interval
 * after the start, and the delivery sweep {@link DeliveryDispatcher#sweep} every {@code nadel.delivery.sweep-interval}
 * (1 min), which wakes every send lane with due deliveries. Off with {@code nadel.telegram.bot-enabled=false}: the
 * tests call the supervisor and the dispatcher themselves.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "nadel.telegram.bot-enabled", havingValue = "true", matchIfMissing = true)
class BotSchedule {

    private final BotSupervisor supervisor;
    private final DeliveryDispatcher dispatcher;

    BotSchedule(BotSupervisor supervisor, DeliveryDispatcher dispatcher) {
        this.supervisor = supervisor;
        this.dispatcher = dispatcher;
    }

    @Scheduled(initialDelayString = "${nadel.telegram.supervisor-interval:20s}",
            fixedDelayString = "${nadel.telegram.supervisor-interval:20s}")
    void tick() {
        supervisor.tick();
    }

    @Scheduled(initialDelayString = "${nadel.delivery.sweep-interval:60s}",
            fixedDelayString = "${nadel.delivery.sweep-interval:60s}")
    void sweepDeliveries() {
        dispatcher.sweep();
    }
}
