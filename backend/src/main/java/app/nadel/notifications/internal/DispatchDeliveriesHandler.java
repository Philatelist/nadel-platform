package app.nadel.notifications.internal;

import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.springframework.stereotype.Component;

/**
 * Runs {@link DispatchDeliveries} on JobRunr's background job server: the lane's run of {@link DeliveryDispatcher}.
 * Public because JobRunr instantiates it through the Spring context and invokes it reflectively.
 */
@Component
public class DispatchDeliveriesHandler implements JobRequestHandler<DispatchDeliveries> {

    private final DeliveryDispatcher dispatcher;

    DispatchDeliveriesHandler(DeliveryDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void run(DispatchDeliveries job) {
        dispatcher.dispatch(job.tenantId(), Channel.valueOf(job.channel()));
    }
}
