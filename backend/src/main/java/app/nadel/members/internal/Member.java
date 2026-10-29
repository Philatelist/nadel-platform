package app.nadel.members.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A person of the cooperative. The names stay readable (search and sort); the contacts and notes are sealed values
 * ({@code *_enc}, ADR 0016) that only {@link MemberCrypto} writes and opens, and {@code email_bidx} is the blind index
 * of the e-mail. Personal data: never log its fields.
 */
@Entity
@Table(name = "member")
class Member {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "middle_name")
    private String middleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_channel", nullable = false)
    private DeliveryChannel deliveryChannel;

    /** The phones as one sealed JSON array; null when there are none. */
    @Column(name = "phones_enc")
    private byte[] phonesEnc;

    @Column(name = "email_enc")
    private byte[] emailEnc;

    @Column(name = "postal_address_enc")
    private byte[] postalAddressEnc;

    @Column(name = "telegram_username_enc")
    private byte[] telegramUsernameEnc;

    @Column(name = "viber_phone_enc")
    private byte[] viberPhoneEnc;

    @Column(name = "notes_enc")
    private byte[] notesEnc;

    /** HMAC of the normalised e-mail: equality lookup and uniqueness among active members. */
    @Column(name = "email_bidx")
    private byte[] emailBidx;

    /** Keycloak user id, set on the first sign-in. */
    @Column(name = "keycloak_subject")
    private String keycloakSubject;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Member() {
    }

    /** A new active member of a cooperative. */
    static Member create(UUID tenantId, MemberData data, Instant now) {
        return create(UUID.randomUUID(), tenantId, data, now);
    }

    /** A new active member with a given id: the Excel import derives the id from the import, so a re-plan agrees. */
    static Member create(UUID id, UUID tenantId, MemberData data, Instant now) {
        Member member = new Member();
        member.id = id;
        member.tenantId = tenantId;
        member.createdAt = now;
        member.apply(data);
        member.updatedAt = now;
        return member;
    }

    /**
     * Overwrites the names and the channel with {@code data}; the contacts are sealed by {@link MemberCrypto#seal}
     * before the row is written. Does not stamp {@code updatedAt}: the caller calls {@link #touch} only when the
     * audited fields really changed.
     */
    void apply(MemberData data) {
        lastName = data.lastName();
        firstName = data.firstName();
        middleName = data.middleName();
        deliveryChannel = data.deliveryChannel();
    }

    /** The member's own choice of channel (feature 017); {@link MemberChannelCommands} checks the contact first. */
    void choose(DeliveryChannel channel, Instant now) {
        deliveryChannel = channel;
        updatedAt = now;
    }

    /** The sealed value of a contact field; null when the field is empty. */
    byte[] sealed(ContactField field) {
        return switch (field) {
            case PHONES -> phonesEnc;
            case EMAIL -> emailEnc;
            case POSTAL_ADDRESS -> postalAddressEnc;
            case TELEGRAM_USERNAME -> telegramUsernameEnc;
            case VIBER_PHONE -> viberPhoneEnc;
            case NOTES -> notesEnc;
        };
    }

    /** Stores the sealed value of a contact field (null clears it). */
    void seal(ContactField field, byte[] value) {
        switch (field) {
            case PHONES -> phonesEnc = value;
            case EMAIL -> emailEnc = value;
            case POSTAL_ADDRESS -> postalAddressEnc = value;
            case TELEGRAM_USERNAME -> telegramUsernameEnc = value;
            case VIBER_PHONE -> viberPhoneEnc = value;
            case NOTES -> notesEnc = value;
        }
    }

    byte[] getEmailBidx() {
        return emailBidx;
    }

    void setEmailBidx(byte[] emailBidx) {
        this.emailBidx = emailBidx;
    }

    void touch(Instant now) {
        updatedAt = now;
    }

    void archive(Instant now) {
        archivedAt = now;
        updatedAt = now;
    }

    void restore(Instant now) {
        archivedAt = null;
        updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    String getLastName() {
        return lastName;
    }

    String getFirstName() {
        return firstName;
    }

    String getMiddleName() {
        return middleName;
    }

    DeliveryChannel getDeliveryChannel() {
        return deliveryChannel;
    }

    String getKeycloakSubject() {
        return keycloakSubject;
    }

    Instant getArchivedAt() {
        return archivedAt;
    }

    boolean isArchived() {
        return archivedAt != null;
    }

    /** "Last First Middle" as written on invoices. */
    String displayName() {
        return middleName == null || middleName.isBlank()
                ? lastName + " " + firstName
                : lastName + " " + firstName + " " + middleName;
    }
}
