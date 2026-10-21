package app.nadel.tariffs.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.ChargeType;
import nadel.contract.model.ChargeTypeCreate;
import nadel.contract.model.ChargeTypeOrder;
import nadel.contract.model.ChargeTypeUpdate;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds, changes, reorders and archives charge types. Every method is one transaction that also publishes the audit
 * event(s). The cooperative always comes from the caller's {@link AccessProfile}, never from the request; the caller
 * must hold {@code TARIFFS_VIEW} and {@code TARIFFS_MANAGE} (checked on the controller).
 * <p>
 * Names are unique among the non-archived types ignoring letter case (the unique index
 * {@code charge_type_tenant_name_uq} is the guarantee). On top of the index a name must not equal the English or
 * Ukrainian default name of a seeded type that was never renamed, because clients show that default to the user.
 * A change to one type locks its row, a reorder locks every non-archived row, so such writes run one after another.
 */
@Service
class ChargeTypeCommands {

    private static final String NAME_INDEX = "charge_type_tenant_name_uq";

    private final ChargeTypeRepository types;
    private final TariffAuditPublisher audit;

    ChargeTypeCommands(ChargeTypeRepository types, TariffAuditPublisher audit) {
        this.types = types;
        this.audit = audit;
    }

    @Transactional
    ChargeType create(ChargeTypeCreate input, AccessProfile actor) {
        String name = cleanName(input.getName());
        String description = blankToNull(input.getDescription());
        ensureNameFree(types.findActiveInOrder(), name, null);

        int position = types.findMaxPosition().orElse(0) + 1;
        ChargeTypeRecord type = save(ChargeTypeRecord.custom(actor.tenantId(), name, description, position,
                Instant.now()));
        publish(actor, "CHARGE_TYPE_CREATED", type,
                ChargeTypeSnapshot.diff(ChargeTypeSnapshot.NONE, ChargeTypeSnapshot.of(type)));
        return ChargeTypeDtoMapper.toDto(type);
    }

    @Transactional
    ChargeType update(UUID id, ChargeTypeUpdate input, AccessProfile actor) {
        ChargeTypeRecord type = findActive(id, actor);
        String requested = cleanName(input.getName());
        String description = blankToNull(input.getDescription());
        boolean active = Boolean.TRUE.equals(input.getActive());

        String name = type.getName();
        boolean nameCustomized = type.isNameCustomized();
        // the client shows the default name of a type that was never renamed and may send it back: not a rename
        if (!isOwnDefault(type, requested) && !requested.equals(type.getName())) {
            ensureNameFree(types.findActiveInOrder(), requested, type.getId());
            name = requested;
            nameCustomized = true;
        }

        List<FieldChange> changes = ChargeTypeSnapshot.diff(ChargeTypeSnapshot.of(type),
                ChargeTypeSnapshot.of(name, description, active));
        if (!changes.isEmpty()) {
            type.apply(name, nameCustomized, description, active, Instant.now());
            save(type);
            publish(actor, "CHARGE_TYPE_UPDATED", type, changes);
        }
        return ChargeTypeDtoMapper.toDto(type);
    }

    @Transactional
    void archive(UUID id, AccessProfile actor) {
        ChargeTypeRecord type = findActive(id, actor);
        ChargeTypeSnapshot before = ChargeTypeSnapshot.of(type);
        type.archive(Instant.now());
        save(type);
        publish(actor, "CHARGE_TYPE_ARCHIVED", type, ChargeTypeSnapshot.diff(before, ChargeTypeSnapshot.of(type)));
    }

    /**
     * Sets the order of the list. The submitted ids must be exactly the non-archived types of the cooperative, each
     * once (the rows are locked first, so the set cannot change under the check); anything else is
     * {@code CHARGE_TYPE_ORDER_STALE}. Positions are renumbered from 1 and one audit entry is written for every type
     * whose position changed; the current order submitted again changes nothing.
     */
    @Transactional
    void reorder(ChargeTypeOrder input, AccessProfile actor) {
        List<ChargeTypeRecord> current = types.lockActiveInOrder();
        List<UUID> submitted = input.getIds();
        List<UUID> currentIds = current.stream().map(ChargeTypeRecord::getId).toList();
        if (submitted.size() != currentIds.size() || !new HashSet<>(submitted).equals(new HashSet<>(currentIds))) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.CHARGE_TYPE_ORDER_STALE,
                    "The list of charge types changed; reload it and order it again.");
        }
        if (submitted.equals(currentIds)) {
            return;
        }
        Map<UUID, ChargeTypeRecord> byId = current.stream()
                .collect(Collectors.toMap(ChargeTypeRecord::getId, Function.identity()));
        Instant now = Instant.now();
        for (int i = 0; i < submitted.size(); i++) {
            ChargeTypeRecord type = byId.get(submitted.get(i));
            int position = i + 1;
            if (type.getPosition() != position) {
                int old = type.getPosition();
                type.moveTo(position, now);
                publish(actor, "CHARGE_TYPE_REORDERED", type,
                        List.of(new FieldChange("position", String.valueOf(old), String.valueOf(position))));
            }
        }
        types.flush();
    }

    /** The charge type of the caller's cooperative (RLS hides others), locked; 404 when missing, 409 when archived. */
    private ChargeTypeRecord findActive(UUID id, AccessProfile actor) {
        ChargeTypeRecord type = types.findByIdForUpdate(id).filter(t -> t.getTenantId().equals(actor.tenantId()))
                .orElseThrow(ApiProblemException::notFound);
        if (type.getArchivedAt() != null) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.CHARGE_TYPE_ARCHIVED,
                    "The charge type is archived and cannot be changed.");
        }
        return type;
    }

    /** Saves and flushes, so a violation of the name index surfaces here as {@code CHARGE_TYPE_NAME_TAKEN}. */
    private ChargeTypeRecord save(ChargeTypeRecord type) {
        try {
            return types.saveAndFlush(type);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.violates(e, NAME_INDEX)) {
                throw nameTaken();
            }
            throw e;
        }
    }

    /**
     * Fast, friendly check against the non-archived types: the name of another type, or a default name of a seeded
     * type that was never renamed. The unique index remains the guarantee against a concurrent request.
     */
    private static void ensureNameFree(List<ChargeTypeRecord> active, String name, @Nullable UUID selfId) {
        for (ChargeTypeRecord other : active) {
            if (other.getId().equals(selfId)) {
                continue;
            }
            if (other.getName().equalsIgnoreCase(name) || isOwnDefault(other, name)) {
                throw nameTaken();
            }
        }
    }

    /** Whether the name is a default name (either language) of this seeded type while it is not renamed. */
    private static boolean isOwnDefault(ChargeTypeRecord type, String name) {
        return type.getCode() != null && !type.isNameCustomized()
                && DefaultChargeTypeNames.isDefaultOf(type.getCode(), name);
    }

    private void publish(AccessProfile actor, String action, ChargeTypeRecord type, List<FieldChange> changes) {
        audit.publish(Objects.requireNonNull(actor.tenantId()),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), action,
                TariffAuditPublisher.CHARGE_TYPE, type.getId(), type.getName(), changes);
    }

    /**
     * The trimmed name. The contract already limits the raw text to 1 to 100 characters, so only a blank name (spaces
     * only) is left to refuse here.
     */
    private static String cleanName(@Nullable String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("name", ProblemCodes.REQUIRED)));
        }
        return name;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static ApiProblemException nameTaken() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.CHARGE_TYPE_NAME_TAKEN,
                "A charge type with this name already exists.",
                List.of(new FieldProblem("name", ProblemCodes.CHARGE_TYPE_NAME_TAKEN)));
    }
}
