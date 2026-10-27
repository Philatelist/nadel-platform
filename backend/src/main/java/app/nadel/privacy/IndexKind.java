package app.nadel.privacy;

/**
 * What a blind index is computed over. The kind is part of the HMAC input, so an e-mail and a phone with the same text
 * never share an index value.
 */
public enum IndexKind {
    /** An e-mail, stripped and in lower case. */
    EMAIL,
    /** A phone in international form ({@code PhoneNumber}). */
    PHONE,
    /** The last four digits of a normalised phone. */
    PHONE_LAST4
}
