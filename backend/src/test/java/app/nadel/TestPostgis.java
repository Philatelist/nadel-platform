package app.nadel;

import org.testcontainers.utility.DockerImageName;

/**
 * The PostgreSQL + PostGIS image of the test containers. {@code NADEL_POSTGIS_IMAGE} overrides the default, the same
 * variable as in {@code infra/docker-compose.yml}: the default image is linux/amd64 only and runs emulated on Apple
 * Silicon, so a Mac sets a multi-arch build of the same versions (see {@code infra/README.md}).
 */
public final class TestPostgis {

    static final String DEFAULT_IMAGE = "postgis/postgis:17-3.5";

    private TestPostgis() {
    }

    public static DockerImageName image() {
        String configured = System.getenv("NADEL_POSTGIS_IMAGE");
        String image = configured == null || configured.isBlank() ? DEFAULT_IMAGE : configured.strip();
        return DockerImageName.parse(image).asCompatibleSubstituteFor("postgres");
    }
}
