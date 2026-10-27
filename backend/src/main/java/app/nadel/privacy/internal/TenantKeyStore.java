package app.nadel.privacy.internal;

import app.nadel.privacy.PersonalDataUnreadableException;
import java.security.GeneralSecurityException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads the keys of the current cooperative from {@code tenant_data_key} as the application role, under RLS, on the
 * connection of the tenant-aware transaction (a {@link JdbcTemplate} joins it; without one, a read-only transaction is
 * opened, so the key is never read outside a transaction).
 * <p>
 * A cooperative without keys gets them lazily: a new transaction ({@code REQUIRES_NEW}, needed because the caller's may
 * be read-only, such as a search or the sign-in) inserts them with {@code on conflict do nothing} and reads back
 * whichever row won, so two instances racing for the first key agree on one. The cooperative of that transaction comes
 * from {@code TenantContext}, so {@code TenantAwareTransactionManager} sets {@code app.tenant_id} for it.
 */
@Component
class TenantKeyStore {

    private static final Logger log = LoggerFactory.getLogger(TenantKeyStore.class);

    private final JdbcTemplate jdbc;
    private final MasterKeys masterKeys;
    private final TransactionTemplate read;
    private final TransactionTemplate create;

    TenantKeyStore(JdbcTemplate jdbc, MasterKeys masterKeys, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.masterKeys = masterKeys;
        this.read = new TransactionTemplate(transactions);
        this.read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.read.setReadOnly(true);
        this.create = new TransactionTemplate(transactions);
        this.create.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The keys of {@code tenantId}, which must be the current cooperative; created when it has none. */
    TenantKeys load(UUID tenantId) {
        TenantKeyRow row = read.execute(status -> select(tenantId));
        if (row == null) {
            row = create.execute(status -> {
                TenantKeyRow fresh = KeyWrapper.wrapNew(masterKeys.current(), tenantId, TenantKeys.CURRENT_VERSION);
                jdbc.update(TenantKeyRow.INSERT, fresh.tenantId(), fresh.version(), fresh.dataKeyWrapped(),
                        fresh.indexKeyWrapped(), fresh.masterKeyId());
                // the inserted row, or the one another transaction committed first
                return jdbc.queryForObject(TenantKeyRow.SELECT, TenantKeyStore::row, tenantId,
                        TenantKeys.CURRENT_VERSION);
            });
            log.info("Created the personal data keys of a cooperative");
        }
        try {
            return KeyWrapper.unwrap(masterKeys, row);
        } catch (GeneralSecurityException e) {
            log.error("PERSONAL_DATA_UNREADABLE: the personal data key of the current cooperative does not unwrap");
            throw new PersonalDataUnreadableException();
        }
    }

    private @Nullable TenantKeyRow select(UUID tenantId) {
        List<TenantKeyRow> rows = jdbc.query(TenantKeyRow.SELECT, TenantKeyStore::row, tenantId,
                TenantKeys.CURRENT_VERSION);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    static TenantKeyRow row(ResultSet rs, int rowNum) throws SQLException {
        return new TenantKeyRow(rs.getObject("tenant_id", UUID.class), rs.getInt("version"),
                rs.getBytes("data_key_wrapped"), rs.getBytes("index_key_wrapped"), rs.getBytes("master_key_id"));
    }
}
