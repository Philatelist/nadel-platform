package app.nadel.notifications.internal;

import java.util.Objects;
import java.util.UUID;
import org.jobrunr.jobs.lambdas.JobRequest;

/**
 * The JobRunr job that sends the queued deliveries of one lane: a cooperative and a channel ({@code EMAIL} or
 * {@code TELEGRAM}). Its arguments are ids and a code only, so the job table holds no cooperative data (V30). Run by
 * {@link DispatchDeliveriesHandler}.
 *
 * @param tenantId the cooperative
 * @param channel  {@code EMAIL} or {@code TELEGRAM}
 */
public record DispatchDeliveries(UUID tenantId, String channel) implements JobRequest {

    public DispatchDeliveries {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(channel, "channel");
    }

    @Override
    public Class<DispatchDeliveriesHandler> getJobRequestHandler() {
        return DispatchDeliveriesHandler.class;
    }
}
