package app.nadel.notifications.internal;

import app.nadel.notifications.internal.mail.SmtpSecurity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The delivery settings row of a cooperative (table {@code delivery_settings}, V29); the primary key is the
 * cooperative. The row is created by {@link DeliverySettingsRepository#insertDefaults} and changed only through
 * {@link DeliverySettingsService}, under a row lock. The secrets are the sealed bytes, never plaintext.
 */
@Entity
@Table(name = "delivery_settings")
class DeliverySettingsRow {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "smtp_host")
    private @Nullable String smtpHost;

    @Column(name = "smtp_port")
    private @Nullable Integer smtpPort;

    @Enumerated(EnumType.STRING)
    @Column(name = "smtp_security")
    private @Nullable SmtpSecurity smtpSecurity;

    @Column(name = "smtp_user")
    private @Nullable String smtpUser;

    @Column(name = "smtp_from_address")
    private @Nullable String smtpFromAddress;

    @Column(name = "smtp_from_name")
    private @Nullable String smtpFromName;

    @Column(name = "smtp_password_enc")
    private byte @Nullable [] smtpPasswordEnc;

    @Column(name = "bot_token_enc")
    private byte @Nullable [] botTokenEnc;

    @Column(name = "bot_token_last4")
    private @Nullable String botTokenLast4;

    @Column(name = "bot_username")
    private @Nullable String botUsername;

    @Column(name = "email_rate_per_second", nullable = false)
    private int emailRatePerSecond;

    @Column(name = "notify_payments", nullable = false)
    private boolean notifyPayments;

    @Column(name = "settings_version", nullable = false)
    private int settingsVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DeliverySettingsRow() {
    }

    UUID tenantId() {
        return tenantId;
    }

    /** The values as {@link DeliverySettingsService} works with them. */
    DeliverySettingsValues values() {
        return new DeliverySettingsValues(smtpHost, smtpPort, smtpSecurity, smtpUser, smtpFromAddress, smtpFromName,
                smtpPasswordEnc, botTokenEnc, botTokenLast4, botUsername, emailRatePerSecond, notifyPayments,
                settingsVersion);
    }

    /** Replaces every value; flushed by the transaction that locked the row. */
    void apply(DeliverySettingsValues values, Instant now) {
        smtpHost = values.host();
        smtpPort = values.port();
        smtpSecurity = values.security();
        smtpUser = values.user();
        smtpFromAddress = values.fromAddress();
        smtpFromName = values.fromName();
        smtpPasswordEnc = values.passwordEnc();
        botTokenEnc = values.tokenEnc();
        botTokenLast4 = values.tokenLast4();
        botUsername = values.botUsername();
        emailRatePerSecond = values.emailRatePerSecond();
        notifyPayments = values.notifyPayments();
        settingsVersion = values.settingsVersion();
        updatedAt = now;
    }
}
