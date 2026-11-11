package app.nadel.notifications;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A synthetic browser subscription for the backend tests (feature 021, the fake push rule of ADR 0021): its own P-256
 * key pair and auth secret, an endpoint on {@link FakePushService}, and an RFC 8291 decryption written apart from the
 * production code (so a mistake in one is not repeated in the other), as the browser would decrypt a push.
 */
public final class TestPushDevice {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final String id = UUID.randomUUID().toString();
    private final KeyPair keys;
    private final byte[] publicPoint;
    private final byte[] auth = new byte[16];

    public TestPushDevice() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            keys = generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        publicPoint = point((ECPublicKey) keys.getPublic());
        new SecureRandom().nextBytes(auth);
    }

    /** The id of its endpoint on the fake service. */
    public String id() {
        return id;
    }

    /** Its endpoint on the shared fake service. */
    public String endpoint() {
        return FakePushService.shared().endpoint(id);
    }

    /** The {@code p256dh} key, base64url. */
    public String p256dh() {
        return B64.encodeToString(publicPoint);
    }

    /** The {@code auth} secret, base64url. */
    public String auth() {
        return B64.encodeToString(auth);
    }

    /** {@code PushSubscription.toJSON()} with a language and a label, the body of {@code POST /me/push/devices}. */
    public Map<String, Object> registration(String language, String label) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("endpoint", endpoint());
        body.put("keys", Map.of("p256dh", p256dh(), "auth", auth()));
        body.put("language", language);
        if (label != null) {
            body.put("label", label);
        }
        return body;
    }

    /** Decrypts an {@code aes128gcm} push body sent to this device; the plaintext as UTF-8 text. */
    public String decrypt(byte[] body) {
        return decrypt(body, keys.getPrivate(), publicPoint, auth);
    }

    /** RFC 8291 decryption of one record with the receiver's private key, public point and auth secret. */
    public static String decrypt(byte[] body, PrivateKey uaPrivate, byte[] uaPublic, byte[] auth) {
        try {
            ByteBuffer in = ByteBuffer.wrap(body);
            byte[] salt = new byte[16];
            in.get(salt);
            int rs = in.getInt();
            int idLength = in.get() & 0xff;
            byte[] asPublic = new byte[idLength];
            in.get(asPublic);
            byte[] record = new byte[in.remaining()];
            in.get(record);
            if (rs < 18 || record.length > rs) {
                throw new IllegalStateException("bad record size");
            }

            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(uaPrivate);
            agreement.doPhase(publicKey(asPublic), true);
            byte[] shared = agreement.generateSecret();

            byte[] prkKey = hmac(auth, shared);
            byte[] keyInfo = join("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[] {0}, uaPublic,
                    asPublic, new byte[] {1});
            byte[] ikm = hmac(prkKey, keyInfo);
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, join("Content-Encoding: aes128gcm".getBytes(StandardCharsets
                    .US_ASCII), new byte[] {0, 1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, join("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII),
                    new byte[] {0, 1})), 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] padded = cipher.doFinal(record);
            int end = padded.length - 1;
            while (end >= 0 && padded[end] == 0) {
                end--;
            }
            if (end < 0 || padded[end] != 2) {
                throw new IllegalStateException("no last-record delimiter");
            }
            return new String(padded, 0, end, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The push body does not decrypt", e);
        }
    }

    private static ECPublicKey publicKey(byte[] point) throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(point, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(point, 33, 65));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
    }

    /** The uncompressed point of a public key. */
    public static byte[] point(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        put(key.getW().getAffineX(), out, 1);
        put(key.getW().getAffineY(), out, 33);
        return out;
    }

    private static void put(BigInteger value, byte[] out, int offset) {
        byte[] bytes = value.toByteArray();
        int start = Math.max(0, bytes.length - 32);
        System.arraycopy(bytes, start, out, offset + 32 - (bytes.length - start), bytes.length - start);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] join(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }
}
