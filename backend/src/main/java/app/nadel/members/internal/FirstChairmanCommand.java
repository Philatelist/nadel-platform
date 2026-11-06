package app.nadel.members.internal;

import app.nadel.problem.ApiProblemException;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one-shot command {@code --nadel.command=first-chairman} (feature 020; {@code infra/stand/first-chairman.sh} runs
 * it with the profile {@code command}: no web server, no job server, no bot polling). Reads
 * {@code nadel.first-chairman.cooperative}, {@code .email}, {@code .last-name}, {@code .first-name} and {@code .resend},
 * runs {@link FirstChairman}, waits (at most {@link #OUTBOX_WAIT}) until the audit events it published are processed,
 * and stops the application with the exit code: {@value #OK} done (or nothing to do), {@value #FAILED} a step failed,
 * {@value #INVALID} invalid arguments.
 * <p>
 * It runs before every other runner ({@link Ordered#HIGHEST_PRECEDENCE}) and exits the JVM itself, so the demo runners
 * of the {@code dev} profile never run in a command. Errors go to standard error as a code or a class name only:
 * never a message that could hold the e-mail or a name, never the link.
 */
@Component
@ConditionalOnProperty(name = "nadel.command", havingValue = "first-chairman")
@Order(Ordered.HIGHEST_PRECEDENCE)
class FirstChairmanCommand implements ApplicationRunner {

    static final int OK = 0;
    static final int FAILED = 1;
    static final int INVALID = 2;
    static final Duration OUTBOX_WAIT = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(FirstChairmanCommand.class);
    private static final String PREFIX = "nadel.first-chairman.";

    private final FirstChairman work;
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate reading;
    private final IntConsumer exit;

    @Autowired
    FirstChairmanCommand(FirstChairman work, Environment environment, JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager, ConfigurableApplicationContext context) {
        this(work, environment, jdbc, transactionManager,
                code -> System.exit(SpringApplication.exit(context, () -> code)));
    }

    /** For the tests: {@code exit} receives the exit code instead of stopping the JVM. */
    FirstChairmanCommand(FirstChairman work, Environment environment, JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager, IntConsumer exit) {
        this.work = work;
        this.environment = environment;
        this.jdbc = jdbc;
        this.reading = new TransactionTemplate(transactionManager);
        this.reading.setReadOnly(true);
        this.exit = exit;
    }

    @Override
    public void run(ApplicationArguments args) {
        exit.accept(execute(System.out, System.err));
    }

    /** Runs the command and answers the exit code; prints to {@code out} and {@code err}, never throws. */
    int execute(PrintStream out, PrintStream err) {
        Instant started = Instant.now();
        int code;
        try {
            work.run(arguments(), out);
            code = OK;
        } catch (FirstChairman.Failure e) {
            log.warn("First chairman command failed: {}", e.code());
            err.println("Error " + e.code() + ": " + e.getMessage());
            code = e.invalidArguments() ? INVALID : FAILED;
        } catch (ApiProblemException e) {
            log.warn("First chairman command failed: {}", e.code());
            err.println("Error " + e.code() + (e.failureCode() == null ? "" : " (" + e.failureCode() + ")"));
            code = FAILED;
        } catch (RuntimeException e) {
            log.error("First chairman command failed: {}", e.getClass().getName());
            err.println("Error: " + e.getClass().getSimpleName() + ". See the application log.");
            code = FAILED;
        }
        awaitOutbox(started);
        return code;
    }

    private FirstChairman.Arguments arguments() {
        return new FirstChairman.Arguments(
                environment.getProperty(PREFIX + "cooperative", ""),
                environment.getProperty(PREFIX + "email", ""),
                environment.getProperty(PREFIX + "last-name", ""),
                environment.getProperty(PREFIX + "first-name", ""),
                environment.getProperty(PREFIX + "resend", Boolean.class, false));
    }

    /**
     * The audit entries are written by asynchronous listeners after the commit (the Spring Modulith outbox,
     * {@code event_publication}); the JVM must not stop before they are. Waits until no publication made since the
     * start is incomplete (a count only, never the payload), or {@link #OUTBOX_WAIT}; then an unfinished one stays in
     * the outbox and the backend's next start resubmits it.
     */
    private void awaitOutbox(Instant since) {
        Instant deadline = Instant.now().plus(OUTBOX_WAIT);
        try {
            while (pendingSince(since) > 0) {
                if (Instant.now().isAfter(deadline)) {
                    log.warn("First chairman command: audit events still pending after {}", OUTBOX_WAIT);
                    return;
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("First chairman command: the outbox could not be read ({})", e.getClass().getName());
        }
    }

    private int pendingSince(Instant since) {
        Integer count = reading.execute(status -> jdbc.queryForObject("select count(*) from event_publication "
                + "where completion_date is null and publication_date >= ?", Integer.class,
                since.atOffset(ZoneOffset.UTC)));
        return count == null ? 0 : count;
    }
}
