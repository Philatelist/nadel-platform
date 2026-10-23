package app.nadel.ledger.internal;

import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.ledger.internal.CommonConsumerSettings.Billing;
import app.nadel.meters.MeterCatalog;
import app.nadel.meters.MeterCatalog.MeterFacts;
import app.nadel.meters.MeterKind;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.settings.CommonConsumerSplit;
import app.nadel.settings.CooperativeSettings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.CommonConsumerBilling;
import nadel.contract.model.CommonConsumerBillingUpdate;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How one common consumer is shared among plots ({@code GET|PUT /common-consumers/{meterId}/billing}, feature 011
 * §2.6): its own split (absent: the cooperative's {@code commonConsumerDefaultSplit}) and its service users, the plots
 * marked as using it (they count with {@code SERVICE_USERS}). Settings, not history: a change applies to the next run
 * and never to runs booked before.
 * <p>
 * The settings belong to the consumer, the whole chain of its meters ({@link MeterCatalog#chainOf}: a replacement
 * keeps the consumer): {@code GET} on any meter of the chain shows what a run uses for it
 * ({@link CommonConsumerSettings#ofChain}: the split and the users each of the latest meter that has them), and
 * {@code PUT} on any meter of the chain saves them on the chain's latest meter, clearing the rows of its other meters
 * ({@link CommonConsumerSettings#saveConsumer}), so a read after a change shows what was saved and a run charges with
 * it.
 * <p>
 * The meter must be a common consumer meter of the cooperative (removed ones too), else 404 {@code NOT_FOUND}. A
 * service user must be an active plot that is not common land, listed once: field codes {@code UNKNOWN_PLOT},
 * {@code DUPLICATE}, {@code PLOT_ARCHIVED}, {@code PLOT_COMMON_LAND} on {@code servicePlotIds[i]}. Two changes of one
 * consumer run one after the other (an advisory lock by the first meter of its chain); a change is audited as
 * {@code COMMON_CONSUMER_BILLING_CHANGED} (entity type {@code METER} about the meter of the request, label its name)
 * with {@code split} (the consumer's own split, absent for the default) and {@code servicePlots} (plot numbers in
 * natural order, joined by ", "), each only when it changed.
 */
@Service
class CommonConsumerBillings {

    private final MeterCatalog meters;
    private final PlotCatalog plots;
    private final CooperativeSettings settings;
    private final CommonConsumerSettings billing;
    private final LedgerAuditPublisher audit;

    CommonConsumerBillings(MeterCatalog meters, PlotCatalog plots, CooperativeSettings settings,
            CommonConsumerSettings billing, LedgerAuditPublisher audit) {
        this.meters = meters;
        this.plots = plots;
        this.settings = settings;
        this.billing = billing;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    CommonConsumerBilling get(UUID meterId) {
        MeterFacts meter = consumer(meterId);
        return view(meter, billing.ofConsumer(meters.chainOf(meterId)));
    }

    @Transactional
    CommonConsumerBilling update(UUID meterId, CommonConsumerBillingUpdate update, AccessProfile actor) {
        MeterFacts meter = consumer(meterId);
        List<UUID> users = update.getServicePlotIds() == null ? List.of() : update.getServicePlotIds();
        validate(users);
        CommonConsumerSplit split = update.getSplit() == null ? null
                : CommonConsumerSplit.valueOf(update.getSplit().getValue());
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        List<UUID> chain = meters.chainOf(meterId);
        billing.lock(tenantId, chain.getFirst());
        Billing before = billing.ofConsumer(chain);
        Billing after = new Billing(split, new HashSet<>(users));
        if (!before.equals(after)) {
            billing.saveConsumer(tenantId, chain, split, users);
            List<FieldChange> changes = new ArrayList<>();
            if (before.split() != after.split()) {
                changes.add(new FieldChange("split", name(before.split()), name(after.split())));
            }
            if (!before.users().equals(after.users())) {
                changes.add(new FieldChange("servicePlots", numbers(before.users()), numbers(after.users())));
            }
            audit.publishMeter(actor, LedgerAuditPublisher.Action.COMMON_CONSUMER_BILLING_CHANGED, meterId,
                    meter.name(), changes);
        }
        return view(meter, after);
    }

    private MeterFacts consumer(UUID meterId) {
        return meters.find(meterId).filter(meter -> meter.kind() == MeterKind.COMMON_CONSUMER)
                .orElseThrow(ApiProblemException::notFound);
    }

    private void validate(List<UUID> users) {
        Map<UUID, PlotFacts> found = plots.findAll(users);
        Set<UUID> seen = new HashSet<>();
        List<FieldProblem> problems = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            UUID id = users.get(i);
            PlotFacts plot = found.get(id);
            String field = "servicePlotIds[" + i + "]";
            if (!seen.add(id)) {
                problems.add(new FieldProblem(field, ProblemCodes.DUPLICATE));
            } else if (plot == null) {
                problems.add(new FieldProblem(field, ProblemCodes.UNKNOWN_PLOT));
            } else if (plot.archived()) {
                problems.add(new FieldProblem(field, ProblemCodes.PLOT_ARCHIVED));
            } else if (plot.commonLand()) {
                problems.add(new FieldProblem(field, ProblemCodes.PLOT_COMMON_LAND));
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", problems);
        }
    }

    private CommonConsumerBilling view(MeterFacts meter, Billing stored) {
        CommonConsumerSplit effective = stored.split() == null ? settings.commonConsumerDefaultSplit()
                : stored.split();
        CommonConsumerBilling dto = new CommonConsumerBilling(meter.id(), meter.active(),
                nadel.contract.model.CommonConsumerSplit.fromValue(effective.name()),
                stored.users().isEmpty() ? List.of() : plots.inNaturalOrder(stored.users()));
        dto.setName(meter.name());
        if (stored.split() != null) {
            dto.setSplit(nadel.contract.model.CommonConsumerSplit.fromValue(stored.split().name()));
        }
        return dto;
    }

    private static @Nullable String name(@Nullable CommonConsumerSplit split) {
        return split == null ? null : split.name();
    }

    /** The plot numbers in natural order, joined by ", "; null for none. */
    private @Nullable String numbers(Set<UUID> plotIds) {
        if (plotIds.isEmpty()) {
            return null;
        }
        Map<UUID, PlotFacts> found = plots.findAll(plotIds);
        return plots.inNaturalOrder(plotIds).stream().map(id -> found.get(id).number())
                .collect(Collectors.joining(", "));
    }
}
