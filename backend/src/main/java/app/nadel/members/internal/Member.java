package app.nadel.members.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A person of the cooperative. Personal data: never log its fields. */
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

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> phones;

    /** Stored trimmed and lower-case (CHECK in the database). */
    private String email;

    @Column(name = "postal_address")
    private String postalAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_channel", nullable = false)
    private DeliveryChannel deliveryChannel;

    @Column(name = "telegram_username")
    private String telegramUsername;

    @Column(name = "viber_phone")
    private String viberPhone;

    private String notes;

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
        Member member = new Member();
        member.id = UUID.randomUUID();
        member.tenantId = tenantId;
        member.createdAt = now;
        member.apply(data);
        member.updatedAt = now;
        return member;
    }

    /**
     * Overwrites every editable field with {@code data}. Does not stamp {@code updatedAt}: the caller calls
     * {@link #touch} only when the audited fields really changed. The stored phone order is kept when only the order
     * differs, so such an edit changes nothing.
     */
    void apply(MemberData data) {
        lastName = data.lastName();
        firstName = data.firstName();
        middleName = data.middleName();
        if (phones == null || !Set.copyOf(phones).equals(Set.copyOf(data.phones()))
                || phones.size() != data.phones().size()) {
            phones = new ArrayList<>(data.phones());
        }
        email = data.email();
        postalAddress = data.postalAddress();
        deliveryChannel = data.deliveryChannel();
        telegramUsername = data.telegramUsername();
        viberPhone = data.viberPhone();
        notes = data.notes();
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

    List<String> getPhones() {
        return phones;
    }

    String getEmail() {
        return email;
    }

    String getPostalAddress() {
        return postalAddress;
    }

    DeliveryChannel getDeliveryChannel() {
        return deliveryChannel;
    }

    String getTelegramUsername() {
        return telegramUsername;
    }

    String getViberPhone() {
        return viberPhone;
    }

    String getNotes() {
        return notes;
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
