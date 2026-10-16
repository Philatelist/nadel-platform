package app.nadel.tenancy.internal;

import app.nadel.tenancy.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Passes the current cooperative to PostgreSQL at the start of every transaction:
 * {@code set_config('app.tenant_id', ..., true)}. The {@code true} flag scopes the value
 * to the current transaction, so a pooled connection never carries another tenant.
 * <p>
 * Without a cooperative an empty string is set, and RLS policies then let no rows through.
 * See docs/adr/0002-multitenancy-rls.md.
 */
class TenantAwareTransactionManager extends JpaTransactionManager {

    TenantAwareTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        String tenantId = TenantContext.currentTenantId().map(Object::toString).orElse("");
        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        holder.getEntityManager()
                .createNativeQuery("select set_config('app.tenant_id', :tenantId, true)")
                .setParameter("tenantId", tenantId)
                .getSingleResult();
    }
}
