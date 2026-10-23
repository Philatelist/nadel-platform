package app.nadel.tariffs;

import app.nadel.audit.AuditEvent;
import app.nadel.identity.AccessProfile;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import nadel.contract.model.TariffVersionCreate;
import org.jspecify.annotations.Nullable;

/**
 * Changes of tariff versions that a recalculation books together with the change (feature 012 §2.4): a version added
 * (back-dated), corrected or deleted although charges used it. The ledger calls it inside its own transaction (a
 * preview rolls the change back, a booking keeps it), so every method requires a running transaction
 * ({@code Propagation.MANDATORY}) and opens none.
 * <p>
 * Each method takes the row locks and runs the validation of the tariff endpoints ({@code POST}, {@code PUT} and
 * {@code DELETE /tariffs/{id}/versions}): the tariff {@code FOR UPDATE}, its charge type {@code FOR SHARE} (404 when
 * unknown or of another cooperative, 409 {@code CHARGE_TYPE_ARCHIVED}), the field problems of the terms (400
 * {@code VALIDATION_FAILED}, fields as on the endpoint: {@code rate}, {@code frequency}, {@code dueDate}), a start date
 * another version has (409 {@code TARIFF_VERSION_DATE_TAKEN}), the last version (409 {@code TARIFF_LAST_VERSION}),
 * but <b>no</b> use check: a version that charges used is changed here, and the recalculation corrects the charges.
 * A deletion is soft ({@code deleted_at}): the charge parts of the version keep pointing at it. The audit entry of the
 * change is returned, not published: the caller publishes it with the recalculation's entry, or drops it with a
 * rolled-back preview.
 */
public interface TariffVersionChanges {

    /**
     * A change applied in the caller's transaction.
     *
     * @param tariffId     the tariff of the version
     * @param chargeTypeId the charge type of the tariff
     * @param versionId    the version added, corrected or deleted (an added version's id is new on every call)
     * @param terms        the terms of the version after the change; null for a deletion
     * @param note         the note after the change (user content); null when none or for a deletion
     * @param from         the first day whose version may have changed: the earliest start of the old and the new
     *                     validity of the version
     * @param to           the last such day: the latest end of the old and the new validity (the day before the next
     *                     version starts); null when either runs on without end
     * @param audit        the audit entry of the change ({@code TARIFF_VERSION_ADDED}, {@code TARIFF_VERSION_UPDATED}
     *                     or {@code TARIFF_VERSION_DELETED}, old and new values), not published; null when an update
     *                     changed nothing
     */
    record Change(UUID tariffId, UUID chargeTypeId, UUID versionId, @Nullable VersionTerms terms,
            @Nullable String note, LocalDate from, @Nullable LocalDate to, @Nullable AuditEvent audit) {

        public Change {
            Objects.requireNonNull(tariffId, "tariffId");
            Objects.requireNonNull(chargeTypeId, "chargeTypeId");
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(from, "from");
        }
    }

    /** Adds a version to the tariff, whatever its start date. */
    Change add(UUID tariffId, TariffVersionCreate input, AccessProfile actor);

    /** Replaces the terms and the note of the version (404 when unknown, deleted or of another cooperative). */
    Change update(UUID versionId, TariffVersionCreate input, AccessProfile actor);

    /** Deletes the version softly (404 when unknown, deleted or of another cooperative). */
    Change delete(UUID versionId, AccessProfile actor);
}
