package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.audit.Actor;
import app.nadel.members.MemberImport;
import app.nadel.members.MemberImportAction;
import app.nadel.members.MemberImportPlan;
import app.nadel.members.MemberImportRow;
import app.nadel.plots.OwnershipImportOutcome;
import app.nadel.plots.OwnershipImportPlan;
import app.nadel.plots.OwnershipImportRow;
import app.nadel.plots.PlotImport;
import app.nadel.plots.PlotImportAction;
import app.nadel.plots.PlotImportPlan;
import app.nadel.plots.PlotImportRow;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A rule the database enforces and the plan could not foresee, because a record was created between the plan and the
 * flush, is 409 {@code IMPORT_STALE}. Inserts are batched (Hibernate {@code jdbc.batch_size}), so the violation reaches
 * the application as the failure of a JDBC batch, not of a single statement. Each test plans in a tenant-aware
 * transaction, creates the conflicting record in another connection (committed, as a concurrent request would), applies
 * the plan in the same transaction and expects the refusal for each of the four constraints the import maps.
 */
@ExtendWith(OutputCaptureExtension.class)
class ImportConstraintViolationTests extends ImportCommitSupport {

    private static final Actor ACTOR = new Actor(null, "sub-constraint-tests", "Constraint Tests");

    @Autowired
    PlotImport plotImport;

    @Autowired
    MemberImport memberImport;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    DataSource dataSource;

    @Test
    void aPlotNumberTakenAfterThePlanIsStale() {
        String taken = plotNo("taken");
        List<PlotImportRow> rows = List.of(plotRow(2, plotNo("a"), null), plotRow(3, taken, null),
                plotRow(4, plotNo("c"), null));

        assertStale(() -> inTransaction(() -> {
            PlotImportPlan plan = plotImport.plan(DEMO_TENANT, UUID.randomUUID(), rows);
            assertThat(plan.items()).allMatch(item -> item.action() == PlotImportAction.NEW);
            insertPlot(taken, null);
            plotImport.apply(DEMO_TENANT, rows, plan, List.of(), emptyOwnershipPlan(), ACTOR);
            return null;
        }));
        assertThat(plotsLike("CMT-" + tag)).isEqualTo(1);
    }

    @Test
    void aCadastralNumberTakenAfterThePlanIsStale() {
        String cadastral = "4810136300:02:" + String.format("%03d", Math.abs(tag.hashCode()) % 1000) + ":0001";
        List<PlotImportRow> rows = List.of(plotRow(2, plotNo("a"), null), plotRow(3, plotNo("b"), cadastral),
                plotRow(4, plotNo("c"), null));

        assertStale(() -> inTransaction(() -> {
            PlotImportPlan plan = plotImport.plan(DEMO_TENANT, UUID.randomUUID(), rows);
            assertThat(plan.items()).allMatch(item -> item.action() == PlotImportAction.NEW);
            insertPlot(plotNo("holder"), cadastral);
            plotImport.apply(DEMO_TENANT, rows, plan, List.of(), emptyOwnershipPlan(), ACTOR);
            return null;
        }));
        assertThat(plotsLike("CMT-" + tag)).isEqualTo(1);
    }

    @Test
    void anOverlappingOwnerRecordedAfterThePlanIsStale() {
        UUID owner = newMember(DEMO_TENANT, email("owner"));
        UUID plot = ownerJdbc().queryForObject("select id from plot where tenant_id = ? and number = ?", UUID.class,
                DEMO_TENANT, FREE_DEMO_PLOT);
        List<OwnershipImportRow> rows = List.of(
                new OwnershipImportRow(2, FREE_DEMO_PLOT, owner, "1/1", LocalDate.now().minusDays(30)));

        assertStale(() -> inTransaction(() -> {
            PlotImportPlan plotPlan = plotImport.plan(DEMO_TENANT, UUID.randomUUID(), List.of());
            OwnershipImportPlan plan = plotImport.planOwnership(DEMO_TENANT, List.of(), plotPlan, rows);
            assertThat(plan.items()).allMatch(item -> item.outcome() == OwnershipImportOutcome.FIRST_OWNERS);
            ownerJdbc().update("insert into ownership (tenant_id, plot_id, member_id, share_numerator, "
                    + "share_denominator, valid_from, valid_to) values (?, ?, ?, 1, 1, date '2000-01-01', null)",
                    DEMO_TENANT, plot, owner);
            plotImport.apply(DEMO_TENANT, List.of(), plotPlan, rows, plan, ACTOR);
            return null;
        }));
    }

    @Test
    void aMemberEmailTakenAfterThePlanIsStale() {
        memberEmailTakenAfterThePlan(email("taken"));
        assertThat(membersLike("Fixt" + tag)).isEqualTo(1);
    }

    @Test
    void aFailedBatchLeavesNoRowValuesInTheLog(CapturedOutput output) {
        String taken = email("logged");

        memberEmailTakenAfterThePlan(taken);

        // Hibernate would log "Batch entry 1 insert into member (...) values ('<e-mail>', ...)" at WARN
        assertThat(output.getAll()).doesNotContain("Batch entry").doesNotContain(taken).doesNotContain("Fixt" + tag);
    }

    @Test
    void theServerErrorCarriesNoOffendingValues() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create temp table value_probe (k text unique)");
            statement.execute("insert into value_probe values ('secret-" + tag + "')");

            assertThatThrownBy(() -> statement.execute("insert into value_probe values ('secret-" + tag + "')"))
                    .isInstanceOfSatisfying(SQLException.class, e -> {
                        // the constraint is still named, which is what the import relies on; the key value is not
                        assertThat(e.getMessage()).contains("value_probe_k_key").doesNotContain("secret-");
                        assertThat(e.getSQLState()).isEqualTo("23505");
                    });
        }
    }

    private void memberEmailTakenAfterThePlan(String taken) {
        List<MemberImportRow> rows = List.of(memberRow(2, "a", email("a")), memberRow(3, "b", taken),
                memberRow(4, "c", email("c")));

        assertStale(() -> inTransaction(() -> {
            MemberImportPlan plan = memberImport.plan(DEMO_TENANT, UUID.randomUUID(), rows);
            assertThat(plan.items()).allMatch(item -> item.action() == MemberImportAction.NEW);
            ownerJdbc().update("insert into member (id, tenant_id, last_name, first_name, email, delivery_channel) "
                    + "values (?, ?, ?, 'Holder', ?, 'EMAIL')", UUID.randomUUID(), DEMO_TENANT, "Fixt" + tag + "Holder",
                    taken);
            memberImport.apply(DEMO_TENANT, rows, plan, ACTOR);
            return null;
        }));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return TenantContext.runAs(DEMO_TENANT,
                () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }

    private static void assertStale(Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(ApiProblemException.class, problem -> {
            assertThat(problem.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(problem.code()).isEqualTo(ProblemCodes.IMPORT_STALE);
        });
    }

    private void insertPlot(String number, String cadastral) {
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2, cadastral_number) "
                + "values (?, ?, ?, 'PLOT', 600, ?)", UUID.randomUUID(), DEMO_TENANT, number, cadastral);
    }

    private static OwnershipImportPlan emptyOwnershipPlan() {
        return new OwnershipImportPlan(List.of());
    }

    private static PlotImportRow plotRow(int row, String number, String cadastral) {
        return new PlotImportRow(row, number, null, "PLOT", new BigDecimal("600"), cadastral, null, null);
    }

    private MemberImportRow memberRow(int row, String name, String email) {
        return new MemberImportRow(row, "key-" + name, "Fixt" + tag + name, "Person", null, List.of(), email, null,
                null, null, null);
    }
}
