package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Two entries saved at the same moment never together take the cash below zero (feature 018 §2.2, technical
 * considerations 2.5): two treasurers record a cash expense of 150,00 each while the cash is 200,00; exactly one is
 * saved and the other is refused with {@code CASH_INSUFFICIENT}.
 * <p>
 * The race is made deterministic with a barrier in the database: the test holds {@code account_movement} in
 * {@code SHARE} mode, which lets the cash rule read the journal but blocks the insert of a movement. Both requests are
 * sent and the test waits until both wait on a lock. With the cash account locked {@code FOR UPDATE} before the journal
 * is read, the first waits on the insert and the second on the account row, so the second reads the journal only after
 * the first committed. Without that lock both would read 200,00, pass the rule and wait on the insert, and both would
 * be saved.
 */
class ExpenseConcurrencyTests extends TreasuryApiSupport {

    @Test
    void twoCashExpensesOf150On200SavedAtTheSameMomentSaveExactlyOne() throws Exception {
        String chairman = person(tenantA, "CHAIRMAN");
        String first = person(tenantA, "TREASURER");
        String second = person(tenantA, "TREASURER");
        UUID cash = account(tenantA, "CASH");
        UUID materials = category(tenantA, "EXPENSE", "MATERIALS");
        setOpening(chairman, cash, "200.00", today().minusDays(1)).andExpect(status().isOk());
        // the billing month exists before the race, so both requests only read it
        ownerJdbc().update("insert into billing_month (tenant_id, month) values (?, ?) "
                + "on conflict (tenant_id, month) do nothing", tenantA, today().withDayOfMonth(1));

        List<MockHttpServletResponse> responses = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection barrier = Objects.requireNonNull(ownerJdbc().getDataSource()).getConnection()) {
            barrier.setAutoCommit(false);
            try (Statement statement = barrier.createStatement()) {
                statement.execute("lock table account_movement in share mode");
            }
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (String treasurer : List.of(first, second)) {
                futures.add(pool.submit(() -> createExpense(treasurer, expense("CASH", today(), "150.00",
                        materials)).andReturn().getResponse()));
            }
            await().atMost(Duration.ofSeconds(20)).until(() -> waitingOnALock() == 2);
            barrier.rollback();
            for (Future<MockHttpServletResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(responses.stream().map(MockHttpServletResponse::getStatus).sorted().toList())
                .containsExactly(201, 409);
        MockHttpServletResponse refused = responses.stream().filter(r -> r.getStatus() == 409).findFirst()
                .orElseThrow();
        JsonNode problem = json.readTree(refused.getContentAsString());
        assertThat(problem.get("code").asString()).isEqualTo("CASH_INSUFFICIENT");
        assertThat(problem.get("balance").asString()).isEqualTo("50.00");
        assertThat(problem.get("date").asString()).isEqualTo(today().toString());
        assertThat(ownerJdbc().queryForObject("select count(*) from expense where tenant_id = ?", Integer.class,
                tenantA)).isOne();
        accounts(chairman).andExpect(jsonPath("$[0].balance").value("50.00"));
    }

    /** The application's sessions that wait on a lock right now. */
    private static int waitingOnALock() {
        return Objects.requireNonNull(ownerJdbc().queryForObject("select count(*) from pg_stat_activity "
                + "where usename = 'nadel_app' and wait_event_type = 'Lock'", Integer.class));
    }
}
