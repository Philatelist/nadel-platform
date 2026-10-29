package app.nadel.notifications.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.members.MemberContacts;
import app.nadel.members.MemberDirectory;
import app.nadel.notifications.internal.mail.SmtpSecurity;
import app.nadel.notifications.internal.mail.SmtpSettings;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import app.nadel.tenancy.TenantContext;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes the delivery settings of the current cooperative (feature 017 §2.1): its mailbox, its bot, the
 * e-mail rate and the payment-message switch. A cooperative without a row has {@link DeliverySettingsValues#DEFAULTS}.
 * <p>
 * The mailbox password and the bot token are sealed with the cooperative's data key (ADR 0016, ADR 0018) at
 * {@code FieldRef("delivery_settings", "smtp_password_enc" | "bot_token_enc", tenantId)} and opened only to connect;
 * they never leave this class except as {@link SmtpSettings} and {@link BotToken} for the channel that uses them. A
 * change publishes {@code DELIVERY_SETTINGS_CHANGED} in its transaction; a changed secret is listed with
 * {@code sealed = true} and no values, so neither the outbox nor the audit log ever holds it.
 * <p>
 * The cooperative comes from the token ({@code TenantContext}, the caller's profile), never from the request; the
 * caller holds {@code SETTINGS_MANAGE} (checked on the controller).
 */
@Service
class DeliverySettingsService {

    static final String TABLE = "delivery_settings";
    static final String PASSWORD_COLUMN = "smtp_password_enc";
    static final String TOKEN_COLUMN = "bot_token_enc";
    static final String ACTION = "DELIVERY_SETTINGS_CHANGED";
    static final String ENTITY_TYPE = "DELIVERY_SETTINGS";
    /** A code, not a phrase: the client names the settings in the reader's language. */
    static final String LABEL = "delivery-settings";

    /**
     * The mailbox of a change: replaces every saved field (null or blank clears it) except the password, which an
     * absent or empty value keeps. Without a user the password is removed.
     */
    record EmailChange(@Nullable String host, @Nullable Integer port, @Nullable SmtpSecurity security,
            @Nullable String user, @Nullable String password, @Nullable String fromAddress,
            @Nullable String fromName) {

        /** Never prints the password. */
        @Override
        public String toString() {
            return "EmailChange[password=" + (password == null || password.isEmpty() ? "<keep>" : "<new>") + "]";
        }
    }

    /** A change of the settings: a null field keeps its value; an absent or empty bot token keeps the saved one. */
    record Change(@Nullable EmailChange email, @Nullable String botToken, @Nullable Integer emailRatePerSecond,
            @Nullable Boolean notifyPayments) {

        /** Never prints a secret. */
        @Override
        public String toString() {
            return "Change[email=" + email + ", botToken=" + (botToken == null || botToken.isEmpty() ? "<keep>"
                    : "<new>") + ", emailRatePerSecond=" + emailRatePerSecond + ", notifyPayments=" + notifyPayments
                    + "]";
        }
    }

    /** The saved bot token, opened, with the settings version it belongs to. */
    record BotToken(String token, int settingsVersion) {

        @Override
        public String toString() {
            return "BotToken[version=" + settingsVersion + "]";
        }
    }

    /** What the test letter needs: the opened mailbox, the signed-in member's own e-mail and the cooperative's name. */
    record TestLetter(SmtpSettings smtp, String to, String cooperativeName) {

        @Override
        public String toString() {
            return "TestLetter[" + smtp + "]";
        }
    }

    private final DeliverySettingsRepository settings;
    private final PersonalDataCipher cipher;
    private final MemberDirectory members;
    private final TenantDirectory tenants;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    DeliverySettingsService(DeliverySettingsRepository settings, PersonalDataCipher cipher, MemberDirectory members,
            TenantDirectory tenants, ApplicationEventPublisher events, Clock clock) {
        this.settings = settings;
        this.cipher = cipher;
        this.members = members;
        this.tenants = tenants;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    DeliverySettingsValues values(AccessProfile viewer) {
        return valuesOf(viewer.tenantId());
    }

    /**
     * Applies the change under a lock of the cooperative's row. Saving the current values (the same password or token
     * included) writes nothing and records nothing.
     */
    @Transactional
    DeliverySettingsValues update(Change change, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        Optional<DeliverySettingsRow> row = settings.lockById(tenantId);
        DeliverySettingsValues before = row.map(DeliverySettingsRow::values).orElse(DeliverySettingsValues.DEFAULTS);
        Outcome outcome = apply(tenantId, before, change);
        if (outcome.changes().isEmpty()) {
            return before;
        }
        if (row.isEmpty()) {
            settings.insertDefaults(tenantId);
            row = settings.lockById(tenantId);
            // another first save may have won the insert: apply the change to what it saved
            outcome = apply(tenantId, row.orElseThrow().values(), change);
            if (outcome.changes().isEmpty()) {
                return outcome.values();
            }
        }
        row.orElseThrow().apply(outcome.values(), clock.instant());
        events.publishEvent(AuditEvent.now(tenantId, new Actor(actor.memberId(), actor.subject(),
                actor.displayName()), ACTION, ENTITY_TYPE, tenantId, LABEL, outcome.changes()));
        return outcome.values();
    }

    /** The mailbox of the bound cooperative, opened; empty when it is not set up. */
    @Transactional(readOnly = true)
    Optional<SmtpSettings> mailbox() {
        UUID tenantId = boundTenant();
        DeliverySettingsValues values = valuesOf(tenantId);
        if (!values.emailConfigured()) {
            return Optional.empty();
        }
        return Optional.of(new SmtpSettings(values.host(), values.port(), values.security(), values.user(),
                open(tenantId, PASSWORD_COLUMN, values.passwordEnc()), values.fromAddress(), values.fromName()));
    }

    /** The bot token of the bound cooperative, opened; empty when none is saved. */
    @Transactional(readOnly = true)
    Optional<BotToken> botToken() {
        UUID tenantId = boundTenant();
        DeliverySettingsValues values = valuesOf(tenantId);
        String token = open(tenantId, TOKEN_COLUMN, values.tokenEnc());
        return token == null ? Optional.empty() : Optional.of(new BotToken(token, values.settingsVersion()));
    }

    /**
     * The test letter of the signed-in member: 409 {@code DELIVERY_NOT_CONFIGURED} without a mailbox, 409
     * {@code NO_OWN_EMAIL} when the member has no e-mail of their own.
     */
    @Transactional(readOnly = true)
    TestLetter testLetter(AccessProfile actor) {
        SmtpSettings smtp = mailbox().orElseThrow(() -> new ApiProblemException(HttpStatus.CONFLICT,
                ProblemCodes.DELIVERY_NOT_CONFIGURED, "The cooperative's mailbox is not set up."));
        String email = actor.memberId() == null ? null
                : Optional.ofNullable(members.contacts(Set.of(actor.memberId())).get(actor.memberId()))
                        .map(MemberContacts::email).orElse(null);
        if (email == null || email.isBlank()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.NO_OWN_EMAIL,
                    "The signed-in member has no e-mail to send the test letter to.");
        }
        String name = tenants.current().map(TenantSummary::name).orElse("");
        return new TestLetter(smtp, email, name);
    }

    /** Keeps the bot's @name while the token it belongs to is still the saved one. */
    @Transactional
    void rememberBotUsername(int settingsVersion, String username) {
        settings.rememberBotUsername(boundTenant(), settingsVersion, username);
    }

    private DeliverySettingsValues valuesOf(@Nullable UUID tenantId) {
        if (tenantId == null) {
            return DeliverySettingsValues.DEFAULTS;
        }
        return settings.findById(tenantId).map(DeliverySettingsRow::values).orElse(DeliverySettingsValues.DEFAULTS);
    }

    /** The values after the change and the audit changes between them. */
    private Outcome apply(UUID tenantId, DeliverySettingsValues before, Change change) {
        List<FieldChange> changes = new ArrayList<>();
        EmailChange email = change.email();
        String host = email == null ? before.host() : text(email.host());
        Integer port = email == null ? before.port() : email.port();
        SmtpSecurity security = email == null ? before.security() : email.security();
        String user = email == null ? before.user() : text(email.user());
        String fromAddress = email == null ? before.fromAddress() : text(email.fromAddress());
        String fromName = email == null ? before.fromName() : text(email.fromName());
        byte[] passwordEnc = before.passwordEnc();
        if (user == null) {
            passwordEnc = null;
        } else if (email != null && email.password() != null && !email.password().isEmpty()
                && !email.password().equals(open(tenantId, PASSWORD_COLUMN, before.passwordEnc()))) {
            passwordEnc = seal(tenantId, PASSWORD_COLUMN, email.password());
        }

        byte[] tokenEnc = before.tokenEnc();
        String tokenLast4 = before.tokenLast4();
        String botUsername = before.botUsername();
        int version = before.settingsVersion();
        String token = change.botToken();
        if (token != null && !token.isEmpty() && !token.equals(open(tenantId, TOKEN_COLUMN, before.tokenEnc()))) {
            tokenEnc = seal(tenantId, TOKEN_COLUMN, token);
            tokenLast4 = token.substring(token.length() - 4);
            botUsername = null;
            version++;
        }
        int rate = change.emailRatePerSecond() == null ? before.emailRatePerSecond() : change.emailRatePerSecond();
        boolean notifyPayments = change.notifyPayments() == null ? before.notifyPayments() : change.notifyPayments();

        plain(changes, "host", before.host(), host);
        plain(changes, "port", before.port(), port);
        plain(changes, "security", before.security(), security);
        plain(changes, "user", before.user(), user);
        if (passwordEnc != before.passwordEnc()) {
            changes.add(new FieldChange("password", null, null, true));
        }
        plain(changes, "fromAddress", before.fromAddress(), fromAddress);
        plain(changes, "fromName", before.fromName(), fromName);
        if (tokenEnc != before.tokenEnc()) {
            changes.add(new FieldChange("botToken", null, null, true));
        }
        plain(changes, "emailRatePerSecond", before.emailRatePerSecond(), rate);
        plain(changes, "notifyPayments", before.notifyPayments(), notifyPayments);
        return new Outcome(new DeliverySettingsValues(host, port, security, user, fromAddress, fromName, passwordEnc,
                tokenEnc, tokenLast4, botUsername, rate, notifyPayments, version), List.copyOf(changes));
    }

    private static void plain(List<FieldChange> changes, String field, @Nullable Object before,
            @Nullable Object after) {
        if (!Objects.equals(before, after)) {
            changes.add(new FieldChange(field, before == null ? null : before.toString(),
                    after == null ? null : after.toString()));
        }
    }

    /** A setting text: trimmed, a blank one is cleared. */
    private static @Nullable String text(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private byte[] seal(UUID tenantId, String column, String secret) {
        return cipher.encrypt(tenantId, new FieldRef(TABLE, column, tenantId), secret);
    }

    private @Nullable String open(UUID tenantId, String column, byte @Nullable [] sealed) {
        return sealed == null ? null : cipher.decrypt(tenantId, new FieldRef(TABLE, column, tenantId), sealed);
    }

    private static UUID boundTenant() {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("No cooperative is bound"));
    }

    private record Outcome(DeliverySettingsValues values, List<FieldChange> changes) {
    }
}
