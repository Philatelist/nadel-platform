package app.nadel;

import app.nadel.tenancy.TenantContext;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for integration tests: real PostgreSQL + PostGIS in a container.
 * Migrations run as the container superuser (acting as owner) and the application runs as nadel_app,
 * so RLS behaves in tests exactly as in production.
 * <p>
 * HTTP tests use MockMvc with {@link TestTokens}; the real security filter chain and authentication converter run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfiguration.class)
public abstract class IntegrationTest {

    protected static final UUID DEMO_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    protected static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestPostgis.image())
            .withInitScript("db/test-init.sql");

    /** The personal data master key of this JVM's contexts: random per run, never written anywhere. */
    protected static final String MASTER_KEY = randomMasterKey();

    /** The owner connections of {@link #ownerJdbc()}: pooled, a new connection costs a backend start and SCRAM. */
    private static final OwnerDataSource OWNER;

    static {
        POSTGRES.start();
        OWNER = new OwnerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        // Outbox listeners finish within milliseconds; the default (first check after 100 ms, then every 100 ms) made
        // each wait for an event cost at least 100 ms. The timeouts stay as each test sets them.
        Awaitility.setDefaultPollDelay(Duration.ZERO);
        Awaitility.setDefaultPollInterval(Duration.ofMillis(10));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "nadel_app");
        registry.add("spring.datasource.password", () -> "nadel_app");
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        // The demo cooperative the tests share starts without a ledger (no charges, debts or water connections);
        // DemoLedgerData loads the demo ledger block for the tests that want it
        registry.add("spring.flyway.placeholders.demoledger", () -> "off");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://localhost:0/realms/test");
        // A fresh personal data master key per JVM, shared by every context on the one container; set explicitly, so
        // the dev key file (infra/.env) is never read or created by a test
        registry.add("nadel.privacy.master-key", () -> MASTER_KEY);
        registry.add("nadel.privacy.previous-master-key", () -> "");
    }

    private static String randomMasterKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Autowired
    private TransactionTemplate fixtureTx;

    @Autowired
    private JdbcTemplate fixtureJdbc;

    private final List<Fixture> createdMembers = new ArrayList<>();

    /**
     * SQL as the container owner (superuser, bypasses RLS): for fixtures the application role cannot create, such as
     * a whole cooperative. Autocommit, over pooled connections that come back without an open transaction; the
     * caller removes what it creates.
     */
    protected static JdbcTemplate ownerJdbc() {
        return OWNER.jdbc();
    }

    /** Runs SQL as the given cooperative (RLS applies), in one transaction. */
    protected <T> T inTenant(UUID tenant, Function<JdbcTemplate, T> work) {
        return TenantContext.runAs(tenant, () -> fixtureTx.execute(status -> work.apply(fixtureJdbc)));
    }

    /** Creates an active, unlinked member with the given e-mail; removed after the test. */
    protected UUID newMember(UUID tenant, String email) {
        return newMember(tenant, "Test", "Member", null, email);
    }

    /**
     * Creates an active, unlinked member with the given name parts, e-mail and phones (all optional except the
     * names); removed after the test. Delivery channel is PAPER.
     */
    protected UUID newMember(UUID tenant, String lastName, String firstName, String middleName, String email,
            String... phones) {
        UUID id = UUID.randomUUID();
        inTenant(tenant, sql -> sql.update(
                "insert into member (id, tenant_id, last_name, first_name, middle_name, email, phones) "
                        + "values (?, ?, ?, ?, ?, ?, string_to_array(?, ','))",
                id, tenant, lastName, firstName, middleName, email, String.join(",", phones)));
        createdMembers.add(new Fixture(tenant, id));
        return id;
    }

    /** Archives a member (sets archived_at). */
    protected void archiveMember(UUID tenant, UUID member) {
        inTenant(tenant, sql -> sql.update("update member set archived_at = now() where id = ?", member));
    }

    /** Gives a member one of the three base roles of its cooperative. */
    protected void giveBaseRole(UUID tenant, UUID member, String baseCode) {
        inTenant(tenant, sql -> sql.update(
                "insert into member_role (tenant_id, member_id, role_id) "
                        + "select ?, ?, id from role where base_code = ?", tenant, member, baseCode));
    }

    protected String subjectOf(UUID tenant, UUID member) {
        return inTenant(tenant, sql -> sql.queryForObject(
                "select keycloak_subject from member where id = ?", String.class, member));
    }

    @AfterEach
    void removeFixtureMembers() {
        for (Fixture fixture : createdMembers) {
            inTenant(fixture.tenant(), sql -> {
                sql.update("delete from member_role where member_id = ?", fixture.id());
                return sql.update("delete from member where id = ?", fixture.id());
            });
        }
        createdMembers.clear();
    }

    private record Fixture(UUID tenant, UUID id) {
    }
}
