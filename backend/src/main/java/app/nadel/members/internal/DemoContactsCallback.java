package app.nadel.members.internal;

import app.nadel.privacy.OwnerKeyring;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the contacts of the demo members (spec 014). {@code R__demo_data.sql} inserts the demo members with names
 * only and the {@code PAPER} channel, because plain SQL cannot seal; right after that script, inside its transaction
 * on the owner's connection, this callback reads {@value #CONTACTS} and seals each member's contacts through
 * {@link OwnerKeyring}, sets {@code email_bidx}, the delivery channel (in the same statement as the contacts, so the
 * channel rule on the ciphertext holds) and the {@code member_phone_index} rows. The demo users sign in by e-mail
 * ({@code chairman@demo.local}), which works through {@code email_bidx}.
 * <p>
 * Registered only when the {@code db/demo} location is active (the {@code dev} profile and the integration tests),
 * and runs only after {@code R__demo_data.sql}, which Flyway re-applies whenever the script changes. Idempotent: every
 * run writes the same contacts again, as the script itself used to. A listed member that does not exist is skipped.
 * The log has counts only.
 */
@Component
@ConditionalOnExpression("'${spring.flyway.locations:}'.contains('db/demo')")
class DemoContactsCallback implements Callback {

    static final String SCRIPT = "R__demo_data.sql";
    static final String CONTACTS = "db/demo/demo-contacts.json";
    private static final Logger log = LoggerFactory.getLogger(DemoContactsCallback.class);
    private static final TypeReference<List<DemoContacts>> LIST = new TypeReference<>() {
    };

    private static final String UPDATE = """
            update member set phones_enc = ?, email_enc = ?, postal_address_enc = ?, telegram_username_enc = ?,
                viber_phone_enc = ?, notes_enc = ?, email_bidx = ?, delivery_channel = ?
            where id = ?
            """;

    private final OwnerKeyring keyring;

    DemoContactsCallback(OwnerKeyring keyring) {
        this.keyring = keyring;
    }

    /** One entry of {@value #CONTACTS}: the plaintext contacts and the channel of a demo member. */
    record DemoContacts(UUID memberId, @Nullable List<String> phones, @Nullable String email,
            @Nullable String postalAddress, @Nullable String telegramUsername, @Nullable String viberPhone,
            @Nullable String notes, @Nullable String deliveryChannel) {
    }

    @Override
    public boolean supports(Event event, Context context) {
        if (event != Event.AFTER_EACH_MIGRATE || context == null) {
            return false;
        }
        MigrationInfo migration = context.getMigrationInfo();
        return migration != null && SCRIPT.equals(migration.getScript());
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public String getCallbackName() {
        return "demo-member-contacts";
    }

    @Override
    public void handle(Event event, Context context) {
        try {
            int written = write(context.getConnection(), read());
            log.info("Demo member contacts written: {} members", written);
        } catch (SQLException e) {
            throw new IllegalStateException("The demo member contacts could not be written", e);
        }
    }

    /** Seals and writes the given contacts on the owner connection, inside the caller's transaction. */
    int write(Connection connection, List<DemoContacts> entries) throws SQLException {
        OwnerKeyring.Session session = keyring.open(connection);
        int written = 0;
        try (PreparedStatement update = connection.prepareStatement(UPDATE);
                PreparedStatement deleteIndex = connection.prepareStatement(
                        "delete from member_phone_index where member_id = ?");
                PreparedStatement insertIndex = connection.prepareStatement(
                        "insert into member_phone_index (tenant_id, member_id, phone_bidx, last4_bidx) "
                                + "values (?, ?, ?, ?)")) {
            for (DemoContacts entry : entries) {
                UUID tenantId = tenantOf(connection, entry.memberId());
                if (tenantId == null) {
                    continue;
                }
                ContactCipher cipher = ContactCipher.of(session.forTenant(tenantId));
                UUID id = entry.memberId();
                List<String> phones = entry.phones() == null ? List.of() : List.copyOf(entry.phones());
                update.setBytes(1, cipher.sealPhones(id, phones));
                update.setBytes(2, cipher.seal(ContactField.EMAIL, id, entry.email()));
                update.setBytes(3, cipher.seal(ContactField.POSTAL_ADDRESS, id, entry.postalAddress()));
                update.setBytes(4, cipher.seal(ContactField.TELEGRAM_USERNAME, id, entry.telegramUsername()));
                update.setBytes(5, cipher.seal(ContactField.VIBER_PHONE, id, entry.viberPhone()));
                update.setBytes(6, cipher.seal(ContactField.NOTES, id, entry.notes()));
                update.setBytes(7, cipher.emailIndex(entry.email()));
                update.setString(8, DeliveryChannel.valueOf(
                        Objects.requireNonNullElse(entry.deliveryChannel(), DeliveryChannel.PAPER.name())).name());
                update.setObject(9, id, Types.OTHER);
                update.executeUpdate();
                deleteIndex.setObject(1, id, Types.OTHER);
                deleteIndex.executeUpdate();
                for (ContactCipher.PhoneIndexRow row : cipher.phoneIndex(phones)) {
                    insertIndex.setObject(1, tenantId, Types.OTHER);
                    insertIndex.setObject(2, id, Types.OTHER);
                    insertIndex.setBytes(3, row.phoneBidx());
                    insertIndex.setBytes(4, row.last4Bidx());
                    insertIndex.executeUpdate();
                }
                written++;
            }
        }
        return written;
    }

    static List<DemoContacts> read() {
        return read(CONTACTS);
    }

    static List<DemoContacts> read(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return JsonMapper.shared().readValue(in, LIST);
        } catch (IOException e) {
            throw new UncheckedIOException("The demo member contacts could not be read", e);
        }
    }

    private static @Nullable UUID tenantOf(Connection connection, UUID memberId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("select tenant_id from member where id = ?")) {
            select.setObject(1, memberId, Types.OTHER);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        }
    }
}
