package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.KeycloakIntegrationTest;
import app.nadel.NadelApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * The first-chairman command started the way {@code infra/stand/first-chairman.sh} starts it (feature 020): a separate
 * JVM running {@link NadelApplication#main} with {@code SPRING_PROFILES_ACTIVE=dev} and
 * {@code --spring.profiles.include=command}, so the context has no web server ({@code web-application-type: none}),
 * and the real runner stops the JVM with its exit code. {@link FirstChairmanCommandTests} builds the runner inside a
 * web test context and cannot see a bean that only a web application can create.
 * <p>
 * The child JVM uses this JVM's class path, the shared PostgreSQL container (its migrations are already applied by this
 * class's own context) and the real Keycloak Admin API of {@code TestKeycloak}. Its output goes to a temporary file
 * that is deleted afterwards; a failure shows it with the token of any link masked.
 */
class FirstChairmanJarCommandTests extends KeycloakIntegrationTest {

    private static final Pattern TOKEN = Pattern.compile("(set-password\\?token=)[A-Za-z0-9_-]+");
    private static final long TIMEOUT_SECONDS = 180;

    @Autowired
    Environment environment;

    private Path workDir;
    private final List<UUID> cooperatives = new ArrayList<>();

    @BeforeEach
    void createWorkDir() throws IOException {
        // The child's working directory: without infra/ in it the dev key file is never looked for or written
        workDir = Files.createTempDirectory("nadel-first-chairman-");
    }

    @AfterEach
    void cleanUp() throws IOException {
        for (UUID tenant : cooperatives) {
            FirstChairmanCommandTests.removeCooperative(tenant);
        }
        try (Stream<Path> files = Files.walk(workDir)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void theCommandStartsWithoutAWebServerCreatesTheChairmanPrintsOneLinkAndExitsWithZero() throws Exception {
        String email = "first-chairman-jar-" + UUID.randomUUID() + "@coop.example";
        UUID tenant = FirstChairman.tenantIdOf(email);
        cooperatives.add(tenant);

        Path output = workDir.resolve("output.txt");
        Process process = commandProcess(email, output).start();
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor();
        }
        String printed = Files.readString(output, StandardCharsets.UTF_8);

        assertThat(finished).as("the command stops by itself; output:%n%s", masked(printed)).isTrue();
        assertThat(process.exitValue()).as("exit code; output:%n%s", masked(printed))
                .isEqualTo(FirstChairmanCommand.OK);
        assertThat(TOKEN.matcher(printed).results().count()).as("one set-password link is printed").isEqualTo(1);
        assertThat(printed).contains("Cooperative created: " + tenant);
        assertThat(memberIdByEmail(tenant, email)).isNotNull();
        assertThat(ownerJdbc().queryForList("select r.base_code from member_role mr join role r on r.id = mr.role_id "
                + "where mr.tenant_id = ?", String.class, tenant)).containsExactly("CHAIRMAN");
    }

    /** {@code java -cp <this class path> NadelApplication} with the arguments of {@code first-chairman.sh}. */
    private ProcessBuilder commandProcess(String email, Path output) {
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> command = new ArrayList<>(List.of(java,
                "-cp", System.getProperty("java.class.path"),
                NadelApplication.class.getName(),
                // as first-chairman.sh
                "--spring.profiles.include=command",
                "--nadel.command=first-chairman",
                "--nadel.first-chairman.cooperative=СТ «Команда»",
                "--nadel.first-chairman.email=" + email,
                "--nadel.first-chairman.last-name=Іваненко",
                "--nadel.first-chairman.first-name=Петро",
                "--nadel.first-chairman.resend=false"));
        // the test infrastructure instead of the stand's environment, as IntegrationTest and KeycloakIntegrationTest
        for (String property : List.of("spring.datasource.url", "spring.datasource.username",
                "spring.datasource.password", "spring.flyway.user", "spring.flyway.password", "spring.flyway.locations",
                "spring.flyway.placeholders.demoledger", "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                "nadel.privacy.master-key", "nadel.keycloak.admin.url", "nadel.telegram.api-url")) {
            command.add("--" + property + "=" + environment.getRequiredProperty(property));
        }
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        builder.environment().put("SPRING_PROFILES_ACTIVE", "dev");
        builder.environment().remove("NADEL_PD_MASTER_KEY");
        builder.environment().remove("NADEL_PD_MASTER_KEY_PREVIOUS");
        return builder;
    }

    private static String masked(String printed) {
        return TOKEN.matcher(printed).replaceAll("$1***");
    }
}
