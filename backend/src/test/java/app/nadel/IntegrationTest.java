package app.nadel;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for integration tests: real PostgreSQL + PostGIS in a container.
 * Migrations run as the container superuser (acting as owner) and the application runs as nadel_app,
 * so RLS behaves in tests exactly as in production.
 */
@SpringBootTest
public abstract class IntegrationTest {

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
}
