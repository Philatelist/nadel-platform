package app.nadel.notifications.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Drives {@link BotSupervisor#tick} every {@code nadel.telegram.supervisor-interval} (20 s), starting one interval
 * after the start. Off with {@code nadel.telegram.bot-enabled=false}: the tests call the supervisor themselves.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "nadel.telegram.bot-enabled", havingValue = "true", matchIfMissing = true)
class BotSchedule {

    private final BotSupervisor supervisor;

    BotSchedule(BotSupervisor supervisor) {
        this.supervisor = supervisor;
    }

    @Scheduled(initialDelayString = "${nadel.telegram.supervisor-interval:20s}",
            fixedDelayString = "${nadel.telegram.supervisor-interval:20s}")
    void tick() {
        supervisor.tick();
    }
}
