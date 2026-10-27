package app.nadel.privacy.internal;

import app.nadel.privacy.OwnerKeyring;
import app.nadel.privacy.PersonalDataUnreadableException;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The owner keyring on plain JDBC: reads and creates keys on the given connection (the schema owner bypasses RLS) and
 * leaves commits to the caller, which for a Java migration is Flyway's migration transaction. Depends only on
 * {@link MasterKeys}, so a Flyway Java migration or callback bean can use it without pulling in JPA.
 */
@Component
class DefaultOwnerKeyring implements OwnerKeyring {

    private static final Logger log = LoggerFactory.getLogger(DefaultOwnerKeyring.class);

    private final MasterKeys masterKeys;

    DefaultOwnerKeyring(MasterKeys masterKeys) {
        this.masterKeys = masterKeys;
    }

    @Override
    public Session open(Connection connection) {
        return new JdbcSession(Objects.requireNonNull(connection, "connection"));
    }

    private final class JdbcSession implements Session {

        private final Connection connection;
        private final Map<UUID, TenantKeys> keys = new HashMap<>();

        JdbcSession(Connection connection) {
            this.connection = connection;
        }

        @Override
        public TenantCipher forTenant(UUID tenantId) {
            Objects.requireNonNull(tenantId, "tenantId");
            return keys.computeIfAbsent(tenantId, this::load);
        }

        private TenantKeys load(UUID tenantId) {
            try {
                TenantKeyRow row = select(tenantId);
                if (row == null) {
                    TenantKeyRow fresh = KeyWrapper.wrapNew(masterKeys.current(), tenantId,
                            TenantKeys.CURRENT_VERSION);
                    try (PreparedStatement insert = connection.prepareStatement(TenantKeyRow.INSERT)) {
                        insert.setObject(1, fresh.tenantId());
                        insert.setInt(2, fresh.version());
                        insert.setBytes(3, fresh.dataKeyWrapped());
                        insert.setBytes(4, fresh.indexKeyWrapped());
                        insert.setBytes(5, fresh.masterKeyId());
                        insert.executeUpdate();
                    }
                    row = Objects.requireNonNull(select(tenantId), "The key of a cooperative was not created");
                }
                return KeyWrapper.unwrap(masterKeys, row);
            } catch (SQLException e) {
                throw new IllegalStateException("The personal data key of a cooperative could not be read", e);
            } catch (GeneralSecurityException e) {
                log.error("PERSONAL_DATA_UNREADABLE: the personal data key of a cooperative does not unwrap");
                throw new PersonalDataUnreadableException();
            }
        }

        private @Nullable TenantKeyRow select(UUID tenantId) throws SQLException {
            try (PreparedStatement select = connection.prepareStatement(TenantKeyRow.SELECT)) {
                select.setObject(1, tenantId);
                select.setInt(2, TenantKeys.CURRENT_VERSION);
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? TenantKeyStore.row(rs, 0) : null;
                }
            }
        }
    }
}
