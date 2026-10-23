package app.nadel.ledger.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.ledger.ChargesBooked;
import app.nadel.ledger.OpeningBalanceImport;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.plots.PlotImportAction;
import app.nadel.plots.PlotImportItem;
import app.nadel.plots.PlotImportPlan;
import app.nadel.plots.PlotImportRow;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.ProblemCodes;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger's side of the Excel import ({@link OpeningBalanceImport}): plans the rows of the "Opening balances" sheet
 * with the rules of an opening balance entered by hand ({@link OpeningBalanceRules}), locks what the plan relies on and
 * books the new balances through {@link OpeningBalances#book}. Plots and charge types are read only through their
 * modules' catalogs. Every method runs in the caller's (the import's) transaction. Reasons are user content: never
 * logged.
 */
@Service
class OpeningBalanceImportService implements OpeningBalanceImport {

    private static final String PLOT_FIELD = "plotNumber";
    private static final String TYPE_FIELD = "chargeType";
    private static final String AMOUNT_FIELD = "amount";
    private static final String DATE_FIELD = "date";
    private static final String REASON_FIELD = "reason";
    private static final String COMMON_KIND = "COMMON";
    private static final String SOURCE_KEY_INDEX = "charge_source_key_uq";

    private final PlotCatalog plots;
    private final ChargeTypeCatalog chargeTypes;
    private final ChargeRepository charges;
    private final OpeningBalances openingBalances;
    private final BillingMonths billingMonths;
    private final LedgerAuditPublisher audit;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final Clock clock;

    OpeningBalanceImportService(PlotCatalog plots, ChargeTypeCatalog chargeTypes, ChargeRepository charges,
            OpeningBalances openingBalances, BillingMonths billingMonths, LedgerAuditPublisher audit, ApplicationEventPublisher events,
            EntityManager entityManager, Clock clock) {
        this.plots = plots;
        this.chargeTypes = chargeTypes;
        this.charges = charges;
        this.openingBalances = openingBalances;
        this.billingMonths = billingMonths;
        this.audit = audit;
        this.events = events;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** What the plot number of a row stands for. */
    private sealed interface Target {
    }

    /** No plot of the file and no active plot has the number. */
    private record Unknown() implements Target {
    }

    private record CommonLand() implements Target {
    }

    /** The plot's own row in the Plots sheet has a problem, which is reported there: nothing more to say. */
    private record Suppressed() implements Target {
    }

    /**
     * @param number  the plot number as stored, or as the file writes it for a plot the import creates
     * @param created whether the same import creates the plot
     */
    private record Resolved(UUID plotId, String number, boolean created) implements Target {
    }

    /** The values of a row whose cells and charge type passed the rules. */
    private record Valid(String plotKey, ChargeTypeFacts type, Money amount, LocalDate date) {
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Plan plan(UUID tenantId, List<PlotImportRow> plotRows, PlotImportPlan plotPlan, List<Row> rows) {
        if (rows.isEmpty()) {
            return new Plan(List.of());
        }
        Map<String, Target> targets = resolveTargets(plotRows, plotPlan, rows);
        Map<String, List<ChargeTypeFacts>> types = chargeTypes.findByNames(typeNames(rows));
        Map<UUID, List<ChargeRecord>> standing = standingOpenings(targets);
        LocalDate today = LocalDate.now(clock);

        Set<String> seen = new HashSet<>();
        List<Item> items = new ArrayList<>(rows.size());
        for (Row row : rows) {
            List<Problem> problems = new ArrayList<>();
            Valid valid = validate(row, types, today, problems);
            String number = blank(row.plotNumber());
            Target target = number == null ? null : targets.get(key(number));
            switch (target) {
                case Unknown _ -> problems.add(new Problem(PLOT_FIELD, ProblemCodes.UNKNOWN_PLOT));
                case CommonLand _ -> problems.add(new Problem(PLOT_FIELD, ProblemCodes.PLOT_COMMON_LAND));
                case null, default -> {
                }
            }
            if (!problems.isEmpty() || valid == null || !(target instanceof Resolved plot)) {
                items.add(new Item(row.row(), null, null, null, problems));
                continue;
            }
            if (!seen.add(valid.plotKey() + "/" + valid.type().id() + "/" + valid.date())) {
                items.add(problem(row, TYPE_FIELD, ProblemCodes.DUPLICATE_IN_FILE, Map.of()));
                continue;
            }
            items.add(planAgainstStored(row, valid, plot, standing));
        }
        return new Plan(items);
    }

    /** The values of the row, or null with the problems of its cells and its charge type added to {@code problems}. */
    private static @Nullable Valid validate(Row row, Map<String, List<ChargeTypeFacts>> types, LocalDate today,
            List<Problem> problems) {
        String plotNumber = blank(row.plotNumber());
        if (plotNumber == null) {
            problems.add(new Problem(PLOT_FIELD, ProblemCodes.REQUIRED));
        }
        ChargeTypeFacts type = null;
        String typeName = blank(row.chargeType());
        if (typeName == null) {
            problems.add(new Problem(TYPE_FIELD, ProblemCodes.REQUIRED));
        } else {
            List<ChargeTypeFacts> matches = types.getOrDefault(row.chargeType(), List.of());
            List<ChargeTypeFacts> usable = matches.stream().filter(candidate -> !candidate.archived()).toList();
            if (usable.size() == 1) {
                type = usable.getFirst();
            } else if (usable.size() > 1) {
                problems.add(new Problem(TYPE_FIELD, ProblemCodes.INVALID));
            } else if (!matches.isEmpty()) {
                problems.add(new Problem(TYPE_FIELD, ProblemCodes.CHARGE_TYPE_ARCHIVED));
            } else {
                problems.add(new Problem(TYPE_FIELD, ProblemCodes.UNKNOWN_CHARGE_TYPE));
            }
        }
        Money amount = null;
        BigDecimal typed = row.amount();
        if (typed == null) {
            problems.add(new Problem(AMOUNT_FIELD, ProblemCodes.REQUIRED));
        } else if (typed.stripTrailingZeros().scale() > Money.SCALE) {
            // never rounded: an amount with more decimals than kopecks is a mistake in the file
            problems.add(new Problem(AMOUNT_FIELD, ProblemCodes.INVALID));
        } else {
            amount = Money.of(typed);
            String amountProblem = OpeningBalanceRules.amountProblem(amount);
            if (amountProblem != null) {
                problems.add(new Problem(AMOUNT_FIELD, amountProblem));
                amount = null;
            }
        }
        LocalDate date = row.date();
        if (date == null) {
            problems.add(new Problem(DATE_FIELD, ProblemCodes.REQUIRED));
        } else {
            String dateProblem = OpeningBalanceRules.dateProblem(date, today);
            if (dateProblem != null) {
                problems.add(new Problem(DATE_FIELD, dateProblem));
            }
        }
        String reason = OpeningBalanceRules.reason(row.reason());
        if (reason != null && reason.codePointCount(0, reason.length()) > OpeningBalanceRules.MAX_REASON_LENGTH) {
            problems.add(new Problem(REASON_FIELD, ProblemCodes.TOO_LONG));
        }
        if (!problems.isEmpty()) {
            return null;
        }
        return new Valid(key(plotNumber), type, amount, date);
    }

    /** {@code UNCHANGED}, {@code OPENING_BALANCE_EXISTS} or {@code NEW}, from the plot's standing opening balances. */
    private static Item planAgainstStored(Row row, Valid valid, Resolved plot,
            Map<UUID, List<ChargeRecord>> standing) {
        Money other = null;
        for (ChargeRecord charge : standing.getOrDefault(plot.plotId(), List.of())) {
            if (!charge.getChargeTypeId().equals(valid.type().id()) || !charge.getAccruedOn().equals(valid.date())) {
                continue;
            }
            if (charge.getAmount().equals(valid.amount())) {
                return new Item(row.row(), Action.UNCHANGED, plot.plotId(), valid.type().id(), List.of());
            }
            other = charge.getAmount();
        }
        if (other != null) {
            return problem(row, AMOUNT_FIELD, ProblemCodes.OPENING_BALANCE_EXISTS, Map.of("amount", other.toString()));
        }
        return new Item(row.row(), Action.NEW, plot.plotId(), valid.type().id(), List.of());
    }

    /**
     * The target of every plot number of the rows. The Plots sheet is asked first (its first row of the number decides;
     * a later duplicate is reported there): a file row may create the plot or change the kind of a stored one; only
     * then an active plot. An archived plot is never matched.
     */
    private Map<String, Target> resolveTargets(List<PlotImportRow> plotRows, PlotImportPlan plotPlan,
            List<Row> rows) {
        Map<String, PlotImportItem> fileItems = new HashMap<>();
        Map<String, PlotImportRow> fileRows = new HashMap<>();
        for (int index = 0; index < plotRows.size(); index++) {
            String number = blank(plotRows.get(index).number());
            if (number != null && !fileItems.containsKey(key(number))) {
                fileItems.put(key(number), plotPlan.items().get(index));
                fileRows.put(key(number), plotRows.get(index));
            }
        }
        Set<String> numbers = new LinkedHashSet<>();
        for (Row row : rows) {
            String number = blank(row.plotNumber());
            if (number != null) {
                numbers.add(key(number));
            }
        }
        Map<String, PlotFacts> stored = new HashMap<>();
        for (PlotFacts plot : plots.findActiveByNumbers(numbers)) {
            stored.put(key(plot.number()), plot);
        }
        Map<String, Target> targets = new HashMap<>();
        for (String number : numbers) {
            PlotImportItem item = fileItems.get(number);
            PlotFacts plot = stored.get(number);
            if (item != null) {
                targets.put(number, ofFilePlot(item, fileRows.get(number), plot));
            } else if (plot == null) {
                targets.put(number, new Unknown());
            } else {
                targets.put(number, plot.commonLand() ? new CommonLand()
                        : new Resolved(plot.id(), plot.number(), false));
            }
        }
        return targets;
    }

    private static Target ofFilePlot(PlotImportItem item, PlotImportRow row, @Nullable PlotFacts stored) {
        if (item.action() == null || item.plotId() == null) {
            return new Suppressed();
        }
        String kind = blank(row.kind());
        boolean commonLand = kind == null ? stored != null && stored.commonLand() : COMMON_KIND.equalsIgnoreCase(kind);
        if (commonLand) {
            return new CommonLand();
        }
        boolean created = item.action() == PlotImportAction.NEW;
        String number = created || stored == null ? row.number().strip() : stored.number();
        return new Resolved(item.plotId(), number, created);
    }

    /** The opening balances of the stored plots the rows name that no reversal cancels, by plot. */
    private Map<UUID, List<ChargeRecord>> standingOpenings(Map<String, Target> targets) {
        Set<UUID> plotIds = new HashSet<>();
        for (Target target : targets.values()) {
            if (target instanceof Resolved plot && !plot.created()) {
                plotIds.add(plot.plotId());
            }
        }
        Map<UUID, List<ChargeRecord>> byPlot = new HashMap<>();
        if (!plotIds.isEmpty()) {
            for (ChargeRecord charge : charges.findStandingOfKind(plotIds, ChargeKind.OPENING)) {
                byPlot.computeIfAbsent(charge.getPlotId(), id -> new ArrayList<>()).add(charge);
            }
        }
        return byPlot;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Plan plan) {
        Set<UUID> plotIds = new HashSet<>();
        Set<UUID> typeIds = new TreeSet<>();
        for (Item item : plan.items()) {
            if (item.action() == Action.NEW) {
                plotIds.add(item.plotId());
                typeIds.add(item.chargeTypeId());
            }
        }
        // plots the same import creates are not there yet, so they are simply not found
        if (!plotIds.isEmpty()) {
            plots.findAllAndLockShared(plotIds);
        }
        // a handful of types at most; in id order, so two transactions never wait for each other in a circle
        typeIds.forEach(chargeTypes::findAndLockShared);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<AuditEvent> apply(UUID tenantId, UUID importId, List<Row> rows, Plan plan, Actor actor) {
        Map<UUID, ChargeTypeFacts> types = new HashMap<>();
        chargeTypes.all().forEach(type -> types.put(type.id(), type));
        // the booking month depends only on the month of the date, and stays locked until the end of the transaction
        Map<YearMonth, BillingMonthRecord> months = new HashMap<>();
        List<Integer> booked = new ArrayList<>();
        List<OpeningBalances.Booked> balances = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            Item item = plan.items().get(index);
            if (item.action() != Action.NEW) {
                continue;
            }
            Row row = rows.get(index);
            BillingMonthRecord month = months.computeIfAbsent(YearMonth.from(row.date()),
                    key -> billingMonths.openForBooking(tenantId, row.date()));
            balances.add(openingBalances.book(tenantId, item.plotId(), types.get(item.chargeTypeId()),
                    Money.of(row.amount()), row.date(), OpeningBalanceRules.reason(row.reason()),
                    "OPENING:" + importId + ":" + row.row(), actor.memberId(), month));
            booked.add(index);
        }
        if (balances.isEmpty()) {
            return List.of();
        }
        try {
            entityManager.flush();
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.violates(e, SOURCE_KEY_INDEX)) {
                throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.IMPORT_STALE,
                        "The opening balances changed after the preview; upload the file again.");
            }
            throw e;
        }

        Set<UUID> plotIds = new LinkedHashSet<>();
        balances.forEach(balance -> plotIds.add(balance.charge().getPlotId()));
        Map<UUID, PlotFacts> plotFacts = plots.findAll(plotIds);
        List<AuditEvent> entries = new ArrayList<>(balances.size());
        for (int i = 0; i < balances.size(); i++) {
            OpeningBalances.Booked balance = balances.get(i);
            UUID plotId = balance.charge().getPlotId();
            PlotFacts plot = plotFacts.get(plotId);
            String number = plot == null ? rows.get(booked.get(i)).plotNumber().strip() : plot.number();
            entries.add(audit.event(tenantId, actor, LedgerAuditPublisher.Action.OPENING_BALANCE_ENTERED, plotId,
                    number, balance.auditChanges()));
        }
        events.publishEvent(new ChargesBooked(tenantId, plotIds));
        return entries;
    }

    private static Item problem(Row row, String field, String code, Map<String, String> params) {
        return new Item(row.row(), null, null, null, List.of(new Problem(field, code, params)));
    }

    private static Set<String> typeNames(List<Row> rows) {
        Set<String> names = new LinkedHashSet<>();
        for (Row row : rows) {
            if (blank(row.chargeType()) != null) {
                names.add(row.chargeType());
            }
        }
        return names;
    }

    private static String key(String number) {
        return number.strip().toLowerCase(Locale.ROOT);
    }

    private static @Nullable String blank(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
