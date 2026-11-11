package app.nadel.tenancy.internal;

import app.nadel.tenancy.TenantContext;
import app.nadel.tenancy.TenantProvisioning;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TenantProvisioning} with two statements as the application role: {@code insert ... on conflict do nothing}
 * on {@code tenant} (its policy lets the row in only while {@code app.tenant_id} is its id, hence
 * {@code TenantContext.runAs}) and, only when the row is new, {@code select seed_base_roles(id)} (not
 * {@code SECURITY DEFINER}: it writes the seeded rows under the same RLS). Logs the id only.
 */
@Service
class SqlTenantProvisioning implements TenantProvisioning {

    private static final Logger log = LoggerFactory.getLogger(SqlTenantProvisioning.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate writing;

    SqlTenantProvisioning(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.writing = new TransactionTemplate(transactionManager);
    }

    @Override
    public boolean ensure(UUID id, String name) {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A cooperative needs a name");
        }
        Boolean created = TenantContext.runAs(id, () -> writing.execute(status -> {
            int inserted = jdbc.update("insert into tenant (id, name) values (?, ?) on conflict (id) do nothing", id,
                    name.strip());
            if (inserted == 0) {
                return false;
            }
            jdbc.queryForList("select seed_base_roles(?)", id);
            return true;
        }));
        if (Boolean.TRUE.equals(created)) {
            log.info("Cooperative {} created with its base roles", id);
        }
        return Boolean.TRUE.equals(created);
    }
}
