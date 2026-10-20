package app.nadel.plots.internal;

import app.nadel.plots.OwnershipImportItem;
import app.nadel.plots.OwnershipImportOutcome;
import app.nadel.plots.OwnershipImportPlan;
import app.nadel.plots.OwnershipImportProblem;
import app.nadel.plots.OwnershipImportRow;
import app.nadel.plots.PlotImportItem;
import app.nadel.plots.PlotImportPlan;
import app.nadel.plots.PlotImportRow;
import app.nadel.plots.internal.OwnershipRules.Period;
import app.nadel.problem.ProblemCodes;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Plans the ownership rows of an Excel import (see {@code PlotImport#planOwnership}). Reads with bulk queries (plots by
 * number, ownership of the matched plots) and writes nothing.
 * <p>
 * The sum of the shares is checked on the file's own rows with {@link OwnershipRules#checkTimeline}, not mixed with
 * the recorded history: a plot's recorded timeline is valid by the rules that wrote it, and mixing the two would blame
 * the file for sums that include rows of past owners. History is used for what it decides: the date of new first
 * owners must be later than the end of every past owner ({@link OwnershipRules#checkChangeDate}).
 */
@Component
class OwnershipImportPlanner {

    private static final String PLOT_FIELD = "plotNumber";
    private static final String MEMBER_FIELD = "memberId";
    private static final String SHARE_FIELD = "share";
    private static final String DATE_FIELD = "validFrom";
    /** Upper bound of one {@code IN (...)} list. */
    private static final int CHUNK = 500;
    private static final Pattern WHOLE_NUMBER = Pattern.compile("[0-9]+");

    private final PlotRepository plots;
    private final OwnershipRepository ownerships;
    private final Clock clock;

    OwnershipImportPlanner(PlotRepository plots, OwnershipRepository ownerships, Clock clock) {
        this.plots = plots;
        this.ownerships = ownerships;
        this.clock = clock;
    }

    /** What a plot number of the Ownership sheet stands for. */
    private sealed interface Target {
    }

    /** No plot of the file and no active plot has this number. */
    private record Unknown() implements Target {
    }

    private record CommonLand() implements Target {
    }

    /** The plot's own row in the Plots sheet has a problem, which is reported there: nothing more to say. */
    private record Suppressed() implements Target {
    }

    private record Resolved(UUID plotId, boolean created) implements Target {
    }

    /** The rows of one plot with their parsed shares. */
    private record Group(String key, Target target, List<Integer> indexes) {
    }

    /** What is known about the rows while they are checked: their problems and the shares that parsed. */
    private record Checked(List<List<OwnershipImportProblem>> problems, Share[] shares) {

        void add(int index, OwnershipImportProblem problem) {
            problems.get(index).add(problem);
        }

        boolean hasProblem(List<Integer> indexes) {
            return indexes.stream().anyMatch(index -> !problems.get(index).isEmpty());
        }
    }

    OwnershipImportPlan plan(UUID tenantId, List<PlotImportRow> plotRows, PlotImportPlan plotPlan,
            List<OwnershipImportRow> rows) {
        LocalDate today = LocalDate.now(clock);
        Map<String, Plot> activeByNumber = activePlotsByNumber(tenantId, rows);
        Map<String, Target> targets = resolveTargets(plotRows, plotPlan, activeByNumber, rows);

        Map<String, Group> groups = new LinkedHashMap<>();
        Checked checked = new Checked(new ArrayList<>(), new Share[rows.size()]);
        for (int index = 0; index < rows.size(); index++) {
            checked.problems().add(new ArrayList<>());
            String key = key(rows.get(index).plotNumber());
            if (key == null) {
                checked.add(index, new OwnershipImportProblem(PLOT_FIELD, ProblemCodes.REQUIRED));
                continue;
            }
            groups.computeIfAbsent(key, k -> new Group(k, targets.get(k), new ArrayList<>())).indexes().add(index);
        }

        Map<UUID, List<Ownership>> recorded = recordedOwnership(groups.values());
        OwnershipImportOutcome[] outcomes = new OwnershipImportOutcome[rows.size()];
        UUID[] plotIds = new UUID[rows.size()];
        for (Group group : groups.values()) {
            checkGroup(group, rows, checked, recorded, today, outcomes, plotIds);
        }

        List<OwnershipImportItem> items = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            items.add(new OwnershipImportItem(rows.get(index).row(), outcomes[index], plotIds[index],
                    checked.problems().get(index)));
        }
        return new OwnershipImportPlan(items);
    }

    // ---- plots of the sheet

    private Map<String, Plot> activePlotsByNumber(UUID tenantId, List<OwnershipImportRow> rows) {
        List<String> numbers = rows.stream()
                .map(row -> key(row.plotNumber()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, Plot> byNumber = new HashMap<>();
        for (List<String> chunk : chunks(numbers)) {
            for (Plot plot : plots.findActiveByLowerNumbers(tenantId, chunk)) {
                byNumber.put(lower(plot.getNumber()), plot);
            }
        }
        return byNumber;
    }

    /**
     * The target of every plot number used by the sheet. A plot of the Plots sheet comes first (it may be new, or
     * change the kind of a stored plot); only then an active plot. An archived plot is never matched: its number is
     * unknown unless the Plots sheet has it, and then the import creates a new plot.
     */
    private static Map<String, Target> resolveTargets(List<PlotImportRow> plotRows, PlotImportPlan plotPlan,
            Map<String, Plot> activeByNumber, List<OwnershipImportRow> rows) {
        Map<String, PlotImportItem> fileItems = new HashMap<>();
        Map<String, PlotImportRow> fileRows = new HashMap<>();
        for (int index = 0; index < plotRows.size(); index++) {
            String key = key(plotRows.get(index).number());
            if (key != null && !fileItems.containsKey(key)) {
                fileItems.put(key, plotPlan.items().get(index));
                fileRows.put(key, plotRows.get(index));
            }
        }
        Map<String, Target> targets = new HashMap<>();
        for (OwnershipImportRow row : rows) {
            String key = key(row.plotNumber());
            if (key == null || targets.containsKey(key)) {
                continue;
            }
            PlotImportItem item = fileItems.get(key);
            Plot stored = activeByNumber.get(key);
            if (item != null) {
                targets.put(key, ofFilePlot(item, fileRows.get(key), stored));
            } else if (stored == null) {
                targets.put(key, new Unknown());
            } else {
                targets.put(key, stored.getKind() == PlotKind.COMMON ? new CommonLand() : new Resolved(stored.getId(), false));
            }
        }
        return targets;
    }

    private static Target ofFilePlot(PlotImportItem item, PlotImportRow row, @Nullable Plot stored) {
        if (item.action() == null || item.plotId() == null) {
            return new Suppressed();
        }
        PlotKind kind = row.kind() == null || row.kind().isBlank()
                ? (stored == null ? null : stored.getKind())
                : PlotImportService.parseKind(row.kind().strip());
        if (kind == PlotKind.COMMON) {
            return new CommonLand();
        }
        return new Resolved(item.plotId(), stored == null);
    }

    /** Every ownership row, current and past, of the stored plots the sheet names. */
    private Map<UUID, List<Ownership>> recordedOwnership(Iterable<Group> groups) {
        List<UUID> plotIds = new ArrayList<>();
        for (Group group : groups) {
            if (group.target() instanceof Resolved resolved && !resolved.created()) {
                plotIds.add(resolved.plotId());
            }
        }
        Map<UUID, List<Ownership>> byPlot = new HashMap<>();
        for (List<UUID> chunk : chunks(plotIds)) {
            for (Ownership ownership : ownerships.findAllByPlotIds(chunk)) {
                byPlot.computeIfAbsent(ownership.getPlotId(), id -> new ArrayList<>()).add(ownership);
            }
        }
        return byPlot;
    }

    // ---- one plot

    private void checkGroup(Group group, List<OwnershipImportRow> rows, Checked checked,
            Map<UUID, List<Ownership>> recorded, LocalDate today, OwnershipImportOutcome[] outcomes, UUID[] plotIds) {
        List<Integer> indexes = group.indexes();
        if (group.target() instanceof Suppressed) {
            return;
        }
        for (int index : indexes) {
            checkRow(rows.get(index), index, group.target(), checked, today);
        }
        if (!(group.target() instanceof Resolved resolved) || checked.hasProblem(indexes)
                || indexes.stream().anyMatch(index -> rows.get(index).memberId() == null)) {
            return;
        }

        checkDuplicatesAndDates(indexes, rows, checked);
        if (checked.hasProblem(indexes)) {
            return;
        }
        List<Period> newPeriods = indexes.stream()
                .map(index -> new Period(index, rows.get(index).memberId(), checked.shares()[index],
                        rows.get(index).validFrom(), null))
                .toList();
        if (OwnershipRules.checkTimeline(newPeriods, today).stream()
                .anyMatch(violation -> violation.kind() == OwnershipRules.Kind.SHARES_NOT_ONE)) {
            checked.add(indexes.getFirst(), new OwnershipImportProblem(SHARE_FIELD, ProblemCodes.SHARES_NOT_ONE));
            return;
        }

        OwnershipImportOutcome outcome = outcomeAgainstRecorded(group, resolved, rows, newPeriods, checked, recorded,
                today);
        if (outcome != null) {
            for (int index : indexes) {
                outcomes[index] = outcome;
                plotIds[index] = resolved.plotId();
            }
        }
    }

    private static void checkRow(OwnershipImportRow row, int index, Target target, Checked checked, LocalDate today) {
        if (target instanceof Unknown) {
            checked.add(index, new OwnershipImportProblem(PLOT_FIELD, ProblemCodes.UNKNOWN_PLOT));
        } else if (target instanceof CommonLand) {
            checked.add(index, new OwnershipImportProblem(PLOT_FIELD, ProblemCodes.PLOT_COMMON_LAND));
        }
        if (row.share() == null || row.share().isBlank()) {
            checked.add(index, new OwnershipImportProblem(SHARE_FIELD, ProblemCodes.REQUIRED));
        } else {
            try {
                checked.shares()[index] = parseShare(row.share().strip());
            } catch (InvalidShareException e) {
                checked.add(index, new OwnershipImportProblem(SHARE_FIELD, ProblemCodes.SHARE_INVALID));
            }
        }
        if (row.validFrom() == null) {
            checked.add(index, new OwnershipImportProblem(DATE_FIELD, ProblemCodes.REQUIRED));
        } else if (row.validFrom().isAfter(today)) {
            checked.add(index, new OwnershipImportProblem(DATE_FIELD, ProblemCodes.OWNERSHIP_DATE_INVALID));
        }
    }

    /** A member listed twice is a problem on the later row; a date other than the first row's is one on its row. */
    private static void checkDuplicatesAndDates(List<Integer> indexes, List<OwnershipImportRow> rows,
            Checked checked) {
        Set<UUID> members = new HashSet<>();
        LocalDate date = rows.get(indexes.getFirst()).validFrom();
        for (int index : indexes) {
            OwnershipImportRow row = rows.get(index);
            if (!members.add(row.memberId())) {
                checked.add(index, new OwnershipImportProblem(MEMBER_FIELD, ProblemCodes.DUPLICATE_OWNER));
            }
            if (!row.validFrom().equals(date)) {
                checked.add(index, new OwnershipImportProblem(DATE_FIELD, ProblemCodes.OWNERSHIP_DATE_MISMATCH));
            }
        }
    }

    /** The outcome for a plot whose rows are fine in themselves; null (with the problem recorded) when it conflicts. */
    private static @Nullable OwnershipImportOutcome outcomeAgainstRecorded(Group group, Resolved resolved,
            List<OwnershipImportRow> rows, List<Period> newPeriods, Checked checked,
            Map<UUID, List<Ownership>> recorded, LocalDate today) {
        List<Integer> indexes = group.indexes();
        List<Ownership> history = recorded.getOrDefault(resolved.plotId(), List.of());
        List<Ownership> current = history.stream().filter(o -> o.getValidTo() == null).toList();
        if (current.isEmpty()) {
            LocalDate validFrom = rows.get(indexes.getFirst()).validFrom();
            List<Period> past = history.stream()
                    .map(o -> new Period(o.getId(), o.getMemberId(), o.getShare(), o.getValidFrom(), o.getValidTo()))
                    .toList();
            if (OwnershipRules.checkChangeDate(validFrom, past, today).isPresent()) {
                checked.add(indexes.getFirst(), new OwnershipImportProblem(DATE_FIELD,
                        ProblemCodes.OWNERSHIP_DATE_INVALID));
                return null;
            }
            return OwnershipImportOutcome.FIRST_OWNERS;
        }
        Map<UUID, Share> recordedShares = new HashMap<>();
        current.forEach(o -> recordedShares.put(o.getMemberId(), o.getShare()));
        Map<UUID, Share> fileShares = new HashMap<>();
        newPeriods.forEach(period -> fileShares.put(period.memberId(), period.share()));
        if (recordedShares.equals(fileShares)) {
            return OwnershipImportOutcome.SKIP;
        }
        checked.add(indexes.getFirst(), new OwnershipImportProblem(PLOT_FIELD, ProblemCodes.OWNERSHIP_CONFLICT,
                Map.of("plot", rows.get(indexes.getFirst()).plotNumber().strip())));
        return null;
    }

    // ---- helpers

    /** {@code n/d} as for a change of owner; a bare {@code 1} is the whole plot, as the template says. */
    static Share parseShare(String text) {
        return Share.parse(WHOLE_NUMBER.matcher(text).matches() ? text + "/1" : text);
    }

    private static @Nullable String key(@Nullable String number) {
        if (number == null || number.isBlank()) {
            return null;
        }
        return lower(number.strip());
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    private static <T> List<List<T>> chunks(List<T> list) {
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < list.size(); from += CHUNK) {
            chunks.add(list.subList(from, Math.min(list.size(), from + CHUNK)));
        }
        return chunks;
    }
}
