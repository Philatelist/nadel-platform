package app.nadel.reports.internal;

import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The cooperative fixtures of {@link ReportsTestSupport} for tests of other packages (the acceptance tests of feature
 * 013): the owner role, the removal of a throw-away cooperative with its append-only ledger and templates, a person
 * with a base role, and the ledger-owner transaction. Nothing of its own: every method delegates.
 */
public final class ReportsFixtures {

    private ReportsFixtures() {
    }

    /** Creates the {@code nadel_owner} role the ledger clean-up runs as, when the database has none yet. */
    public static void createOwnerRole() {
        ReportsTestSupport.createOwnerRole();
    }

    /** Removes everything of a throw-away cooperative, once its owed audit entries are written. */
    public static void removeCooperative(UUID cooperative) {
        ReportsTestSupport.removeCooperative(cooperative);
    }

    /** A member of the cooperative with one base role, named "Звітний {baseRole}"; returns a token. */
    public static String person(UUID cooperative, String baseRole) {
        return ReportsTestSupport.person(cooperative, baseRole);
    }

    /** Runs the work as {@code nadel_owner} with the ledger maintenance setting on, in one transaction. */
    public static void asLedgerOwner(Consumer<JdbcTemplate> work) {
        ReportsTestSupport.asLedgerOwner(work);
    }
}
