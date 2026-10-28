package app.nadel.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.nadel.NadelApplication;
import app.nadel.TestPostgis;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.OwnerKeyring;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.tenancy.TenantContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Start-up with the master keys (ADR 0016), on a container of its own, so the shared container of
 * {@code IntegrationTest} never sees another master key whatever the test order:
 * <ul>
 * <li>{@code prod} without a key or with a malformed one does not start, with the hand-written message;</li>
 * <li>key B on a database keyed with A does not start ({@code PD_MASTER_KEY_MISMATCH}), and nothing is re-wrapped;</li>
 * <li>B with the previous key A starts and re-wraps every key; contacts sealed under A still open;</li>
 * <li>B alone then starts.</li>
 * </ul>
 * The key-check is also called directly for its edge cases (no table, a failing update rolls back, a key whose id
 * matches but which does not unwrap).
 */
@ExtendWith(OutputCaptureExtension.class)
class PersonalDataStartupTests {

    static final UUID FIRST = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
    static final UUID SECOND = UUID.fromString("dddddddd-0000-0000-0000-000000000002");

    static PostgreSQLContainer postgres;
    static JdbcTemplate owner;

    private final String keyA = MasterKeysTests.key(32);
    private final String keyB = MasterKeysTests.key(32);

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer(TestPostgis.image()).withInitScript("db/test-init.sql");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                // up to the keys only: the application's Flyway applies the rest, including the Java migration 22.1
                // (a bean), which plain Flyway cannot resolve and which must not be left behind an applied V23
                .target("21")
                .load()
                .migrate();
        owner = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()));
        for (UUID tenant : List.of(FIRST, SECOND)) {
            owner.update("insert into tenant (id, name) values (?, 'Start-up test cooperative')", tenant);
        }
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    @BeforeEach
    void noKeys() {
        owner.update("delete from tenant_data_key");
    }

    @Test
    void prodWithoutAKeyDoesNotStart(CapturedOutput output) {
        assertThatThrownBy(() -> start("", "").close())
                .satisfies(e -> assertThat(messages(e)).contains(MasterKeysTests.CURRENT_MESSAGE));
    }

    @Test
    void prodWithAMalformedKeyDoesNotStartAndNeverShowsTheValue(CapturedOutput output) {
        String malformed = "MalformedSecretValue*42";

        assertThatThrownBy(() -> start(malformed, "").close())
                .satisfies(e -> assertThat(messages(e)).contains(MasterKeysTests.CURRENT_MESSAGE)
                        .doesNotContain(malformed));
        assertThat(output.getAll()).doesNotContain(malformed);
    }

    @Test
    void prodWithAMalformedPreviousKeyDoesNotStart(CapturedOutput output) {
        String malformed = "MalformedPreviousValue*42";

        assertThatThrownBy(() -> start(keyB, malformed).close())
                .satisfies(e -> assertThat(messages(e)).contains(MasterKeysTests.PREVIOUS_MESSAGE)
                        .doesNotContain(malformed).doesNotContain(keyB));
        assertThat(output.getAll()).doesNotContain(malformed).doesNotContain(keyB);
    }

    @Test
    void aRotationFromAToB(CapturedOutput output) throws Exception {
        Map<FieldRef, byte[]> sealedUnderA = sealUnderA();

        // B alone on a database keyed with A
        assertThatThrownBy(() -> start(keyB, "").close())
                .satisfies(e -> assertThat(messages(e)).contains(KeyCheckCallback.MISMATCH)
                        .doesNotContain(keyA).doesNotContain(keyB));
        assertThat(output.getAll()).contains("PD_MASTER_KEY_MISMATCH: 2 of 2 personal data keys")
                .doesNotContain(keyA).doesNotContain(keyB);
        assertThat(masterKeyIds()).containsOnly(MasterKeysTests.idOf(keyA));

        // B with the previous key A
        try (ConfigurableApplicationContext context = start(keyB, keyA)) {
            assertThat(output.getAll())
                    .contains("Personal data keys: 2 checked, 2 re-wrapped, 0 left on the previous master key");
            assertThat(masterKeyIds()).hasSize(2).containsOnly(MasterKeysTests.idOf(keyB));
            assertOpens(context, sealedUnderA);
        }

        // B alone again
        try (ConfigurableApplicationContext context = start(keyB, "")) {
            assertThat(output.getAll())
                    .contains("Personal data keys: 2 checked, 0 re-wrapped, 0 left on the previous master key");
            assertOpens(context, sealedUnderA);
        }
        assertThat(output.getAll()).doesNotContain(keyA).doesNotContain(keyB);
    }

    @Test
    void theCheckSkipsADatabaseWithoutTheTable() throws Exception {
        try (Connection connection = connection()) {
            connection.createStatement().execute("create schema if not exists no_keys");
            connection.createStatement().execute("set search_path to no_keys");

            new KeyCheckCallback(MasterKeys.of(keyA, null)).check(connection);
        }
    }

    @Test
    void aKeyWithTheRightIdThatDoesNotUnwrapIsAMismatch(CapturedOutput output) {
        TenantKeyRow row = KeyWrapper.wrapNew(MasterKeys.of(keyA, null).current(), FIRST, 1);
        byte[] broken = row.dataKeyWrapped().clone();
        broken[broken.length - 1] ^= 1;
        owner.update(TenantKeyRow.INSERT, FIRST, 1, broken, row.indexKeyWrapped(), row.masterKeyId());

        assertThatThrownBy(() -> {
            try (Connection connection = connection()) {
                new KeyCheckCallback(MasterKeys.of(keyA, null)).check(connection);
            }
        }).isInstanceOf(IllegalStateException.class).hasMessageStartingWith(KeyCheckCallback.MISMATCH);
        assertThat(output.getAll()).contains("PD_MASTER_KEY_MISMATCH: 1 of 1").doesNotContain(keyA);
    }

    @Test
    void aFailedReWrapRollsBackTheCooperativeAndRestoresAutoCommit() throws Exception {
        sealUnderA();
        // a second key version of the first cooperative, so its transaction updates two rows and fails on the second
        TenantKeyRow second = KeyWrapper.wrapNew(MasterKeys.of(keyA, null).current(), FIRST, 2);
        owner.update(TenantKeyRow.INSERT, FIRST, 2, second.dataKeyWrapped(), second.indexKeyWrapped(),
                second.masterKeyId());
        KeyCheckCallback callback = new KeyCheckCallback(MasterKeys.of(keyB, keyA));
        try (Connection real = connection()) {
            Connection failingUpdate = failingUpdates(real, new SQLException("update refused by the test"));
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(failingUpdate);

            assertThat(callback.supports(Event.AFTER_MIGRATE, context)).isTrue();
            assertThat(callback.supports(Event.BEFORE_MIGRATE, context)).isFalse();
            assertThat(callback.canHandleInTransaction(Event.AFTER_MIGRATE, context)).isFalse();
            assertThat(callback.getCallbackName()).isEqualTo("personal-data-key-check");
            assertThatThrownBy(() -> callback.handle(Event.AFTER_MIGRATE, context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("The personal data keys could not be checked");
            assertThat(real.getAutoCommit()).isTrue();
        }
        assertThat(masterKeyIds()).hasSize(3).containsOnly(MasterKeysTests.idOf(keyA));
    }

    @Test
    void anUnexpectedFailureWhileReWrappingAlsoRollsBack() throws Exception {
        sealUnderA();
        TenantKeyRow second = KeyWrapper.wrapNew(MasterKeys.of(keyA, null).current(), FIRST, 2);
        owner.update(TenantKeyRow.INSERT, FIRST, 2, second.dataKeyWrapped(), second.indexKeyWrapped(),
                second.masterKeyId());
        try (Connection real = connection()) {
            Connection failing = failingUpdates(real, new IllegalArgumentException("unexpected"));

            assertThatThrownBy(() -> new KeyCheckCallback(MasterKeys.of(keyB, keyA)).check(failing))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("The personal data keys of a cooperative could not be re-wrapped");
            assertThat(real.getAutoCommit()).isTrue();
        }
        assertThat(masterKeyIds()).hasSize(3).containsOnly(MasterKeysTests.idOf(keyA));
    }

    private Map<FieldRef, byte[]> sealUnderA() throws SQLException {
        Map<FieldRef, byte[]> sealed = new LinkedHashMap<>();
        try (Connection connection = connection()) {
            OwnerKeyring.Session session = new DefaultOwnerKeyring(MasterKeys.of(keyA, null)).open(connection);
            for (UUID tenant : List.of(FIRST, SECOND)) {
                FieldRef field = new FieldRef("member", "email_enc", tenant);
                sealed.put(field, session.forTenant(tenant).encrypt(field, "member@" + tenant + ".example"));
            }
        }
        return sealed;
    }

    private static void assertOpens(ConfigurableApplicationContext context, Map<FieldRef, byte[]> sealed) {
        PersonalDataCipher cipher = context.getBean(PersonalDataCipher.class);
        sealed.forEach((field, value) -> {
            UUID tenant = field.rowId();
            assertThat(TenantContext.runAs(tenant, () -> cipher.decrypt(tenant, field, value)))
                    .isEqualTo("member@" + tenant + ".example");
        });
    }

    private static List<byte[]> masterKeyIds() {
        return owner.queryForList("select master_key_id from tenant_data_key", byte[].class);
    }

    private static ConfigurableApplicationContext start(String masterKey, String previousMasterKey) {
        return new SpringApplicationBuilder(NadelApplication.class)
                .profiles("prod")
                // Command-line arguments: they win over application.yml and over a key in the developer's environment
                .run("--nadel.privacy.master-key=" + masterKey,
                        "--nadel.privacy.previous-master-key=" + previousMasterKey,
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=nadel_app",
                        "--spring.datasource.password=nadel_app",
                        "--spring.flyway.user=" + postgres.getUsername(),
                        "--spring.flyway.password=" + postgres.getPassword(),
                        "--spring.flyway.locations=classpath:db/migration",
                        "--spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
                        "--server.port=0",
                        "--spring.main.banner-mode=off");
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /** The real connection, except that the second execution of the re-wrap update fails. */
    private static Connection failingUpdates(Connection real, Throwable failure) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(method, real, args);
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                            && sql.startsWith("update tenant_data_key")) {
                        return failingOnSecondUpdate((PreparedStatement) result, failure);
                    }
                    return result;
                });
    }

    private static PreparedStatement failingOnSecondUpdate(PreparedStatement real, Throwable failure) {
        AtomicInteger updates = new AtomicInteger();
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    if (method.getName().equals("executeUpdate") && updates.incrementAndGet() == 2) {
                        throw failure;
                    }
                    return invoke(method, real, args);
                });
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Every message of the cause chain, joined. */
    private static String messages(Throwable error) {
        List<String> messages = new ArrayList<>();
        for (Throwable t = error; t != null; t = t.getCause()) {
            messages.add(String.valueOf(t.getMessage()));
        }
        return String.join("\n", messages);
    }
}
