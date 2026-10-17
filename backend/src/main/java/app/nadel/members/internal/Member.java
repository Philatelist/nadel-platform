package app.nadel.members.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
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
