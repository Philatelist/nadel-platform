package app.nadel;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import org.awaitility.core.ConditionTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Keeps the readings that meter tests enter in the demo cooperative free of charges (feature 011): the demo cooperative
 * has an electricity tariff, so every reading of a plot meter there would be charged by the ledger's listener, the
 * reading would become "in use" (no more corrections) and the test meters could not be deleted afterwards. {@link #pause}
 * switches the demo {@code ELECTRICITY} charge type off for the test (an inactive type charges and queues nothing);
 * {@link #resume} waits until the listeners of the test's reading events have run and switches it on again. Tests whose
 * readings fall before the demo tariff (so they are never charged, only queued) just {@link #awaitListeners}.
 */
public final class DemoReadingCharges {

    private static final UUID DEMO_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private DemoReadingCharges() {
    }

    public static void pause(JdbcTemplate owner) {
        owner.update("update charge_type set active = false where tenant_id = ? and code = 'ELECTRICITY'",
                DEMO_TENANT);
    }

    public static void resume(JdbcTemplate owner) {
        awaitListeners(owner);
        owner.update("update charge_type set active = true where tenant_id = ? and code = 'ELECTRICITY'",
                DEMO_TENANT);
    }

    /**
     * Waits until every reading event has been handled by the ledger, so a test's cleanup never deletes a meter while
     * its listener is still charging it. A listener that failed stays incomplete; the wait then ends after a while and
     * the cleanup goes on.
     */
    public static void awaitListeners(JdbcTemplate owner) {
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> owner.queryForObject(
                    "select count(*) from event_publication where completion_date is null "
                            + "and event_type like '%PlotMeterPointsChanged'", Integer.class) == 0);
        } catch (ConditionTimeoutException e) {
            // a failed listener keeps its publication incomplete until a restart; nothing to wait for any more
        }
    }
}
