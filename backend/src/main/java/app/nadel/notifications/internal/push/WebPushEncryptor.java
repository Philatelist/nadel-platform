package app.nadel.notifications.internal.push;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.function.Supplier;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Message encryption for Web Push (RFC 8291, content coding {@code aes128gcm} of RFC 8188), JDK only (feature 021,
 * ADR 0021). Per message: a new ephemeral P-256 key pair and a random 16-byte salt; the ECDH secret with the browser's
 * key ({@code p256dh}); {@code PRK_key = HMAC-SHA-256(auth, ecdh)}, {@code IKM = HMAC-SHA-256(PRK_key, "WebPush: info"
 * ‖ 0x00 ‖ ua_public ‖ as_public ‖ 0x01)}; {@code PRK = HMAC-SHA-256(salt, IKM)}; the content key the first 16 bytes
 * of {@code HMAC-SHA-256(PRK, "Content-Encoding: aes128gcm" ‖ 0x00 ‖ 0x01)} and the nonce the first 12 bytes of
 * {@code HMAC-SHA-256(PRK, "Content-Encoding: nonce" ‖ 0x00 ‖ 0x01)}; one record, the plaintext followed by the
 * last-record delimiter {@code 0x02}, sealed with AES-128-GCM. The body is the header {@code salt ‖ rs (4096, u32) ‖
 * idlen (65) ‖ as_public} and the record. Checked against the example of RFC 8291 §5 byte for byte.
 */
@Component
class WebPushEncryptor {

    /** The record size of the header: one record holds at most {@code rs - 17} bytes of plaintext. */
    static final int RECORD_SIZE = 4096;

    /** The most plaintext one message carries (one record: the delimiter and the 16-byte tag take the rest). */
    static final int MAX_PLAINTEXT = RECORD_SIZE - 17;

    private static final byte[] KEY_INFO = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ONE = {1};
    private static final byte LAST_RECORD = 0x02;

    private final Supplier<KeyPair> ephemeral;
    private final Supplier<byte[]> salts;

    WebPushEncryptor() {
        this(P256::generate, WebPushEncryptor::randomSalt);
    }

    /** An encryptor with fixed ephemeral keys and salts (the RFC example). */
    WebPushEncryptor(Supplier<KeyPair> ephemeral, Supplier<byte[]> salts) {
        this.ephemeral = ephemeral;
        this.salts = salts;
    }

    private static byte[] randomSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /**
     * The encrypted body for the browser's key and auth secret.
     *
     * @param uaPublic the browser's key ({@code p256dh}), an uncompressed point already checked to be on the curve
     * @param auth     the browser's 16-byte auth secret
     */
    byte[] encrypt(byte[] uaPublic, byte[] auth, byte[] plaintext) {
        if (plaintext.length > MAX_PLAINTEXT) {
            throw new IllegalArgumentException("A push message holds at most " + MAX_PLAINTEXT + " bytes");
        }
        ECPublicKey ua = P256.publicKey(uaPublic)
                .orElseThrow(() -> new IllegalArgumentException("The browser key is not a P-256 point"));
        KeyPair as = ephemeral.get();
        byte[] asPublic = P256.encode((ECPublicKey) as.getPublic());
        byte[] salt = salts.get();
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(as.getPrivate());
            agreement.doPhase(ua, true);
            byte[] ecdhSecret = agreement.generateSecret();

            byte[] prkKey = hmac(auth, ecdhSecret);
            byte[] ikm = hmac(prkKey, concat(KEY_INFO, uaPublic, asPublic, ONE));
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat(CEK_INFO, ONE)), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat(NONCE_INFO, ONE)), 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] record = cipher.doFinal(concat(plaintext, new byte[] {LAST_RECORD}));

            return ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + record.length)
                    .put(salt)
                    .putInt(RECORD_SIZE)
                    .put((byte) asPublic.length)
                    .put(asPublic)
                    .put(record)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The push message could not be encrypted", null);
        }
    }

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(length);
        for (byte[] part : parts) {
            buffer.put(part);
        }
        return buffer.array();
    }
}
