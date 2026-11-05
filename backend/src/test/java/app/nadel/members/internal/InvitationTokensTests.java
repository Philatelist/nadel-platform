package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The one-time tokens of invitation links (feature 020): random, URL-safe, stored only as their SHA-256. */
class InvitationTokensTests {

    @Test
    void aTokenIsThirtyTwoRandomBytesInBase64UrlWithoutPadding() {
        String token = InvitationTokens.newToken();

        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(InvitationTokens.TOKEN_BYTES);
    }

    @Test
    void tokensDoNotRepeat() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            tokens.add(InvitationTokens.newToken());
        }
        assertThat(tokens).hasSize(1000);
    }

    @Test
    void theStoredFormIsTheSha256OfTheTokensText() throws Exception {
        String token = InvitationTokens.newToken();

        byte[] hash = InvitationTokens.hash(token);

        assertThat(hash).hasSize(32)
                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        assertThat(InvitationTokens.hash(token)).isEqualTo(hash);
        assertThat(InvitationTokens.hash(token + "x")).isNotEqualTo(hash);
        assertThat(InvitationTokens.hash("")).hasSize(32); // any text has a hash: a mistyped token simply finds nothing
    }
}
