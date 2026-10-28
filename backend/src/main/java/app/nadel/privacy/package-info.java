/**
 * Encryption of personal data at rest (ADR 0016): AES-256-GCM sealing of single values, HMAC-SHA-256 blind indexes for
 * exact lookups, and the key hierarchy (one master key from the environment wraps a data key and an index key per
 * cooperative, stored in {@code tenant_data_key}).
 * <p>
 * Public API: {@link app.nadel.privacy.PersonalDataCipher} (for code running in a cooperative's transaction),
 * {@link app.nadel.privacy.OwnerKeyring} (for Flyway Java migrations and callbacks on the owner connection),
 * {@link app.nadel.privacy.FieldRef}, {@link app.nadel.privacy.IndexKind} and
 * {@link app.nadel.privacy.PersonalDataUnreadableException}. Keys, formats, the start-up key-check and master key
 * rotation are internal. Depends on {@code tenancy} and {@code problem}; no endpoints.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Personal data encryption")
package app.nadel.privacy;
