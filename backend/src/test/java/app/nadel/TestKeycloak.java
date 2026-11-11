package app.nadel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keycloak in a container for the tests of feature 020 that need the real Admin API: the image of
 * {@code infra/docker-compose.yml} in {@code start-dev --import-realm} mode with {@code infra/keycloak/nadel-realm.json}
 * imported (the demo users, the client {@code nadel-backend-admin} with its dev secret and service account, the password
 * policy {@code length(8)}), ready when {@code /health/ready} of the management port answers. A plain Testcontainers
 * {@link GenericContainer}, no Keycloak test library. One container per test JVM ({@link #shared()}), started on first
 * use; only the classes tagged {@code keycloak} use it ({@link KeycloakIntegrationTest} and the client tests).
 * <p>
 * The container's bootstrap admin of the {@code master} realm ({@link #masterToken()}) is for the tests' own checks and
 * fixtures only: the application never has it.
 */
public final class TestKeycloak {

    public static final String IMAGE = "quay.io/keycloak/keycloak:26.8.1";
    public static final String REALM = "nadel";
    public static final String ADMIN_CLIENT_ID = "nadel-backend-admin";
    public static final String ADMIN_CLIENT_SECRET = "nadel-backend-admin-dev-secret";
    /**
     * A public client with direct grants that {@link #signIn} creates, so a test signs in with a password; its tokens
     * carry the {@code tenant_id} claim as the web client's do.
     */
    public static final String DIRECT_CLIENT_ID = "nadel-test-direct";

    private static final int HTTP_PORT = 8080;
    private static final int MANAGEMENT_PORT = 9000;

    private static TestKeycloak shared;

    private final GenericContainer<?> container;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private boolean directClient;

    @SuppressWarnings("resource") // stopped by Testcontainers' Ryuk when the JVM ends
    private TestKeycloak() {
        container = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withCommand("start-dev", "--import-realm")
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                .withEnv("KC_HEALTH_ENABLED", "true")
                .withEnv("KC_HTTP_PORT", Integer.toString(HTTP_PORT))
                .withExposedPorts(HTTP_PORT, MANAGEMENT_PORT)
                .withCopyFileToContainer(MountableFile.forHostPath(realmFile()),
                        "/opt/keycloak/data/import/nadel-realm.json")
                .waitingFor(Wait.forHttp("/health/ready").forPort(MANAGEMENT_PORT)
                        .withStartupTimeout(Duration.ofMinutes(4)));
        container.start();
    }

    /** The container of this test JVM, started on first use. */
    public static synchronized TestKeycloak shared() {
        if (shared == null) {
            shared = new TestKeycloak();
        }
        return shared;
    }

    /** Keycloak's base URL, the value of {@code nadel.keycloak.admin.url}. */
    public String url() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(HTTP_PORT);
    }

    /** A token of the {@code master} realm's bootstrap admin, for the tests' checks. */
    public String masterToken() {
        return tokenOf("master", "grant_type=password&client_id=admin-cli&username=admin&password=admin");
    }

    /** A token of the backend's service account ({@code client_credentials} with the dev secret). */
    public String serviceAccountToken() {
        String basic = java.util.Base64.getEncoder().encodeToString(
                (ADMIN_CLIENT_ID + ":" + ADMIN_CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url() + "/realms/" + REALM
                + "/protocol/openid-connect/token"))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build());
        return accessToken(response);
    }

    /** An admin call with a bearer token: the method, the path below the base URL and a JSON body (or null). */
    public HttpResponse<String> call(String method, String path, String bearer, String jsonBody) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url() + path))
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json");
        request.method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody));
        return send(request.build());
    }

    /** The users of realm {@code nadel} with exactly this e-mail, as the master admin reads them. */
    public List<JsonNode> usersByEmail(String email) {
        HttpResponse<String> response = call("GET", "/admin/realms/" + REALM + "/users?exact=true&email="
                + URLEncoder.encode(email, StandardCharsets.UTF_8), masterToken(), null);
        List<JsonNode> users = new ArrayList<>();
        JsonMapper.shared().readTree(response.body()).forEach(users::add);
        return users;
    }

    /** The credentials (password, one-time codes) of a user of realm {@code nadel}, as the master admin reads them. */
    public List<JsonNode> credentials(String userId) {
        return arrayOf(call("GET", "/admin/realms/" + REALM + "/users/" + userId + "/credentials", masterToken(), null));
    }

    /** The open sessions of a user of realm {@code nadel}, as the master admin reads them. */
    public List<JsonNode> sessions(String userId) {
        return arrayOf(call("GET", "/admin/realms/" + REALM + "/users/" + userId + "/sessions", masterToken(), null));
    }

    /** The claims of an access token of realm {@code nadel} (its payload, not verified: the test's own token). */
    public static JsonNode claims(String accessToken) {
        String payload = accessToken.split("\\.")[1];
        return JsonMapper.shared().readTree(new String(java.util.Base64.getUrlDecoder().decode(payload),
                StandardCharsets.UTF_8));
    }

    private static List<JsonNode> arrayOf(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Keycloak refused the admin read: HTTP " + response.statusCode());
        }
        List<JsonNode> items = new ArrayList<>();
        JsonMapper.shared().readTree(response.body()).forEach(items::add);
        return items;
    }

    /**
     * Signs in to realm {@code nadel} with a user name (or e-mail) and password through the test client's direct grant,
     * as a person would at the sign-in page; answers the HTTP status of the token endpoint (200 signed in).
     */
    public synchronized int signIn(String username, String password) {
        ensureDirectClient();
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url() + "/realms/" + REALM
                + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=password&client_id=" + DIRECT_CLIENT_ID
                        + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                        + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)))
                .build());
        return response.statusCode();
    }

    /** An access token of a user of realm {@code nadel} through the test client's direct grant (the realm's own clients stay closed). */
    public synchronized String userToken(String username, String password) {
        ensureDirectClient();
        return tokenOf(REALM, "grant_type=password&client_id=" + DIRECT_CLIENT_ID
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8));
    }

    private void ensureDirectClient() {
        if (directClient) {
            return;
        }
        HttpResponse<String> response = call("POST", "/admin/realms/" + REALM + "/clients", masterToken(),
                "{\"clientId\":\"" + DIRECT_CLIENT_ID + "\",\"publicClient\":true,\"directAccessGrantsEnabled\":true,"
                        + "\"standardFlowEnabled\":false,\"enabled\":true,\"protocolMappers\":[{\"name\":\"tenant_id\","
                        + "\"protocol\":\"openid-connect\",\"protocolMapper\":\"oidc-usermodel-attribute-mapper\","
                        + "\"config\":{\"user.attribute\":\"tenant_id\",\"claim.name\":\"tenant_id\","
                        + "\"jsonType.label\":\"String\",\"access.token.claim\":\"true\"}}]}");
        if (response.statusCode() != 201 && response.statusCode() != 409) {
            throw new IllegalStateException("Could not create the test client: HTTP " + response.statusCode());
        }
        directClient = true;
    }

    private String tokenOf(String realm, String form) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url() + "/realms/" + realm
                + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build());
        return accessToken(response);
    }

    private static String accessToken(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Keycloak refused the token: HTTP " + response.statusCode());
        }
        return JsonMapper.shared().readTree(response.body()).path("access_token").asString();
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The realm file of {@code infra/}, from {@code backend/} (Maven) or the repository root (an IDE). */
    private static Path realmFile() {
        for (Path candidate : List.of(Path.of("..", "infra", "keycloak", "nadel-realm.json"),
                Path.of("infra", "keycloak", "nadel-realm.json"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("infra/keycloak/nadel-realm.json not found from " + Path.of("").toAbsolutePath());
    }
}
