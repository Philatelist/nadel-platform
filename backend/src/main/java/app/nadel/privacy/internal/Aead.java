package app.nadel.privacy.internal;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM with the stored layout {@code [0x01 format][u16 BE key version][12-byte nonce][ciphertext ‖ 16-byte tag]}.
 * The associated data is the three header bytes followed by the caller's context, so neither the header nor the place
 * of a value can be changed without the tag failing. A fresh random nonce per value.
 */
final class Aead {

    static final byte FORMAT = 0x01;
    static final int HEADER_BYTES = 3;
    static final int NONCE_BYTES = 12;
    static final int TAG_BITS = 128;
    static final int MIN_BYTES = HEADER_BYTES + NONCE_BYTES + TAG_BITS / 8;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Aead() {
    }

    static byte[] seal(SecretKey key, int version, byte[] plaintext, byte[] context) {
        if (version < 0 || version > 0xFFFF) {
            throw new IllegalArgumentException("The key version must fit in two bytes");
        }
        byte[] header = {FORMAT, (byte) (version >>> 8), (byte) version};
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        byte[] sealed;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header);
            cipher.updateAAD(context);
            sealed = cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        }
        byte[] out = new byte[HEADER_BYTES + NONCE_BYTES + sealed.length];
        System.arraycopy(header, 0, out, 0, HEADER_BYTES);
        System.arraycopy(nonce, 0, out, HEADER_BYTES, NONCE_BYTES);
        System.arraycopy(sealed, 0, out, HEADER_BYTES + NONCE_BYTES, sealed.length);
        return out;
    }

    /** The key version in the header of a sealed value. */
    static int version(byte[] sealed) throws GeneralSecurityException {
        if (sealed == null || sealed.length < MIN_BYTES || sealed[0] != FORMAT) {
            throw new GeneralSecurityException("Not a sealed value");
        }
        return ((sealed[1] & 0xFF) << 8) | (sealed[2] & 0xFF);
    }

    static byte[] open(SecretKey key, byte[] sealed, byte[] context) throws GeneralSecurityException {
        version(sealed);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, HEADER_BYTES, NONCE_BYTES));
        cipher.updateAAD(sealed, 0, HEADER_BYTES);
        cipher.updateAAD(context);
        int offset = HEADER_BYTES + NONCE_BYTES;
        return cipher.doFinal(sealed, offset, sealed.length - offset);
    }
}
