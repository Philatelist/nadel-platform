package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.privacy.FieldRef;
import app.nadel.privacy.IndexKind;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.privacy.PersonalDataUnreadableException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The envelope of a sealed plan, with a cipher that only records the place and passes the text through: the place is
 * the run's {@code plan_enc}, and a value that opens to anything but an envelope is unreadable, never an empty plan.
 */
class SealedPlansTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();

    /** Passes the text through and remembers the last place; refuses another place than the run's plan. */
    private static final class PassThrough implements PersonalDataCipher {

        @Override
        public byte[] encrypt(UUID tenantId, FieldRef field, String plaintext) {
            assertThat(field).isEqualTo(new FieldRef("import_run", "plan_enc", RUN));
            return plaintext.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String decrypt(UUID tenantId, FieldRef field, byte[] sealed) {
            assertThat(field).isEqualTo(new FieldRef("import_run", "plan_enc", RUN));
            return new String(sealed, StandardCharsets.UTF_8);
        }

        @Override
        public byte[] blindIndex(UUID tenantId, IndexKind kind, String normalised) {
            throw new UnsupportedOperationException();
        }
    }

    private final SealedPlans plans = new SealedPlans(new PassThrough());

    @Test
    void theEnvelopeCarriesThePlanAndTheDigest() {
        byte[] sealed = plans.seal(TENANT, RUN, Map.of("version", 1, "plots", java.util.List.of()), "abc");

        assertThat(new String(sealed, StandardCharsets.UTF_8)).startsWith("{\"plan\":").endsWith("\"digest\":\"abc\"}");
        SealedPlans.Opened opened = plans.open(TENANT, RUN, sealed);
        assertThat(opened.plan()).containsEntry("version", 1).containsKey("plots");
        assertThat(opened.digest()).isEqualTo("abc");
    }

    @Test
    void aValueThatIsNotAnEnvelopeIsUnreadable() {
        for (String text : new String[] {"not json", "null", "[1]", "{\"digest\": \"abc\"}",
                "{\"plan\": [], \"digest\": \"abc\"}", "{\"plan\": {}, \"digest\": 7}"}) {
            assertThatThrownBy(() -> plans.open(TENANT, RUN, text.getBytes(StandardCharsets.UTF_8)))
                    .as(text).isInstanceOf(PersonalDataUnreadableException.class);
        }
    }
}
