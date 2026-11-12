package app.nadel.notifications.internal.push;

import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.springframework.stereotype.Component;

/**
 * Runs {@link SendPush} on JobRunr's background job server: one attempt of {@link PushSender}. Public because JobRunr
 * instantiates it through the Spring context and invokes it reflectively.
 */
@Component
public class SendPushHandler implements JobRequestHandler<SendPush> {

    private final PushSender sender;

    SendPushHandler(PushSender sender) {
        this.sender = sender;
    }

    @Override
    public void run(SendPush job) {
        sender.send(job);
    }
}
