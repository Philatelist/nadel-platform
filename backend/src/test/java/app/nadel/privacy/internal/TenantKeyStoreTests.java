package app.nadel.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.IndexKind;
import app.nadel.privacy.OwnerKeyring;
import app.nadel.privacy.PersonalDataUnreadableException;
import app.nadel.tenancy.TenantContext;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lazy creation of a cooperative's keys as the application role (RLS) and as the owner, on fresh cooperatives that
 * are removed afterwards, so the shared container keeps only keys wrapped with this JVM's master key.
 */
class TenantKeyStoreTests extends IntegrationTest {

    @Autowired
    TenantKeyStore store;

    @Autowired
    OwnerKeyring ownerKeyring;

    @Autowired
    PlatformTransactionManager transactions;

    private final List<UUID> cooperatives = new ArrayList<>();

    @AfterEach
    void removeCooperatives() {
        for (UUID tenant : cooperatives) {
            ownerJdbc().update("delete from tenant_data_key where tenant_id = ?", tenant);
            ownerJdbc().update("delete from tenant where id = ?", tenant);
        }
    }

    @Test
    void twoTransactionsRacingForAFreshCooperativeEndWithOneRowAndTheSameKeys() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                UUID tenant = freshCooperative();
                CyclicBarrier start = new CyclicBarrier(2);
                List<Future<TenantKeys>> loads = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    loads.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return TenantContext.runAs(tenant, () -> store.load(tenant));
                    }));
                }
                TenantKeys first = loads.get(0).get(30, TimeUnit.SECONDS);
                TenantKeys second = loads.get(1).get(30, TimeUnit.SECONDS);
                FieldRef field = new FieldRef("member", "email_enc", UUID.randomUUID());

                assertThat(second.decrypt(field, first.encrypt(field, "race@example.com")))
                        .isEqualTo("race@example.com");
                assertThat(first.blindIndex(IndexKind.EMAIL, "race@example.com"))
                        .isEqualTo(second.blindIndex(IndexKind.EMAIL, "race@example.com"));
                assertThat(ownerJdbc().queryForObject("select count(*) from tenant_data_key where tenant_id = ?",
                        Integer.class, tenant)).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void keysAreCreatedFromInsideAReadOnlyTransactionAndReadBackLater() {
        UUID tenant = freshCooperative();
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        TenantKeys created = TenantContext.runAs(tenant, () -> readOnly.execute(status -> store.load(tenant)));
        TenantKeys loaded = TenantContext.runAs(tenant, () -> store.load(tenant));
        FieldRef field = new FieldRef("member", "notes_enc", UUID.randomUUID());

        assertThat(loaded.decrypt(field, created.encrypt(field, "note"))).isEqualTo("note");
        assertThat(ownerJdbc().queryForObject(
                "select version || ':' || octet_length(master_key_id) from tenant_data_key where tenant_id = ?",
                String.class, tenant)).isEqualTo("1:8");
    }

    @Test
    void aKeyRowThatDoesNotUnwrapIsUnreadable() {
        UUID tenant = freshCooperative();
        foreignKeyRow(tenant);

        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> store.load(tenant)))
                .isInstanceOf(PersonalDataUnreadableException.class);
    }

    @Test
    void theOwnerKeyringCreatesMissingKeysOnItsConnectionAndTheRuntimeReadsThem() throws Exception {
        UUID tenant = freshCooperative();
        FieldRef field = new FieldRef("member", "postal_address_enc", UUID.randomUUID());
        byte[] sealed;
        try (Connection owner = ownerJdbc().getDataSource().getConnection()) {
            OwnerKeyring.Session session = ownerKeyring.open(owner);
            OwnerKeyring.TenantCipher cipher = session.forTenant(tenant);
            assertThat(session.forTenant(tenant)).as("cached per session").isSameAs(cipher);
            sealed = cipher.encrypt(field, "Київ");
        }

        TenantKeys runtime = TenantContext.runAs(tenant, () -> store.load(tenant));

        assertThat(runtime.decrypt(field, sealed)).isEqualTo("Київ");
        assertThat(ownerJdbc().queryForObject("select count(*) from tenant_data_key where tenant_id = ?",
                Integer.class, tenant)).isEqualTo(1);
    }

    @Test
    void theOwnerKeyringRefusesAKeyRowThatDoesNotUnwrapAndABrokenConnection() throws Exception {
        UUID tenant = freshCooperative();
        foreignKeyRow(tenant);
        try (Connection owner = ownerJdbc().getDataSource().getConnection()) {
            OwnerKeyring.Session session = ownerKeyring.open(owner);
            assertThatThrownBy(() -> session.forTenant(tenant)).isInstanceOf(PersonalDataUnreadableException.class);
        }
        Connection closed = ownerJdbc().getDataSource().getConnection();
        closed.close();

        assertThatThrownBy(() -> ownerKeyring.open(closed).forTenant(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The personal data key of a cooperative could not be read");
    }

    private UUID freshCooperative() {
        UUID tenant = UUID.randomUUID();
        ownerJdbc().update("insert into tenant (id, name) values (?, 'Key test cooperative')", tenant);
        cooperatives.add(tenant);
        return tenant;
    }

    /** Keys wrapped by a master key this JVM does not have. */
    private void foreignKeyRow(UUID tenant) {
        TenantKeyRow row = KeyWrapper.wrapNew(MasterKeys.of(MasterKeysTests.key(32), null).current(), tenant, 1);
        ownerJdbc().update(TenantKeyRow.INSERT, tenant, 1, row.dataKeyWrapped(), row.indexKeyWrapped(),
                row.masterKeyId());
    }
}
