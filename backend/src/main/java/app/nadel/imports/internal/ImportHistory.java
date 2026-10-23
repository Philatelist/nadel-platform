package app.nadel.imports.internal;

import app.nadel.audit.ActorNames;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.ImportCounts;
import nadel.contract.model.ImportEntityCounts;
import nadel.contract.model.ImportPage;
import nadel.contract.model.ImportRunStatus;
import nadel.contract.model.ImportSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The import history of one cooperative: every upload, newest first. The cooperative comes from the caller's profile;
 * row level security applies as well. The stored plan is never read.
 * <p>
 * "Expired" is not stored: a preview that is still {@code PREVIEW} once its expiry passed (on the same Kyiv clock the
 * commit uses) is shown as {@code EXPIRED}. File names and names of people are never logged.
 */
@Service
class ImportHistory {

    private final ImportRunRepository runs;
    private final ActorNames actorNames;
    private final Clock clock;

    ImportHistory(ImportRunRepository runs, ActorNames actorNames, Clock clock) {
        this.runs = runs;
        this.actorNames = actorNames;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    ImportPage list(UUID tenantId, int page, int size) {
        Page<ImportHistoryRow> result = runs.history(tenantId, PageRequest.of(page, size));
        Set<UUID> authors = new HashSet<>();
        result.getContent().forEach(row -> {
            if (row.createdByMemberId() != null) {
                authors.add(row.createdByMemberId());
            }
        });
        // the audit log shows the current name of the member; a member that no longer exists keeps the name from the upload
        Map<UUID, String> names = authors.isEmpty() ? Map.of() : actorNames.displayNames(authors);
        Instant now = clock.instant();
        List<ImportSummary> items = result.getContent().stream().map(row -> toSummary(row, names, now)).toList();
        return new ImportPage(items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    private static ImportSummary toSummary(ImportHistoryRow row, Map<UUID, String> names, Instant now) {
        String uploadedBy = row.createdByMemberId() == null
                ? row.createdByName()
                : names.getOrDefault(row.createdByMemberId(), row.createdByName());
        ImportSummary summary = new ImportSummary(row.id(), row.fileName(), uploadedBy,
                row.createdAt().atOffset(ZoneOffset.UTC), statusOf(row, now), countsOf(row.counts()),
                row.problemCount());
        if (row.committedAt() != null) {
            summary.setCommittedAt(row.committedAt().atOffset(ZoneOffset.UTC));
        }
        return summary;
    }

    static ImportRunStatus statusOf(ImportHistoryRow row, Instant now) {
        return switch (row.status()) {
            case PREVIEW -> now.isAfter(row.expiresAt()) ? ImportRunStatus.EXPIRED : ImportRunStatus.PREVIEW;
            case HAS_ERRORS -> ImportRunStatus.HAS_ERRORS;
            case COMMITTED -> ImportRunStatus.COMMITTED;
            case STALE -> ImportRunStatus.STALE;
        };
    }

    /**
     * The stored {@code {"plots": {"created", "updated", "unchanged"}, ...}}; a missing part counts as zero (an upload
     * from before the Meters sheet has no {@code meters}, one from before feature 011 no {@code waterConnections} and
     * no {@code openingBalances}).
     */
    private static ImportCounts countsOf(Map<String, Object> stored) {
        return new ImportCounts(entityCounts(stored.get("plots")), entityCounts(stored.get("members")),
                entityCounts(stored.get("ownerships")), entityCounts(stored.get("meters")),
                entityCounts(stored.get("waterConnections")), entityCounts(stored.get("openingBalances")));
    }

    private static ImportEntityCounts entityCounts(Object stored) {
        Map<?, ?> map = stored instanceof Map<?, ?> m ? m : Map.of();
        return new ImportEntityCounts(number(map.get("created")), number(map.get("updated")),
                number(map.get("unchanged")));
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
