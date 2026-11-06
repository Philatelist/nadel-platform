package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import app.nadel.problem.ApiProblemException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The exit paths of the first-chairman runner (feature 020, technical considerations 2.1) without a database: the
 * work and the outbox are stand-ins, so the wait for the audit events can fail or be interrupted. Whatever happens
 * after the work, the exit code is the work's; the outbox only delays the exit and never turns a success into a
 * failure. {@link FirstChairmanCommandTests} runs the runner against the real context.
 */
class FirstChairmanCommandExitTests {

    private final FirstChairman work = mock(FirstChairman.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final MockEnvironment environment = new MockEnvironment().withProperty("nadel.command", "first-chairman");
    private final List<Integer> exitCodes = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    private final PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger().addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger().detachAppender(logs);
        logs.stop();
        Thread.interrupted(); // never leak an interrupt into the next test
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(FirstChairmanCommand.class);
    }

    private FirstChairmanCommand command(JdbcTemplate outbox) {
        return new FirstChairmanCommand(work, environment, outbox, transactionManager, exitCodes::add);
    }

    /** An outbox that answers {@code pending} incomplete publications on every count, and counts the reads. */
    private static JdbcTemplate outboxWith(int pending, AtomicInteger reads) {
        return new JdbcTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                reads.incrementAndGet();
                return (T) Integer.valueOf(pending);
            }
        };
    }

    private List<String> warnings() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void anOutboxThatCannotBeReadIsLoggedAndTheWorkStillSucceeds() {
        JdbcTemplate unreadable = new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                throw new DataAccessResourceFailureException("connection refused for chairman@coop.example");
            }
        };

        assertThat(command(unreadable).execute(out, err)).isEqualTo(FirstChairmanCommand.OK);

        assertThat(warnings()).containsExactly("First chairman command: the outbox could not be read "
                + "(org.springframework.dao.DataAccessResourceFailureException)");
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void anInterruptedWaitForTheOutboxEndsTheWaitAndKeepsTheInterrupt() {
        AtomicInteger reads = new AtomicInteger();
        Thread.currentThread().interrupt();

        int code = command(outboxWith(1, reads)).execute(out, err);

        assertThat(code).isEqualTo(FirstChairmanCommand.OK);
        assertThat(reads).as("one count, then the interrupted sleep ends the wait").hasValue(1);
        assertThat(Thread.interrupted()).as("the interrupt is restored for the caller").isTrue();
    }

    @Test
    void anOutboxThatAnswersNoCountIsNothingToWaitFor() {
        AtomicInteger reads = new AtomicInteger();
        JdbcTemplate noCount = new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                reads.incrementAndGet();
                return null;
            }
        };

        command(noCount).run(null);

        assertThat(exitCodes).containsExactly(FirstChairmanCommand.OK);
        assertThat(reads).hasValue(1);
    }

    @Test
    void aProblemWithAFailureCodePrintsTheCodeAndTheFailureCodeOnly() {
        doThrow(ApiProblemException.invitationNotSent("SMTP_AUTH_FAILED")).when(work).run(any(), any());

        assertThat(command(outboxWith(0, new AtomicInteger())).execute(out, err))
                .isEqualTo(FirstChairmanCommand.FAILED);

        assertThat(errBytes.toString(StandardCharsets.UTF_8).strip())
                .isEqualTo("Error INVITATION_NOT_SENT (SMTP_AUTH_FAILED)");
    }

    @Test
    void theApplicationRunnerDoesNothingUntilItRuns() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);

        FirstChairmanCommand runner = new FirstChairmanCommand(work, environment, new JdbcTemplate(),
                transactionManager, context);

        assertThat(runner).isNotNull();
        verifyNoInteractions(context, work);
    }
}
