package app.nadel.privacy.internal;

import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The start-up key-check and master key rotation, run by Flyway after every migrate on its owner connection, outside a
 * transaction, before the application serves anything (ADR 0016). For each row of {@code tenant_data_key}:
 * <ul>
 * <li>wrapped with the current master key: both keys are unwrapped (the key-check);</li>
 * <li>wrapped with the previous master key ({@code NADEL_PD_MASTER_KEY_PREVIOUS}): re-wrapped with the current one, one
 * transaction per cooperative;</li>
 * <li>wrapped with any other key, or not unwrapping: start-up fails with {@code PD_MASTER_KEY_MISMATCH} in the log,
 * before anything is re-wrapped. The server never starts with a wrong key and then fails on each screen.</li>
 * </ul>
 * The log has counts only, never a key, an id or a cooperative. It depends only on {@link MasterKeys}, so registering
 * it with Flyway does not pull JPA into Flyway's initialisation.
 */
@Component
class KeyCheckCallback implements Callback {

    static final String MISMATCH = "PD_MASTER_KEY_MISMATCH";
    private static final Logger log = LoggerFactory.getLogger(KeyCheckCallback.class);
    private static final String UPDATE = "update tenant_data_key "
            + "set data_key_wrapped = ?, index_key_wrapped = ?, master_key_id = ? "
            + "where tenant_id = ? and version = ? and master_key_id = ?";

    private final MasterKeys masterKeys;

    KeyCheckCallback(MasterKeys masterKeys) {
        this.masterKeys = masterKeys;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return false;
    }

    @Override
    public String getCallbackName() {
        return "personal-data-key-check";
    }

    @Override
    public void handle(Event event, Context context) {
        try {
            check(context.getConnection());
        } catch (SQLException e) {
            throw new IllegalStateException("The personal data keys could not be checked", e);
        }
    }

    void check(Connection connection) throws SQLException {
        if (!tableExists(connection)) {
            return;
        }
        List<TenantKeyRow> rows = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(TenantKeyRow.SELECT_ALL);
                ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                rows.add(TenantKeyStore.row(rs, rows.size()));
            }
        }
        Optional<MasterKeys.Key> previous = masterKeys.previous();
        Map<UUID, List<TenantKeyRow>> toRewrap = new LinkedHashMap<>();
        int unreadable = 0;
        for (TenantKeyRow row : rows) {
            boolean onPrevious = previous.isPresent() && previous.get().matches(row.masterKeyId());
            try {
                // Unwrapping proves the key: the id alone would also match a key that was changed in the database
                KeyWrapper.unwrap(masterKeys, row);
                if (onPrevious) {
                    toRewrap.computeIfAbsent(row.tenantId(), id -> new ArrayList<>()).add(row);
                }
            } catch (GeneralSecurityException e) {
                unreadable++;
            }
        }
        if (unreadable > 0) {
            log.error("{}: {} of {} personal data keys are not wrapped with NADEL_PD_MASTER_KEY or "
                    + "NADEL_PD_MASTER_KEY_PREVIOUS. Start with the master key this database was encrypted with.",
                    MISMATCH, unreadable, rows.size());
            throw new IllegalStateException(MISMATCH + ": the personal data keys of the database are wrapped with "
                    + "another master key than NADEL_PD_MASTER_KEY or NADEL_PD_MASTER_KEY_PREVIOUS");
        }
        int rewrapped = 0;
        for (List<TenantKeyRow> cooperative : toRewrap.values()) {
            rewrapped += rewrap(connection, previous.orElseThrow(), cooperative);
        }
        int left = countOnPrevious(connection, previous);
        log.info("Personal data keys: {} checked, {} re-wrapped, {} left on the previous master key", rows.size(),
                rewrapped, left);
    }

    /** The rows of one cooperative in one transaction on the owner connection. */
    private int rewrap(Connection connection, MasterKeys.Key previous, List<TenantKeyRow> rows) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement update = connection.prepareStatement(UPDATE)) {
            int updated = 0;
            for (TenantKeyRow row : rows) {
                TenantKeyRow moved = KeyWrapper.rewrap(previous, masterKeys.current(), row);
                update.setBytes(1, moved.dataKeyWrapped());
                update.setBytes(2, moved.indexKeyWrapped());
                update.setBytes(3, moved.masterKeyId());
                update.setObject(4, row.tenantId());
                update.setInt(5, row.version());
                update.setBytes(6, row.masterKeyId());
                updated += update.executeUpdate();
            }
            connection.commit();
            return updated;
        } catch (SQLException | GeneralSecurityException | RuntimeException e) {
            connection.rollback();
            if (e instanceof SQLException sql) {
                throw sql;
            }
            throw new IllegalStateException("The personal data keys of a cooperative could not be re-wrapped", e);
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static int countOnPrevious(Connection connection, Optional<MasterKeys.Key> previous) throws SQLException {
        if (previous.isEmpty()) {
            return 0;
        }
        try (PreparedStatement count = connection.prepareStatement(
                "select count(*) from tenant_data_key where master_key_id = ?")) {
            count.setBytes(1, previous.get().id());
            try (ResultSet rs = count.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static boolean tableExists(Connection connection) throws SQLException {
        try (PreparedStatement exists = connection.prepareStatement(
                "select to_regclass('tenant_data_key') is not null");
                ResultSet rs = exists.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }
}
