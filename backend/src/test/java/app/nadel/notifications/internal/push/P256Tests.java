package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The P-256 point checks that stand between a browser's key and ECDH: only an uncompressed point ({@code 0x04}, 65
 * bytes) whose coordinates lie in the field and satisfy the curve equation becomes a key. A coordinate that is only
 * congruent to a valid one (out of the field) or negative is refused, so no invalid-curve point reaches the JDK, whose
 * key factory does not check.
 */
class P256Tests {

    private static final BigInteger P = ((ECFieldFp) P256.PARAMS.getCurve().getField()).getP();
    private static final BigInteger GX = P256.PARAMS.getGenerator().getAffineX();
    private static final BigInteger GY = P256.PARAMS.getGenerator().getAffineY();

    @Test
    void theGeneratorIsOnTheCurveButNotItsCoordinatesOutsideTheField() {
        assertThat(P256.onCurve(GX, GY)).isTrue();
        assertThat(P256.onCurve(GX, GY.add(P))).isFalse();
        assertThat(P256.onCurve(GX.add(P), GY)).isFalse();
        assertThat(P256.onCurve(GX, GY.subtract(P))).isFalse();
        assertThat(P256.onCurve(GX.subtract(P), GY)).isFalse();
        assertThat(P256.onCurve(GX, GY.add(BigInteger.ONE))).isFalse();
    }

    @Test
    void onlyAnUncompressedPointOfTheCurveBecomesAKey() {
        KeyPair pair = P256.generate();
        byte[] point = P256.encode((ECPublicKey) pair.getPublic());

        assertThat(P256.publicKey(point)).hasValueSatisfying(key -> assertThat(P256.encode(key)).isEqualTo(point));

        byte[] otherPrefix = point.clone();
        otherPrefix[0] = 0x03;
        assertThat(P256.publicKey(otherPrefix)).isEmpty();
        assertThat(P256.publicKey(Arrays.copyOf(point, 64))).isEmpty();
        assertThat(P256.publicKey(Arrays.copyOf(point, 66))).isEmpty();

        byte[] xBeyondTheField = point.clone();
        Arrays.fill(xBeyondTheField, 1, 1 + P256.SCALAR_BYTES, (byte) 0xFF);
        assertThat(P256.publicKey(xBeyondTheField)).isEmpty();
    }

    @Test
    void aPrivateScalarMustBeThirtyTwoBytesBetweenOneAndTheOrder() {
        byte[] one = new byte[P256.SCALAR_BYTES];
        one[P256.SCALAR_BYTES - 1] = 1;
        byte[] order = P256.PARAMS.getOrder().toByteArray();

        assertThat(P256.privateKey(one)).hasValueSatisfying(key -> assertThat(key.getS()).isEqualTo(BigInteger.ONE));
        assertThat(P256.encode(P256.privateKey(one).orElseThrow())).isEqualTo(one);
        assertThat(P256.privateKey(new byte[P256.SCALAR_BYTES])).isEmpty();
        assertThat(P256.privateKey(Arrays.copyOfRange(order, order.length - P256.SCALAR_BYTES, order.length)))
                .isEmpty();
        assertThat(P256.privateKey(new byte[P256.SCALAR_BYTES + 1])).isEmpty();
    }

    @Test
    void base64UrlIsReadWithOrWithoutPaddingAndAnythingElseIsEmpty() {
        assertThat(P256.base64Url(new byte[] {(byte) 0xFB, (byte) 0xFF})).isEqualTo("-_8");
        assertThat(P256.fromBase64Url(" -_8= ")).hasValueSatisfying(
                bytes -> assertThat(bytes).containsExactly((byte) 0xFB, (byte) 0xFF));
        assertThat(P256.fromBase64Url("+/8=")).isEmpty();
    }
}
