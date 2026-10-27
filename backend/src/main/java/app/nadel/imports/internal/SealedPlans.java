package app.nadel.imports.internal;

import app.nadel.privacy.FieldRef;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.privacy.PersonalDataUnreadableException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stored plan of a preview, sealed (ADR 0016): {@code import_run.plan_enc} holds the envelope
 * {@code {"plan": <normalised rows>, "digest": <PlanDigest>}} sealed at {@code FieldRef("import_run", "plan_enc", runId)}
 * with the run's cooperative, so neither the rows nor their digest are readable in the database, and a plan copied
 * into another run does not open. The Java migration 25.1 builds the same envelope with {@link #envelope} and
 * {@link #ref}.
 * <p>
 * A plan that does not open is a {@link PersonalDataUnreadableException} (500). Personal data: never log it.
 */
@Component
class SealedPlans {

    static final String TABLE = "import_run";
    static final String COLUMN = "plan_enc";

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final PersonalDataCipher cipher;

    SealedPlans(PersonalDataCipher cipher) {
        this.cipher = cipher;
    }

    /** The opened envelope: the stored rows and the digest of the preview. */
    record Opened(Map<String, Object> plan, @Nullable String digest) {
    }

    /** Seals the rows and the digest of a preview of the current cooperative for the run {@code runId}. */
    byte[] seal(UUID tenantId, UUID runId, Map<String, Object> plan, String digest) {
        return cipher.encrypt(tenantId, ref(runId), envelope(plan, digest));
    }

    /**
     * @throws PersonalDataUnreadableException when the value was sealed for another run, cooperative or key, or opens
     *                                         to something else than an envelope
     */
    @SuppressWarnings("unchecked")
    Opened open(UUID tenantId, UUID runId, byte[] sealed) {
        String json = cipher.decrypt(tenantId, ref(runId), sealed);
        try {
            Map<String, Object> envelope = JsonMapper.shared().readValue(json, MAP);
            if (!(envelope.get("plan") instanceof Map<?, ?> plan)) {
                throw new PersonalDataUnreadableException();
            }
            return new Opened((Map<String, Object>) plan, (String) envelope.get("digest"));
        } catch (JacksonException | ClassCastException | NullPointerException e) {
            // the value opened but is not the envelope this module seals: unreadable, never an empty plan
            throw new PersonalDataUnreadableException();
        }
    }

    /** The place of a run's sealed plan: the associated data of its encryption. */
    static FieldRef ref(UUID runId) {
        return new FieldRef(TABLE, COLUMN, runId);
    }

    /** The JSON text that is sealed: the plan (a map or a JSON tree) and the digest. */
    static String envelope(Object plan, @Nullable String digest) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("plan", plan);
        envelope.put("digest", digest);
        return JsonMapper.shared().writeValueAsString(envelope);
    }
}
