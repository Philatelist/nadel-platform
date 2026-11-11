package app.nadel.notifications.internal.push;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The VAPID authorization of a push request (RFC 8292, feature 021): an ES256 JWT signed with the JDK's
 * {@code SHA256withECDSAinP1363Format} (the 64-byte {@code r ‖ s} form JWS wants), with {@code aud} the origin of the
 * endpoint, {@code exp} {@value #LIFETIME_HOURS} hours ahead (at most 24 h by RFC 8292) and {@code sub} the configured
 * contact. One token per audience is kept and reused until less than {@value #RENEW_MINUTES} minutes of it are left,
 * so a fan-out to thousands of devices of one push service signs once. The header is
 * {@code Authorization: vapid t=<jwt>, k=<public key>}.
 */
@Component
class VapidSigner {

    static final String ALGORITHM = "SHA256withECDSAinP1363Format";
    static final long LIFETIME_HOURS = 12;
    static final long RENEW_MINUTES = 60;

    private static final String HEADER = base64Url("{\"typ\":\"JWT\",\"alg\":\"ES256\"}"
            .getBytes(StandardCharsets.UTF_8));

    private record Token(String header, Instant expiresAt) {
    }

    private final VapidKeys keys;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.shared();
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    VapidSigner(VapidKeys keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    /** The {@code Authorization} header value for a request to the endpoint; push is on. */
    String authorization(URI endpoint) {
        String audience = origin(endpoint);
        Instant now = clock.instant();
        Token token = tokens.get(audience);
        if (token == null || token.expiresAt().minus(Duration.ofMinutes(RENEW_MINUTES)).isBefore(now)) {
            token = sign(audience, now);
            tokens.put(audience, token);
        }
        return token.header();
    }

    private Token sign(String audience, Instant now) {
        Instant expiresAt = now.plus(Duration.ofHours(LIFETIME_HOURS));
        ObjectNode claims = json.createObjectNode();
        claims.put("aud", audience);
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("sub", keys.subject());
        String signingInput = HEADER + "." + base64Url(json.writeValueAsBytes(claims));
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(keys.privateKey());
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            String jwt = signingInput + "." + base64Url(signer.sign());
            return new Token("vapid t=" + jwt + ", k=" + keys.publicKeyText(), expiresAt);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The VAPID token could not be signed", null);
        }
    }

    /** {@code scheme://host[:port]} of the endpoint, lower case, the port only when it is not the scheme's default. */
    static String origin(URI endpoint) {
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        String host = endpoint.getHost().toLowerCase(Locale.ROOT);
        int port = endpoint.getPort();
        boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    private static String base64Url(byte[] bytes) {
        return P256.base64Url(bytes);
    }
}
