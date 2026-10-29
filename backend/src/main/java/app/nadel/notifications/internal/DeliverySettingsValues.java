package app.nadel.notifications.internal;

import app.nadel.notifications.internal.mail.SmtpSecurity;
import org.jspecify.annotations.Nullable;

/**
 * The delivery settings of a cooperative as stored: the secrets are the sealed bytes ({@code passwordEnc},
 * {@code tokenEnc}), never their plaintext. {@link #DEFAULTS} is a cooperative that never saved them.
 *
 * @param settingsVersion grows with every new bot token, so the bot of the cooperative restarts with it
 */
record DeliverySettingsValues(
        @Nullable String host,
        @Nullable Integer port,
        @Nullable SmtpSecurity security,
        @Nullable String user,
        @Nullable String fromAddress,
        @Nullable String fromName,
        byte @Nullable [] passwordEnc,
        byte @Nullable [] tokenEnc,
        @Nullable String tokenLast4,
        @Nullable String botUsername,
        int emailRatePerSecond,
        boolean notifyPayments,
        int settingsVersion) {

    static final DeliverySettingsValues DEFAULTS = new DeliverySettingsValues(null, null, null, null, null, null, null,
            null, null, null, 1, true, 1);

    /** A mailbox letters can be sent through: the server, its port and the sender's address are set. */
    boolean emailConfigured() {
        return host != null && port != null && fromAddress != null;
    }

    boolean passwordSet() {
        return passwordEnc != null;
    }

    boolean botConfigured() {
        return tokenEnc != null;
    }
}
