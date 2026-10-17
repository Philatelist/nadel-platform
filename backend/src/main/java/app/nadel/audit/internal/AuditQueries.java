package app.nadel.audit.internal;

import app.nadel.audit.ActorNames;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.AuditAction;
import nadel.contract.model.AuditEntityType;
import nadel.contract.model.AuditEntry;
import nadel.contract.model.AuditFieldChange;
import nadel.contract.model.AuditPage;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the audit log of one cooperative. The cooperative is passed in by the controller from the caller's profile;
 * row level security applies as well.
 */
@Service
class AuditQueries {

    /** The calendar of the cooperatives: {@code from} and {@code to} are whole days in this zone. */
    static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    private final AuditRecordRepository records;
    private final ActorNames actorNames;
    private final JsonMapper json;

    AuditQueries(AuditRecordRepository records, ActorNames actorNames, JsonMapper json) {
        this.records = records;
        this.actorNames = actorNames;
        this.json = json;
    }

    /**
     * One page of entries, newest first. {@code from} and {@code to} are inclusive Europe/Kyiv dates: the range is
     * {@code [from 00:00, to + 1 day 00:00)} in Kyiv time.
     *
     * @throws ApiProblemException 400 {@code VALIDATION_FAILED} when {@code from} is after {@code to}
     */
    @Transactional(readOnly = true)
    AuditPage list(UUID tenantId, @Nullable AuditEntityType entityType, @Nullable UUID entityId,
            @Nullable UUID actorMemberId, @Nullable LocalDate from, @Nullable LocalDate to, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The first day must not be after the last day.",
                    List.of(new FieldProblem("from", ProblemCodes.INVALID, "from must not be after to")));
        }
        Instant start = from == null ? null : from.atStartOfDay(KYIV).toInstant();
        Instant endExclusive = to == null ? null : to.plusDays(1).atStartOfDay(KYIV).toInstant();

        Page<AuditRecord> result = records.search(tenantId, entityType == null ? null : entityType.getValue(),
                entityId, actorMemberId, start, endExclusive, PageRequest.of(page, size));

        Set<UUID> actors = new HashSet<>();
        result.getContent().forEach(r -> {
            if (r.getActorMemberId() != null) {
                actors.add(r.getActorMemberId());
            }
        });
        Map<UUID, String> names = actors.isEmpty() ? Map.of() : actorNames.displayNames(actors);

        List<AuditEntry> items = result.getContent().stream().map(r -> toDto(r, names)).toList();
        return new AuditPage(items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    private AuditEntry toDto(AuditRecord record, Map<UUID, String> names) {
        var dto = new AuditEntry(record.getId(), record.getOccurredAt().atOffset(ZoneOffset.UTC),
                AuditAction.fromValue(record.getAction()), AuditEntityType.fromValue(record.getEntityType()),
                record.getEntityId(), changesOf(record.getChangesJson()));
        dto.setActorMemberId(record.getActorMemberId());
        if (record.getActorMemberId() != null) {
            dto.setActorName(names.get(record.getActorMemberId()));
        }
        dto.setEntityLabel(record.getEntityLabel());
        return dto;
    }

    /** Stored shape {@code [{"field", "old", "new"}]} to the API shape; null values stay absent. */
    private List<AuditFieldChange> changesOf(String stored) {
        JsonNode array = json.readTree(stored);
        return array.valueStream().map(node -> {
            var change = new AuditFieldChange(node.path("field").asString());
            change.setOldValue(textOrNull(node.get("old")));
            change.setNewValue(textOrNull(node.get("new")));
            return change;
        }).toList();
    }

    private static @Nullable String textOrNull(@Nullable JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }
}
