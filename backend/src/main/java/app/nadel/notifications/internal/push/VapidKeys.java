package app.nadel.notifications.internal.push;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The server's VAPID key pair (RFC 8292, feature 021 §2.2) and whether push is on. Push is on only when
 * {@code nadel.webpush.public-key}, {@code private-key} and {@code subject} are all set, the public key is an
 * uncompressed P-256 point on the curve, the private key a scalar of the curve, the subject a {@code mailto:} or
 * {@code https:} URL, and a signature made with the private key verifies with the public key (so the two belong
 * together). Otherwise push is off for everyone, with one WARN line that names the reason and never a value. The
 * application always starts: push is an extra signal, not a delivery channel.
 * <p>
 * {@link #serverKeyId} is the first 8 bytes of SHA-256 of the public key's point; each subscription stores the id of
 * the key it was made with, so a subscription made with another key (after a rotation) is recognised and dropped.
 */
@Component
class VapidKeys {

    private static final Logger log = LoggerFactory.getLogger(VapidKeys.class);

    /** Why push is off. */
    enum Reason {
        /** One of the three values is not set. */
        MISSING,
        /** A key does not decode to a P-256 key, the point is not on the curve, or the subject is not a URL. */
        MALFORMED,
        /** The private key does not belong to the public key. */
        MISMATCH
    }

    private final @Nullable Reason off;
    private final @Nullable ECPublicKey publicKey;
    private final @Nullable ECPrivateKey privateKey;
    private final @Nullable String publicKeyText;
    private final @Nullable String subject;
    private final byte @Nullable [] serverKeyId;

    @Autowired
    VapidKeys(WebPushProperties properties) {
        this(properties.publicKey(), properties.privateKey(), properties.subject());
        if (off != null) {
            log.warn("Web push is off: the VAPID keys (NADEL_WEBPUSH_PUBLIC_KEY, NADEL_WEBPUSH_PRIVATE_KEY, "
                    + "NADEL_WEBPUSH_SUBJECT) are {}", off);
        }
    }

    /** Reads and checks the three values; never throws and never logs. */
    VapidKeys(@Nullable String publicText, @Nullable String privateText, @Nullable String subjectText) {
        Reason reason = null;
        ECPublicKey pub = null;
        ECPrivateKey priv = null;
        if (blank(publicText) || blank(privateText) || blank(subjectText)) {
            reason = Reason.MISSING;
        } else {
            pub = P256.fromBase64Url(publicText).flatMap(P256::publicKey).orElse(null);
            priv = P256.fromBase64Url(privateText).flatMap(P256::privateKey).orElse(null);
            String s = subjectText.strip();
            boolean url = (s.startsWith("mailto:") && s.length() > 7) || (s.startsWith("https://") && s.length() > 8);
            if (pub == null || priv == null || !url) {
                reason = Reason.MALFORMED;
            } else if (!pairs(pub, priv)) {
                reason = Reason.MISMATCH;
            }
        }
        this.off = reason;
        if (reason == null) {
            this.publicKey = pub;
            this.privateKey = priv;
            this.publicKeyText = P256.base64Url(P256.encode(Objects.requireNonNull(pub)));
            this.subject = Objects.requireNonNull(subjectText).strip();
            this.serverKeyId = keyId(P256.encode(pub));
        } else {
            this.publicKey = null;
            this.privateKey = null;
            this.publicKeyText = null;
            this.subject = null;
            this.serverKeyId = null;
        }
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    /** Whether a signature of the private key verifies with the public key. */
    private static boolean pairs(ECPublicKey pub, ECPrivateKey priv) {
        try {
            byte[] probe = new byte[32];
            new SecureRandom().nextBytes(probe);
            Signature signer = Signature.getInstance(VapidSigner.ALGORITHM);
            signer.initSign(priv);
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(VapidSigner.ALGORITHM);
            verifier.initVerify(pub);
            verifier.update(probe);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** The first 8 bytes of SHA-256 of the point. */
    static byte[] keyId(byte[] point) {
        try {
            return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(point), 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The JDK has no SHA-256", e);
        }
    }

    /** Whether push is on. */
    boolean enabled() {
        return off == null;
    }

    /** Why push is off; empty when it is on. */
    Optional<Reason> offReason() {
        return Optional.ofNullable(off);
    }

    /** The public key as the browser takes it ({@code applicationServerKey}): base64url, 87 characters; push on. */
    String publicKeyText() {
        return Objects.requireNonNull(publicKeyText, "push is off");
    }

    ECPublicKey publicKey() {
        return Objects.requireNonNull(publicKey, "push is off");
    }

    ECPrivateKey privateKey() {
        return Objects.requireNonNull(privateKey, "push is off");
    }

    String subject() {
        return Objects.requireNonNull(subject, "push is off");
    }

    /** The id of the key pair stored with each subscription; push on. */
    byte[] serverKeyId() {
        return Objects.requireNonNull(serverKeyId, "push is off").clone();
    }

    /** Never prints a key. */
    @Override
    public String toString() {
        return "VapidKeys[" + (off == null ? "on" : "off " + off) + "]";
    }
}
