package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.nadel.notifications.Mailpit;
import java.time.Duration;
import java.util.UUID;
import org.jobrunr.server.BackgroundJobServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * The delivery worker on JobRunr 9 (feature 017, ADR 0007): JobRunr starts on Spring Boot 4.1 with Jackson 3 on the
 * tables of Flyway V30 (it creates none: {@code skip-create}), as the application role, and its background job server
 * runs the {@link DispatchDeliveries} job a send wakes, which delivers the letter. The other integration tests run
 * without the server and call the dispatcher themselves. The context is closed after the class: a cached context would
 * keep its server polling the shared database and run the jobs the later classes enqueue, racing their own dispatchers.
 */
@DirtiesContext
@TestPropertySource(properties = {"nadel.test.job-server=true",
        "jobrunr.background-job-server.poll-interval-in-seconds=5"})
class DeliveryJobTests extends DeliveryTestSupport {

    @Autowired
    BackgroundJobServer server;

    @Test
    void aSendWakesTheJobThatDeliversTheLetter() throws Exception {
        mailbox(Mailpit.PASSWORD);
        UUID onEmail = member("Пошта", "EMAIL", "job@delivery.test");
        charge(plot("10", onEmail), "100.00");
        buildInvoices();

        sentMonth(false, false);

        assertThat(server.isRunning()).isTrue();
        await().atMost(Duration.ofSeconds(30)).until(() -> "SENT".equals(deliveries().getFirst().get("status")));
        assertThat(mailpit.lettersTo("job@delivery.test")).hasSize(1);
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from jobrunr_jobs where state = 'SUCCEEDED' and jobasjson like ?", Integer.class,
                "%" + tenant + "%") >= 1);
        // the job's arguments are the cooperative and the channel, nothing else
        String job = ownerJdbc().queryForObject("select jobasjson from jobrunr_jobs where jobasjson like ? limit 1",
                String.class, "%" + tenant + "%");
        assertThat(job).contains("EMAIL").doesNotContain("job@delivery.test").doesNotContain("Пошта");
    }

    @Test
    void theTablesComeFromFlywayWithoutRowLevelSecurity() {
        Integer scripts = ownerJdbc().queryForObject("select count(*) from jobrunr_migrations", Integer.class);
        Boolean rls = ownerJdbc().queryForObject(
                "select relrowsecurity from pg_class where oid = 'public.jobrunr_jobs'::regclass", Boolean.class);
        String owner = ownerJdbc().queryForObject(
                "select tableowner from pg_tables where tablename = 'jobrunr_jobs'", String.class);
        String migrationsOwner = ownerJdbc().queryForObject(
                "select tableowner from pg_tables where tablename = 'flyway_schema_history'", String.class);
        Boolean appWrites = ownerJdbc().queryForObject(
                "select has_table_privilege('nadel_app', 'jobrunr_jobs', 'INSERT, UPDATE, DELETE')", Boolean.class);

        assertThat(scripts).isEqualTo(18);
        assertThat(rls).isFalse();
        assertThat(owner).isEqualTo(migrationsOwner);
        assertThat(appWrites).isTrue();
    }
}
