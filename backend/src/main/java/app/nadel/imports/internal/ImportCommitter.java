package app.nadel.imports.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditBatch;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.members.MemberImport;
import app.nadel.meters.MeterImport;
import app.nadel.plots.PlotImport;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import nadel.contract.model.ImportResult;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Commits a previewed import: everything the preview announced is saved in one transaction, or nothing is.
 * <p>
 * Inside the transaction, in this order:
 * <ol>
 *   <li>the advisory lock for the imports of the cooperative, so two commits never interleave;</li>
 *   <li>the run row {@code FOR UPDATE}, and the status and expiry checks (nothing is read from the other modules
 *       before the run is known to be committable);</li>
 *   <li>the stored rows are planned once to learn which existing plots, meters and members they touch; the
 *       persistence context is cleared; those plots are locked ({@code FOR UPDATE}), then the plots that get a new
 *       meter ({@code FOR SHARE}) and the unchanged meters ({@code FOR UPDATE}), then those members
 *       ({@code FOR UPDATE}), each set in id order: the order of a change of owner, which locks the plot first and then
 *       its owners, and of a meter replacement, which locks the plot and then the meter;</li>
 *   <li>the rows are planned again under the locks and the digest is compared with the preview's: any difference or
 *       problem is {@code IMPORT_STALE}. Without the clear in between, the second planning would read the first one's
 *       entities from the persistence context instead of the locked rows;</li>
 *   <li>members, then plots with their status rows, then first owners, then meters are saved (the facades'
 *       {@code apply}), each
 *       record audited as when entered by hand: the facades return the audit events and this class publishes them as
 *       {@link AuditBatch}es of {@value AuditBatch#MAX_ENTRIES}, not one event per record; then the
 *       {@code IMPORT_COMMITTED} summary; then the run is marked committed and its plan dropped.</li>
 * </ol>
 * Any failure rolls the whole transaction back, the audit events and the outbox rows included. A stale or an expired
 * refusal is also recorded on the run, in a short transaction of its own after that rollback (the rolled-back
 * transaction could not keep it): the plan, which holds personal data, is dropped, and a stale run gets the status
 * {@code STALE}. An expired run keeps the status {@code PREVIEW} (it is shown as expired by its expiry time).
 * Any other failure leaves the run untouched, so it can be tried again while it lasts.
 * <p>
 * File names and cell values are never logged.
 */
@Service
class ImportCommitter {

    private final ImportRunRepository runs;
    private final ImportPlanner planner;
    private final MemberImport memberImport;
    private final PlotImport plotImport;
    private final MeterImport meterImport;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final TransactionTemplate outcomeTransaction;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    ImportCommitter(ImportRunRepository runs, ImportPlanner planner, MemberImport memberImport,
            PlotImport plotImport, MeterImport meterImport, ApplicationEventPublisher events,
            PlatformTransactionManager transactions, Clock clock) {
        this.runs = runs;
        this.planner = planner;
        this.memberImport = memberImport;
        this.plotImport = plotImport;
        this.meterImport = meterImport;
        this.events = events;
        this.transaction = new TransactionTemplate(transactions);
        this.outcomeTransaction = new TransactionTemplate(transactions);
        this.outcomeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * @throws ApiProblemException 404 for an unknown run (or another cooperative's); 409 {@code IMPORT_HAS_ERRORS},
     *                             {@code IMPORT_NOTHING_TO_COMMIT}, {@code IMPORT_STALE},
     *                             {@code IMPORT_ALREADY_COMMITTED}; 410 {@code IMPORT_EXPIRED}
     */
    ImportResult commit(UUID importId, AccessProfile actor) {
        try {
            return transaction.execute(status -> commitInTransaction(importId, actor));
        } catch (ApiProblemException refused) {
            recordOutcome(importId, actor.tenantId(), refused);
            throw refused;
        }
    }

    private ImportResult commitInTransaction(UUID importId, AccessProfile profile) {
        UUID tenantId = profile.tenantId();
        runs.lockImports(tenantId);
        ImportRun run = runs.findByIdForUpdate(importId).orElseThrow(ApiProblemException::notFound);
        Instant now = clock.instant();
        // Lazy cleanup, kept only when this commit succeeds: a refusal rolls it back with the transaction. Stale and
        // expired refusals repeat it in recordOutcome, in a transaction that survives the rollback.
        runs.clearExpiredPlans(tenantId, now);
        checkCommittable(run, now);

        String fileName = run.getFileName();
        String digest = run.getPlanDigest();
        Map<String, Object> stored = run.getPlan();
        StoredPlan.Sheets sheets = StoredPlan.read(stored);

        // first planning: which existing plots and members do the rows touch? Locks come before the real planning.
        ImportPlan preliminary = planner.plan(tenantId, importId, sheets.plots(), sheets.members(), sheets.ownership(),
                sheets.meters());
        requireSamePlan(preliminary, digest);
        entityManager.clear();
        ImportPlan.Apply touched = preliminary.apply();
        plotImport.lock(touched.plotPlan(), touched.ownershipPlan());
        meterImport.lock(touched.meterPlan());
        memberImport.lock(touched.memberPlan());
        entityManager.clear();

        // second planning, under the locks: this is the plan that is saved
        ImportPlan plan = planner.plan(tenantId, importId, sheets.plots(), sheets.members(), sheets.ownership(),
                sheets.meters());
        requireSamePlan(plan, digest);
        ImportPlan.Apply apply = plan.apply();
        Actor author = new Actor(profile.memberId(), profile.subject(), profile.displayName());
        List<AuditEvent> entries = new ArrayList<>(
                memberImport.apply(tenantId, apply.memberRows(), apply.memberPlan(), author));
        entries.addAll(plotImport.apply(tenantId, apply.plotRows(), apply.plotPlan(), apply.ownershipRows(),
                apply.ownershipPlan(), author));
        // after the plots: a meter may belong to a plot the same import has just created
        entries.addAll(meterImport.apply(tenantId, apply.meterRows(), apply.meterPlan(), author));
        // one batch event per AuditBatch.MAX_ENTRIES records, stored by the outbox in this transaction
        AuditBatch.of(tenantId, entries).forEach(events::publishEvent);
        publishSummary(tenantId, author, importId, fileName, plan.counts());
        runs.markCommitted(importId, now);
        return new ImportResult().id(importId).counts(ImportService.toApi(plan.counts()));
    }

    /** The refusals that need nothing from the other modules. */
    private void checkCommittable(ImportRun run, Instant now) {
        switch (run.getStatus()) {
            case COMMITTED -> throw conflict(ProblemCodes.IMPORT_ALREADY_COMMITTED,
                    "This import was committed already.");
            case HAS_ERRORS -> throw conflict(ProblemCodes.IMPORT_HAS_ERRORS,
                    "The preview has problems and cannot be committed.");
            case STALE -> throw conflict(ProblemCodes.IMPORT_STALE,
                    "The data changed after the preview; upload the file again.");
            case PREVIEW -> {
                // a preview whose plan is already dropped is an expired one
                if (now.isAfter(run.getExpiresAt()) || run.getPlan() == null || run.getPlanDigest() == null) {
                    throw new ApiProblemException(HttpStatus.GONE, ProblemCodes.IMPORT_EXPIRED,
                            "The preview expired; upload the file again.");
                }
                if (!changesAnything(run.getCounts())) {
                    throw conflict(ProblemCodes.IMPORT_NOTHING_TO_COMMIT,
                            "Everything in the file is already saved; there is nothing to import.");
                }
            }
        }
    }

    /** A plan with problems or another digest than the preview's is not what the chairman saw. */
    private static void requireSamePlan(ImportPlan plan, String previewDigest) {
        if (plan.hasProblems() || plan.digest() == null || !plan.digest().equals(previewDigest)) {
            throw conflict(ProblemCodes.IMPORT_STALE,
                    "The plots, members, owners or meters changed after the preview; upload the file again.");
        }
    }

    private void publishSummary(UUID tenantId, Actor author, UUID importId, String fileName, PlanCounts counts) {
        List<FieldChange> fields = new ArrayList<>();
        addCounts(fields, "plots", counts.plots());
        addCounts(fields, "members", counts.members());
        // existing ownership is never updated, so only created and unchanged can be non-zero
        fields.add(new FieldChange("ownershipsCreated", null, String.valueOf(counts.ownerships().created())));
        fields.add(new FieldChange("ownershipsUnchanged", null, String.valueOf(counts.ownerships().unchanged())));
        // meters are never updated either
        fields.add(new FieldChange("metersCreated", null, String.valueOf(counts.meters().created())));
        fields.add(new FieldChange("metersUnchanged", null, String.valueOf(counts.meters().unchanged())));
        events.publishEvent(AuditEvent.now(tenantId, author, "IMPORT_COMMITTED", "IMPORT", importId, fileName, fields));
    }

    private static void addCounts(List<FieldChange> fields, String sheet, PlanCounts.EntityCounts counts) {
        fields.add(new FieldChange(sheet + "Created", null, String.valueOf(counts.created())));
        fields.add(new FieldChange(sheet + "Updated", null, String.valueOf(counts.updated())));
        fields.add(new FieldChange(sheet + "Unchanged", null, String.valueOf(counts.unchanged())));
    }

    /**
     * Records a stale or expired refusal after the main transaction rolled back: the plan is dropped (and a stale run
     * marked) in a transaction of its own, which also clears the expired plans of the cooperative.
     */
    private void recordOutcome(UUID importId, UUID tenantId, ApiProblemException refused) {
        boolean stale = ProblemCodes.IMPORT_STALE.equals(refused.code());
        boolean expired = ProblemCodes.IMPORT_EXPIRED.equals(refused.code());
        if (!stale && !expired) {
            return;
        }
        outcomeTransaction.executeWithoutResult(status -> {
            if (stale) {
                runs.markStale(importId);
            } else {
                runs.dropPlan(importId);
            }
            runs.clearExpiredPlans(tenantId, clock.instant());
        });
    }

    private static boolean changesAnything(Map<String, Object> counts) {
        int total = 0;
        for (String sheet : List.of("plots", "members", "ownerships", "meters")) {
            if (counts.get(sheet) instanceof Map<?, ?> entity) {
                total += number(entity.get("created")) + number(entity.get("updated"));
            }
        }
        return total > 0;
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static ApiProblemException conflict(String code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }
}
