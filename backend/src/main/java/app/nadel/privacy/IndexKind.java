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
    PHONE_LAST4,
    /**
     * The canonical content of a document (feature 016: an invoice snapshot with its names in plaintext), so a change
     * is detected without a hash of plaintext names.
     */
    DOCUMENT_CONTENT,
    /**
     * A Telegram chat id (feature 017: the chat a member linked to the cooperative's bot), as the decimal text Telegram
     * sends, so the bot finds the link of an incoming chat without opening every sealed chat id.
     */
    TELEGRAM_CHAT
}
