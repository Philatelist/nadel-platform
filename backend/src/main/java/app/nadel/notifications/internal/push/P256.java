package app.nadel.notifications.internal.push;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * The NIST P-256 curve with the JDK only (feature 021, ADR 0021): the uncompressed point form of RFC 8291 and RFC 8292
 * ({@code 0x04 ‖ x ‖ y}, 65 bytes), its check that a point lies on the curve (the JDK's key factory does not check it,
 * and an invalid-curve point could leak the private key through ECDH), the 32-byte private scalar, and base64url.
 */
final class P256 {

    /** The length of an uncompressed point. */
    static final int POINT_BYTES = 65;

    /** The length of a coordinate and of a private scalar. */
    static final int SCALAR_BYTES = 32;

    static final ECParameterSpec PARAMS = params();

    private static final BigInteger P = ((ECFieldFp) PARAMS.getCurve().getField()).getP();
    private static final BigInteger A = PARAMS.getCurve().getA();
    private static final BigInteger B = PARAMS.getCurve().getB();

    private P256() {
    }

    private static ECParameterSpec params() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The JDK has no P-256", e);
        }
    }

    /** The public key of an uncompressed point, or empty when the bytes are not a point of the curve. */
    static Optional<ECPublicKey> publicKey(byte[] point) {
        if (point.length != POINT_BYTES || point[0] != 0x04) {
            return Optional.empty();
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(point, 1, 1 + SCALAR_BYTES));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(point, 1 + SCALAR_BYTES, POINT_BYTES));
        if (!onCurve(x, y)) {
            return Optional.empty();
        }
        try {
            return Optional.of((ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), PARAMS)));
        } catch (GeneralSecurityException e) {
            return Optional.empty();
        }
    }

    /** Whether {@code y² = x³ + ax + b (mod p)} with both coordinates in the field. */
    static boolean onCurve(BigInteger x, BigInteger y) {
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(P) >= 0 || y.compareTo(P) >= 0) {
            return false;
        }
        BigInteger left = y.multiply(y).mod(P);
        BigInteger right = x.pow(3).add(A.multiply(x)).add(B).mod(P);
        return left.equals(right);
    }

    /** The private key of a 32-byte scalar, or empty when it is not in {@code [1, n)}. */
    static Optional<ECPrivateKey> privateKey(byte[] scalar) {
        if (scalar.length != SCALAR_BYTES) {
            return Optional.empty();
        }
        BigInteger s = new BigInteger(1, scalar);
        if (s.signum() <= 0 || s.compareTo(PARAMS.getOrder()) >= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of((ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new ECPrivateKeySpec(s, PARAMS)));
        } catch (GeneralSecurityException e) {
            return Optional.empty();
        }
    }

    /** The uncompressed point of a public key. */
    static byte[] encode(ECPublicKey key) {
        byte[] point = new byte[POINT_BYTES];
        point[0] = 0x04;
        copyScalar(key.getW().getAffineX(), point, 1);
        copyScalar(key.getW().getAffineY(), point, 1 + SCALAR_BYTES);
        return point;
    }

    /** The 32-byte scalar of a private key. */
    static byte[] encode(ECPrivateKey key) {
        byte[] scalar = new byte[SCALAR_BYTES];
        copyScalar(key.getS(), scalar, 0);
        return scalar;
    }

    /** A new random key pair. */
    static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(PARAMS);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The JDK cannot make a P-256 key pair", e);
        }
    }

    /** Base64url without padding. */
    static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Decodes base64url with or without padding; empty when it is not base64url. */
    static Optional<byte[]> fromBase64Url(String text) {
        try {
            return Optional.of(Base64.getUrlDecoder().decode(text.strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Writes an unsigned big integer as exactly 32 big-endian bytes at {@code offset}. */
    private static void copyScalar(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int start = Math.max(0, bytes.length - SCALAR_BYTES);
        int length = bytes.length - start;
        System.arraycopy(bytes, start, target, offset + SCALAR_BYTES - length, length);
    }
}
