package app.nadel.audit.internal;

import app.nadel.audit.ActorNames;
import app.nadel.audit.FieldChange;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.privacy.PersonalDataUnreadableException;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import nadel.contract.model.AuditAction;
import nadel.contract.model.AuditEntityType;
import nadel.contract.model.AuditEntry;
import nadel.contract.model.AuditFieldChange;
import nadel.contract.model.AuditPage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>
 * Sealed values ({@code {"enc": "<base64>"}}, ADR 0016) are opened only when the caller may see personal data
 * ({@code revealPersonal}); otherwise the change has {@code hidden: true} and no values. This is generic: any field
 * of any entity whose stored value is sealed is treated the same way.
 */
@Service
class AuditQueries {

    /** The calendar of the cooperatives: {@code from} and {@code to} are whole days in this zone. */
    static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    private static final Logger log = LoggerFactory.getLogger(AuditQueries.class);

    private final AuditRecordRepository records;
    private final ActorNames actorNames;
    private final JsonMapper json;
    private final PersonalDataCipher cipher;

    AuditQueries(AuditRecordRepository records, ActorNames actorNames, JsonMapper json, PersonalDataCipher cipher) {
        this.records = records;
        this.actorNames = actorNames;
        this.json = json;
        this.cipher = cipher;
    }

    /**
     * One page of entries, newest first. {@code from} and {@code to} are inclusive Europe/Kyiv dates: the range is
     * {@code [from 00:00, to + 1 day 00:00)} in Kyiv time.
     *
     * @param revealPersonal whether sealed personal values are opened (the caller may view members); otherwise such
     *                       changes come back hidden
     *
     * @throws ApiProblemException 400 {@code VALIDATION_FAILED} when {@code from} is after {@code to}
     */
    @Transactional(readOnly = true)
    AuditPage list(UUID tenantId, @Nullable AuditEntityType entityType, @Nullable UUID entityId,
            @Nullable UUID actorMemberId, @Nullable LocalDate from, @Nullable LocalDate to, int page, int size,
            boolean revealPersonal) {
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

        List<AuditEntry> items = result.getContent().stream()
                .map(r -> toDto(r, names, tenantId, revealPersonal)).toList();
        return new AuditPage(items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    private AuditEntry toDto(AuditRecord record, Map<UUID, String> names, UUID tenantId, boolean revealPersonal) {
        var dto = new AuditEntry(record.getId(), record.getOccurredAt().atOffset(ZoneOffset.UTC),
                AuditAction.fromValue(record.getAction()), AuditEntityType.fromValue(record.getEntityType()),
                record.getEntityId(), changesOf(record.getChangesJson(), record.getId(), tenantId, revealPersonal));
        dto.setActorMemberId(record.getActorMemberId());
        if (record.getActorMemberId() != null) {
            dto.setActorName(names.get(record.getActorMemberId()));
        }
        dto.setEntityLabel(record.getEntityLabel());
        return dto;
    }

    /**
     * Stored shape {@code [{"field", "old", "new"}]} to the API shape; null values stay absent. A change with a sealed
     * value ({@code {"enc": ...}}) is opened when {@code revealPersonal}, otherwise both values are left out and it is
     * {@code hidden}; a sealed value that does not open hides the change as well and logs the entry id only.
     */
    List<AuditFieldChange> changesOf(String stored, UUID entryId, UUID tenantId, boolean revealPersonal) {
        JsonNode array = json.readTree(stored);
        return array.valueStream().map(node -> {
            String field = node.path("field").asString();
            var change = new AuditFieldChange(field);
            JsonNode oldNode = node.get("old");
            JsonNode newNode = node.get("new");
            if (!isSealed(oldNode) && !isSealed(newNode)) {
                change.setOldValue(textOrNull(oldNode));
                change.setNewValue(textOrNull(newNode));
            } else if (!revealPersonal) {
                change.setHidden(true);
            } else {
                try {
                    change.setOldValue(open(oldNode, tenantId, () -> FieldChange.oldValueRef(entryId, field)));
                    change.setNewValue(open(newNode, tenantId, () -> FieldChange.newValueRef(entryId, field)));
                } catch (PersonalDataUnreadableException | IllegalArgumentException e) {
                    log.warn("PERSONAL_DATA_UNREADABLE: a sealed value of audit entry {} could not be opened", entryId);
                    change.setOldValue(null);
                    change.setNewValue(null);
                    change.setHidden(true);
                }
            }
            return change;
        }).toList();
    }

    /** A plain value as text, or a sealed value opened at its place. */
    private @Nullable String open(@Nullable JsonNode node, UUID tenantId, Supplier<FieldRef> ref) {
        if (!isSealed(node)) {
            return textOrNull(node);
        }
        byte[] sealed = Base64.getDecoder().decode(node.get("enc").asString());
        return cipher.decrypt(tenantId, ref.get(), sealed);
    }

    private static boolean isSealed(@Nullable JsonNode node) {
        return node != null && node.isObject() && node.has("enc");
    }

    private static @Nullable String textOrNull(@Nullable JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }
}
