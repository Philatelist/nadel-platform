package app.nadel.members.internal;

import app.nadel.privacy.OwnerKeyring;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Java migration 22.1 (ADR 0016): seals the plaintext contacts of every member of every cooperative into the
 * {@code *_enc} columns added by V22, fills {@code email_bidx} and {@code member_phone_index}, so V23 can drop the
 * plaintext. Runs in Flyway's migration transaction on the schema owner's connection (the application role sees one
 * cooperative only), through {@link OwnerKeyring}: no JPA, so registering it with Flyway pulls no persistence bean into
 * Flyway's initialisation. Spring Boot hands every {@link JavaMigration} bean to Flyway.
 * <p>
 * Idempotent: a field that already has its ciphertext, an e-mail that already has its blind index and an index row
 * that already exists are left as they are. The members are read in pages of {@value #PAGE} by id. The log has counts
 * only, never a value or a cooperative.
 */
@Component
class EncryptMemberContactsMigration implements JavaMigration {

    static final String VERSION = "22.1";
    private static final int PAGE = 500;
    /** Fixed: the migration's code is its definition, and an edit that changes what it does needs a new version. */
    private static final int CHECKSUM = 220_100_001;
    private static final Logger log = LoggerFactory.getLogger(EncryptMemberContactsMigration.class);

    private static final String SELECT = """
            select id, tenant_id, phones, email, postal_address, telegram_username, viber_phone, notes,
                   phones_enc is not null, email_enc is not null, postal_address_enc is not null,
                   telegram_username_enc is not null, viber_phone_enc is not null, notes_enc is not null,
                   email_bidx is not null
            from member where (cast(? as uuid) is null or id > ?) order by id limit ?
            """;
    private static final String UPDATE = """
            update member set phones_enc = coalesce(phones_enc, ?), email_enc = coalesce(email_enc, ?),
                postal_address_enc = coalesce(postal_address_enc, ?),
                telegram_username_enc = coalesce(telegram_username_enc, ?),
                viber_phone_enc = coalesce(viber_phone_enc, ?), notes_enc = coalesce(notes_enc, ?),
                email_bidx = coalesce(email_bidx, ?)
            where id = ?
            """;
    private static final String INSERT_INDEX = """
            insert into member_phone_index (tenant_id, member_id, phone_bidx, last4_bidx) values (?, ?, ?, ?)
            on conflict do nothing
            """;

    private final OwnerKeyring keyring;

    EncryptMemberContactsMigration(OwnerKeyring keyring) {
        this.keyring = keyring;
    }

    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion(VERSION);
    }

    @Override
    public String getDescription() {
        return "encrypt member contacts";
    }

    @Override
    public Integer getChecksum() {
        return CHECKSUM;
    }

    @Override
    public boolean canExecuteInTransaction() {
        return true;
    }

    @Override
    public void migrate(Context context) throws SQLException {
        Counts counts = encrypt(context.getConnection());
        log.info("Member contacts encrypted: {} of {} members sealed, {} phone index rows written", counts.sealed,
                counts.read, counts.indexRows);
    }

    /** Seals every member's contacts on the given owner connection, inside the caller's transaction. */
    Counts encrypt(Connection connection) throws SQLException {
        OwnerKeyring.Session session = keyring.open(connection);
        Counts counts = new Counts();
        UUID after = null;
        List<Row> page;
        do {
            page = page(connection, after);
            try (PreparedStatement update = connection.prepareStatement(UPDATE);
                    PreparedStatement index = connection.prepareStatement(INSERT_INDEX)) {
                for (Row row : page) {
                    counts.read++;
                    if (row.needsWork()) {
                        seal(ContactCipher.of(session.forTenant(row.tenantId)), row, update, index, counts);
                    }
                    after = row.id;
                }
                update.executeBatch();
                index.executeBatch();
            }
        } while (page.size() == PAGE);
        return counts;
    }

    private static void seal(ContactCipher cipher, Row row, PreparedStatement update, PreparedStatement index,
            Counts counts) throws SQLException {
        update.setBytes(1, row.phonesSealed ? null : cipher.sealPhones(row.id, row.phones));
        update.setBytes(2, row.emailSealed ? null : cipher.seal(ContactField.EMAIL, row.id, row.email));
        update.setBytes(3, row.postalAddressSealed ? null
                : cipher.seal(ContactField.POSTAL_ADDRESS, row.id, row.postalAddress));
        update.setBytes(4, row.telegramUsernameSealed ? null
                : cipher.seal(ContactField.TELEGRAM_USERNAME, row.id, row.telegramUsername));
        update.setBytes(5, row.viberPhoneSealed ? null
                : cipher.seal(ContactField.VIBER_PHONE, row.id, row.viberPhone));
        update.setBytes(6, row.notesSealed ? null : cipher.seal(ContactField.NOTES, row.id, row.notes));
        update.setBytes(7, row.emailIndexed ? null : cipher.emailIndex(row.email));
        update.setObject(8, row.id, Types.OTHER);
        update.addBatch();
        counts.sealed++;
        for (ContactCipher.PhoneIndexRow entry : cipher.phoneIndex(row.phones)) {
            index.setObject(1, row.tenantId, Types.OTHER);
            index.setObject(2, row.id, Types.OTHER);
            index.setBytes(3, entry.phoneBidx());
            index.setBytes(4, entry.last4Bidx());
            index.addBatch();
            counts.indexRows++;
        }
    }

    /** The next page of members by id, after {@code after} (null: from the first). */
    private static List<Row> page(Connection connection, @Nullable UUID after) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(SELECT)) {
            select.setObject(1, after, Types.OTHER);
            select.setObject(2, after, Types.OTHER);
            select.setInt(3, PAGE);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), phones(rs.getArray(3)),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            rs.getBoolean(9), rs.getBoolean(10), rs.getBoolean(11), rs.getBoolean(12),
                            rs.getBoolean(13), rs.getBoolean(14), rs.getBoolean(15)));
                }
            }
        }
        return rows;
    }

    private static List<String> phones(@Nullable Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) array.getArray())
                .map(value -> value == null ? null : value.toString().strip())
                .filter(value -> value != null && !value.isEmpty())
                .toList();
    }

    /** What the migration did: counts only. */
    static final class Counts {
        int read;
        int sealed;
        int indexRows;
    }

    /** A member row with its plaintext contacts and which of them are sealed already. */
    private record Row(UUID id, UUID tenantId, List<String> phones, @Nullable String email,
            @Nullable String postalAddress, @Nullable String telegramUsername, @Nullable String viberPhone,
            @Nullable String notes, boolean phonesSealed, boolean emailSealed, boolean postalAddressSealed,
            boolean telegramUsernameSealed, boolean viberPhoneSealed, boolean notesSealed, boolean emailIndexed) {

        /** Whether some plaintext still lacks its ciphertext, or the e-mail its blind index. */
        boolean needsWork() {
            return (!phones.isEmpty() && !phonesSealed)
                    || (filled(email) && (!emailSealed || !emailIndexed))
                    || (filled(postalAddress) && !postalAddressSealed)
                    || (filled(telegramUsername) && !telegramUsernameSealed)
                    || (filled(viberPhone) && !viberPhoneSealed)
                    || (filled(notes) && !notesSealed);
        }

        private static boolean filled(@Nullable String value) {
            return value != null && !value.isBlank();
        }
    }
}
