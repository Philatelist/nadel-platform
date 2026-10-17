package app.nadel;

import app.nadel.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
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
import org.testcontainers.utility.DockerImageName;

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

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
            .withInitScript("db/test-init.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "nadel_app");
        registry.add("spring.datasource.password", () -> "nadel_app");
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://localhost:0/realms/test");
    }

    @Autowired
    private TransactionTemplate fixtureTx;

    @Autowired
    private JdbcTemplate fixtureJdbc;

    private final List<Fixture> createdMembers = new ArrayList<>();

    /** Runs SQL as the given cooperative (RLS applies), in one transaction. */
    protected <T> T inTenant(UUID tenant, Function<JdbcTemplate, T> work) {
        return TenantContext.runAs(tenant, () -> fixtureTx.execute(status -> work.apply(fixtureJdbc)));
    }

    /** Creates an active, unlinked member with the given e-mail; removed after the test. */
    protected UUID newMember(UUID tenant, String email) {
        UUID id = UUID.randomUUID();
        inTenant(tenant, sql -> sql.update(
                "insert into member (id, tenant_id, last_name, first_name, email) values (?, ?, 'Test', 'Member', ?)",
                id, tenant, email));
        createdMembers.add(new Fixture(tenant, id));
        return id;
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
