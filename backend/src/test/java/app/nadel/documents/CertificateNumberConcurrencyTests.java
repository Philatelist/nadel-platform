package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Certificate numbers (feature 016 §2.7, technical considerations 2.7): one gap-free sequence per cooperative and year
 * in {@code document_counter}, stepped last in the issuing transaction. Certificates issued at the same moment get
 * consecutive numbers with no gap and no duplicate; an issue that fails after taking its number leaves no gap; the
 * year is the year of the issue in Kyiv, and a new year starts again at 1.
 */
class CertificateNumberConcurrencyTests extends CertificateTestSupport {

    private static final int PARALLEL = 6;

    /** The system clock, or a fixed moment while a test sets one. */
    static final class SettableClock extends Clock {

        private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

        private volatile Instant fixed;

        void set(Instant instant) {
            fixed = instant;
        }

        void reset() {
            fixed = null;
        }

        @Override
        public ZoneId getZone() {
            return KYIV;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            Instant at = fixed;
            return at == null ? Clock.system(zone) : Clock.fixed(at, zone);
        }

        @Override
        public Instant instant() {
            Instant at = fixed;
            return at == null ? Instant.now() : at;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {

        @Bean
        @Primary
        SettableClock settableClock() {
            return new SettableClock();
        }
    }

    @Autowired
    SettableClock clock;

    @AfterEach
    void resetClockAndDropTheFailingTrigger() {
        clock.reset();
        ownerJdbc().execute("drop trigger if exists certificate_test_failure on certificate");
        ownerJdbc().execute("drop function if exists certificate_test_failure()");
    }

    private String numberOf(MvcResult result) throws Exception {
        JsonNode row = json.readTree(result.getResponse().getContentAsString());
        return row.get("number").asString();
    }

    private int waitingForTheCounter() {
        return ownerJdbc().queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' "
                + "and query like '%document_counter%'", Integer.class);
    }

    private List<Integer> storedSeqs(int year) {
        return owner().queryForList("select seq from certificate where tenant_id = ? and year = ? order by seq",
                Integer.class, tenant, year);
    }

    @Test
    void certificatesIssuedAtTheSameMomentGetConsecutiveNumbersWithoutAGap() throws Exception {
        int year = kyivYear();
        List<UUID> plots = new ArrayList<>();
        for (int i = 0; i < PARALLEL; i++) {
            plots.add(memberPlot(Integer.toString(20 + i)));
        }
        // the counter row exists, so every issue reaches its UPDATE (and its row lock) instead of the insert
        owner().update("insert into document_counter (tenant_id, kind, year, last_value) values (?, 'CERTIFICATE', "
                + "?, 0)", tenant, (short) year);

        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        try (Connection blocker = ownerJdbc().getDataSource().getConnection()) {
            // a third transaction holds the counter row, so every issue waits for it before taking a number
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement("select last_value from document_counter "
                    + "where tenant_id = ? and kind = 'CERTIFICATE' and year = ? for update")) {
                lock.setObject(1, tenant);
                lock.setShort(2, (short) year);
                lock.executeQuery().close();
            }
            List<Future<MvcResult>> issues = new ArrayList<>();
            for (int i = 0; i < PARALLEL; i++) {
                UUID plot = plots.get(i);
                String bearer = i % 2 == 0 ? chairman : customRole("CERTIFICATES_ISSUE");
                issues.add(pool.submit(() -> issueCertificate(bearer, plot, null).andReturn()));
            }
            boolean allWaited = true;
            try {
                await().atMost(Duration.ofSeconds(20)).until(() -> waitingForTheCounter() == PARALLEL);
            } catch (ConditionTimeoutException e) {
                // the issues did not wait for the counter row: let them finish, then fail below
                allWaited = false;
            }
            blocker.commit();
            blocker.setAutoCommit(true);

            List<String> numbers = new ArrayList<>();
            for (Future<MvcResult> issue : issues) {
                MvcResult result = issue.get(60, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(201);
                numbers.add(numberOf(result));
            }
            assertThat(numbers).containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, PARALLEL)
                    .mapToObj(n -> "Д-" + year + "-" + n).toList());
            assertThat(allWaited).as("every issue waited for the counter row").isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(lastCertificate(year)).isEqualTo(PARALLEL);
        assertThat(storedSeqs(year)).containsExactlyElementsOf(IntStream.rangeClosed(1, PARALLEL).boxed().toList());
    }

    @Test
    void anIssueThatFailsAfterTakingItsNumberLeavesNoGap() throws Exception {
        int year = kyivYear();
        UUID failing = memberPlot("9");
        UUID plot = memberPlot("10");
        certificate(plot, null);
        // the certificate row of the failing plot fails after the counter stepped, inside the issuing transaction
        ownerJdbc().execute("""
                create function certificate_test_failure() returns trigger language plpgsql as $$
                begin
                    if new.plot_id = '%s'::uuid then
                        raise exception 'certificate_test_failure';
                    end if;
                    return new;
                end;
                $$""".formatted(failing));
        ownerJdbc().execute("create trigger certificate_test_failure before insert on certificate for each row "
                + "execute function certificate_test_failure()");

        issueCertificate(chairman, failing, null).andExpect(status().is5xxServerError());
        assertThat(lastCertificate(year)).isEqualTo(1L);
        assertThat(owner().queryForObject("select count(*) from certificate where plot_id = ?", Integer.class,
                failing)).isZero();

        assertThat(certificate(plot, null).get("number").asString()).isEqualTo("Д-" + year + "-2");
        assertThat(storedSeqs(year)).containsExactly(1, 2);
    }

    @Test
    void theYearIsTheYearOfTheIssueInKyivAndANewYearStartsAgainAtOne() throws Exception {
        UUID plot = memberPlot("9");

        // 31.12.2026 23:30 in Kyiv (21:30 UTC)
        clock.set(Instant.parse("2026-12-31T21:30:00Z"));
        assertThat(certificate(plot, null).get("number").asString()).isEqualTo("Д-2026-1");
        assertThat(certificate(plot, null).get("number").asString()).isEqualTo("Д-2026-2");

        // 01.01.2027 00:30 in Kyiv, while it is still 2026 in UTC
        clock.set(Instant.parse("2026-12-31T22:30:00Z"));
        JsonNode first = certificate(plot, null);
        assertThat(first.get("number").asString()).isEqualTo("Д-2027-1");
        assertThat(first.get("asOf").asString()).isEqualTo("2027-01-01");
        assertThat(certificate(plot, null).get("number").asString()).isEqualTo("Д-2027-2");

        assertThat(lastCertificate(2026)).isEqualTo(2L);
        assertThat(lastCertificate(2027)).isEqualTo(2L);
        assertThat(storedSeqs(2026)).containsExactly(1, 2);
        assertThat(storedSeqs(2027)).containsExactly(1, 2);
    }
}
