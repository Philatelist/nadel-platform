package app.nadel.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Validation of the master keys: the message names the variable and never contains what was supplied. */
class MasterKeysTests {

    static final String CURRENT_MESSAGE = "NADEL_PD_MASTER_KEY is missing or is not 32 bytes of base64";
    static final String PREVIOUS_MESSAGE = "NADEL_PD_MASTER_KEY_PREVIOUS is missing or is not 32 bytes of base64";

    @Test
    void aMissingCurrentKeyNamesTheVariable() {
        for (String missing : Arrays.asList(null, "", "   ")) {
            assertThatThrownBy(() -> MasterKeys.of(missing, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(CURRENT_MESSAGE)
                    .hasNoCause();
        }
    }

    @Test
    void aMalformedCurrentKeyNamesTheVariableButNeverTheValue() {
        String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);
        String thirtyThreeBytes = key(33);
        for (String malformed : List.of("not*base64!secret-value", sixteenBytes, thirtyThreeBytes, "QUJD")) {
            assertThatThrownBy(() -> MasterKeys.of(malformed, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(CURRENT_MESSAGE)
                    .hasNoCause()
                    .message().doesNotContain(malformed);
        }
    }

    @Test
    void aMalformedPreviousKeyNamesItsVariableButNeverTheValue() {
        String current = key(32);
        String malformed = "previous*secret*value";

        assertThatThrownBy(() -> MasterKeys.of(current, malformed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(PREVIOUS_MESSAGE)
                .hasNoCause()
                .message().doesNotContain(malformed).doesNotContain(current);
        assertThatThrownBy(() -> MasterKeys.of(current, key(31)))
                .hasMessage(PREVIOUS_MESSAGE).hasNoCause();
    }

    @Test
    void aValidKeyWithSurroundingWhitespaceIsAccepted() {
        String encoded = key(32);

        MasterKeys keys = MasterKeys.of("  " + encoded + "\n", " ");

        assertThat(keys.previous()).isEmpty();
        assertThat(keys.current().id()).isEqualTo(idOf(encoded));
    }

    @Test
    void theIdIsTheFirstEightBytesOfTheSha256() {
        String encoded = key(32);

        MasterKeys.Key key = MasterKeys.of(encoded, null).current();

        assertThat(key.id()).hasSize(8).isEqualTo(idOf(encoded));
        assertThat(key.secret().getEncoded()).isEqualTo(Base64.getDecoder().decode(encoded));
        assertThat(key.toString()).doesNotContain(encoded).startsWith("MasterKey[id=");
    }

    @Test
    void keysAreFoundByIdAndAPreviousKeyEqualToTheCurrentOneIsIgnored() {
        String current = key(32);
        String previous = key(32);

        MasterKeys rotation = MasterKeys.of(current, previous);
        MasterKeys same = MasterKeys.of(current, current);

        assertThat(rotation.byId(idOf(current))).containsSame(rotation.current());
        assertThat(rotation.byId(idOf(previous))).containsSame(rotation.previous().orElseThrow());
        assertThat(rotation.byId(new byte[8])).isEmpty();
        assertThat(same.previous()).isEmpty();
        assertThat(same.byId(idOf(previous))).isEmpty();
        assertThat(MasterKeys.of(current, null).byId(idOf(previous))).isEmpty();
    }

    @Test
    void theIdReturnedIsACopy() {
        MasterKeys.Key key = MasterKeys.of(key(32), null).current();
        byte[] id = key.id();
        id[0] ^= 1;

        assertThat(key.matches(id)).isFalse();
        assertThat(key.matches(key.id())).isTrue();
    }

    static String key(int bytes) {
        byte[] raw = new byte[bytes];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    static byte[] idOf(String encoded) {
        try {
            return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(encoded)), 8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
