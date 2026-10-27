package app.nadel.imports.internal;

import app.nadel.privacy.OwnerKeyring;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Java migration 25.1 (ADR 0016): seals the plaintext plan of every pending import preview of every cooperative into
 * {@code import_run.plan_enc} (added by V25), as the envelope {@code {"plan": ..., "digest": ...}} the application now
 * writes ({@link SealedPlans#envelope}, at {@link SealedPlans#ref} of the run with the run's cooperative), and clears
 * {@code plan} and {@code plan_digest}, so V26 can drop them. Expired previews are sealed too: the application clears
 * them as before.
 * <p>
 * Runs in Flyway's migration transaction on the schema owner's connection (the application role sees one cooperative
 * only) through {@link OwnerKeyring}: no JPA. Idempotent: a sealed run has no plaintext plan left, so a second run finds
 * nothing. The runs are read in pages of {@value #PAGE} by id. Fixed checksum; the log has counts only.
 */
@Component
class SealImportPlansMigration implements JavaMigration {

    static final String VERSION = "25.1";
    /** Fixed: the migration's code is its definition, and an edit that changes what it does needs a new version. */
    private static final int CHECKSUM = 250_100_001;
    private static final int PAGE = 500;
    private static final Logger log = LoggerFactory.getLogger(SealImportPlansMigration.class);

    private static final String SELECT = """
            select id, tenant_id, cast(plan as text), plan_digest from import_run
            where plan is not null and (cast(? as uuid) is null or id > ?) order by id limit ?
            """;
    private static final String UPDATE = """
            update import_run set plan_enc = ?, plan = null, plan_digest = null where id = ?
            """;

    private final OwnerKeyring keyring;

    SealImportPlansMigration(OwnerKeyring keyring) {
        this.keyring = keyring;
    }

    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion(VERSION);
    }

    @Override
    public String getDescription() {
        return "seal pending import plans";
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
        int sealed = seal(context.getConnection());
        log.info("Import plans sealed: {} pending previews", sealed);
    }

    /** Seals every plaintext plan on the given owner connection, inside the caller's transaction; the number sealed. */
    int seal(Connection connection) throws SQLException {
        OwnerKeyring.Session session = keyring.open(connection);
        int sealed = 0;
        UUID after = null;
        List<Row> page;
        do {
            page = page(connection, after);
            try (PreparedStatement update = connection.prepareStatement(UPDATE)) {
                for (Row row : page) {
                    String envelope = SealedPlans.envelope(JsonMapper.shared().readTree(row.plan), row.digest);
                    update.setBytes(1, session.forTenant(row.tenantId).encrypt(SealedPlans.ref(row.id), envelope));
                    update.setObject(2, row.id, Types.OTHER);
                    update.addBatch();
                    sealed++;
                    after = row.id;
                }
                update.executeBatch();
            }
        } while (page.size() == PAGE);
        return sealed;
    }

    /** The next page of runs with a plaintext plan by id, after {@code after} (null: from the first). */
    private static List<Row> page(Connection connection, @Nullable UUID after) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(SELECT)) {
            select.setObject(1, after, Types.OTHER);
            select.setObject(2, after, Types.OTHER);
            select.setInt(3, PAGE);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                            rs.getString(4)));
                }
            }
        }
        return rows;
    }

    /** A run with its plaintext plan (JSON text) and digest. */
    private record Row(UUID id, UUID tenantId, String plan, @Nullable String digest) {
    }
}
