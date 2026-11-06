package app.nadel.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.identity.SignInAccount;
import app.nadel.identity.SignInAccountChange;
import app.nadel.identity.SignInAccountMissingException;
import app.nadel.notifications.ClosedPort;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.tenancy.TenantContext;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Keycloak Admin API client (feature 020, technical considerations 2.2) against a local fake HTTP server, without
 * Docker: the token cache and its one refresh on 401, the error mapping (503 {@code SIGN_IN_SERVICE_UNAVAILABLE},
 * 400 {@code PASSWORD_POLICY}, missing accounts), the tenant guard, the shape of the requests, the fail-fast secret and
 * that nothing personal reaches the log. {@code KeycloakAdminClientTests} runs the same client against a real Keycloak.
 */
@ExtendWith(OutputCaptureExtension.class)
class KeycloakAdminClientFailureTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String USER_ID = "4f3d9f6c-0000-4000-8000-000000000001";
    private static final String EMAIL = "person.private@example.com";
    private static final String PASSWORD = "very-secret-password";

    private FakeKeycloak keycloak;
    private MovableClock clock;
    private KeycloakAdminClient client;

    @BeforeEach
    void start() throws IOException {
        keycloak = new FakeKeycloak();
        clock = new MovableClock();
        client = client(keycloak.url(), "the-secret", Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        keycloak.stop();
    }

    private KeycloakAdminClient client(String url, String secret, Duration readTimeout) {
        return new KeycloakAdminClient(new KeycloakAdminProperties(URI.create(url + "/"), "nadel", "nadel-backend-admin",
                secret, Duration.ofSeconds(1), readTimeout), JsonMapper.shared(), clock);
    }

    // ---- configuration -------------------------------------------------------------------------------------------

    @Test
    void aMissingOrBlankSecretStopsTheStartUp() {
        for (String secret : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> client(keycloak.url(), secret, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET");
        }
    }

    @Test
    void aRealmThatIsNoNameStopsTheStartUp() {
        assertThatThrownBy(() -> new KeycloakAdminClient(new KeycloakAdminProperties(URI.create(keycloak.url()),
                "nadel/../master", null, "s", null, null), JsonMapper.shared(), clock))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void thePropertiesHaveDefaultsAndNeverPrintTheSecret() {
        var defaults = new KeycloakAdminProperties(null, " ", null, "top-secret-value", null, null);

        assertThat(defaults.url()).isEqualTo(URI.create("http://localhost:8180"));
        assertThat(defaults.realm()).isEqualTo("nadel");
        assertThat(defaults.clientId()).isEqualTo("nadel-backend-admin");
        assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.toString()).doesNotContain("top-secret-value").contains("clientSecret=<set>");
        assertThat(new KeycloakAdminProperties(null, null, null, null, null, null).toString())
                .contains("clientSecret=<missing>");
    }

    // ---- token ---------------------------------------------------------------------------------------------------

    @Test
    void theTokenIsFetchedWithBasicAuthAndKeptUntilShortlyBeforeItExpires() {
        keycloak.tokenLifetime = 31; // kept 1 s
        for (int i = 0; i < 3; i++) {
            keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 200, user(USER_ID, EMAIL, TENANT));
        }

        assertThat(client.get(USER_ID)).isPresent();
        assertThat(client.get(USER_ID)).isPresent();
        assertThat(keycloak.tokenRequests()).isEqualTo(1);
        Request token = keycloak.requests.getFirst();
        assertThat(token.path()).isEqualTo("/realms/nadel/protocol/openid-connect/token");
        assertThat(token.body()).isEqualTo("grant_type=client_credentials");
        assertThat(token.authorization()).isEqualTo("Basic " + java.util.Base64.getEncoder().encodeToString(
                "nadel-backend-admin:the-secret".getBytes(StandardCharsets.UTF_8)));
        assertThat(keycloak.requests.getLast().authorization()).isEqualTo("Bearer token-1");

        clock.advance(Duration.ofSeconds(2));
        assertThat(client.get(USER_ID)).isPresent();
        assertThat(keycloak.tokenRequests()).isEqualTo(2);
        assertThat(keycloak.requests.getLast().authorization()).isEqualTo("Bearer token-2");
    }

    @Test
    void anAdminCallAnswered401GetsANewTokenOnce() {
        keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 401, "");
        keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 200, user(USER_ID, EMAIL, TENANT));

        assertThat(client.get(USER_ID)).isPresent();
        assertThat(keycloak.tokenRequests()).isEqualTo(2);
    }

    @Test
    void anAdminCallAnswered401TwiceIsUnavailable() {
        keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 401, "");
        keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 401, "");

        assertUnavailable(() -> client.get(USER_ID));
    }

    @Test
    void aRefusedSecretIsUnavailable(CapturedOutput output) {
        keycloak.tokenStatus = 401;

        assertUnavailable(() -> client.findByLogin(EMAIL));
        assertThat(output).contains("Keycloak token failed: HTTP 401").doesNotContain(EMAIL).doesNotContain("the-secret");
    }

    @Test
    void aTokenAnswerWithoutATokenIsUnavailable() {
        keycloak.tokenBody = "{\"expires_in\":300}";

        assertUnavailable(() -> client.get(USER_ID));
    }

    // ---- failures ------------------------------------------------------------------------------------------------

    @Test
    void aServerErrorOrAForbiddenServiceAccountIsUnavailable(CapturedOutput output) {
        keycloak.reply("GET", "/admin/realms/nadel/users", 500, "{\"error\":\"" + EMAIL + "\"}");
        assertUnavailable(() -> client.findByLogin(EMAIL));

        keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 403, "");
        assertUnavailable(() -> client.get(USER_ID));

        assertThat(output).contains("Keycloak find failed: HTTP 500").contains("Keycloak get failed: HTTP 403")
                .doesNotContain(EMAIL).doesNotContain("person.private");
    }

    @Test
    void aClosedPortIsUnavailable(CapturedOutput output) {
        KeycloakAdminClient closed = client("http://127.0.0.1:" + ClosedPort.next(), "the-secret",
                Duration.ofSeconds(1));

        assertUnavailable(() -> closed.findByLogin(EMAIL));
        assertThat(output).contains("Keycloak token failed:").doesNotContain(EMAIL);
    }

    @Test
    void anAnswerThatTakesTooLongIsUnavailable() {
        KeycloakAdminClient impatient = client(keycloak.url(), "the-secret", Duration.ofMillis(300));
        keycloak.delay = Duration.ofSeconds(2);

        assertUnavailable(() -> impatient.get(USER_ID));
    }

    @Test
    void anAnswerThatIsNotTheExpectedJsonIsUnavailable() {
        keycloak.reply("GET", "/admin/realms/nadel/users", 200, "{\"not\":\"a list\"}");
        assertUnavailable(() -> client.findByLogin(EMAIL));

        keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 200, "not json");
        assertUnavailable(() -> client.get(USER_ID));
    }

    // ---- find, get, create -----------------------------------------------------------------------------------------

    @Test
    void findingByLoginAsksByEmailAndByUserNameAndAnswersEachAccountOnce() {
        String other = "4f3d9f6c-0000-4000-8000-000000000002";
        keycloak.reply("GET", "/admin/realms/nadel/users", 200, "[" + user(USER_ID, EMAIL, TENANT) + "]");
        keycloak.reply("GET", "/admin/realms/nadel/users", 200, "[" + user(USER_ID, EMAIL, TENANT) + ","
                + "{\"id\":\"" + other + "\",\"username\":\"" + EMAIL + "\",\"enabled\":false,"
                + "\"attributes\":{\"tenant_id\":[\"not-a-uuid\"]}}]");

        List<SignInAccount> found = client.findByLogin("  Person.Private@Example.com ");

        assertThat(found).extracting(SignInAccount::id).containsExactly(USER_ID, other);
        assertThat(found.get(0).tenantId()).isEqualTo(TENANT);
        assertThat(found.get(0).email()).isEqualTo(EMAIL);
        assertThat(found.get(0).enabled()).isTrue();
        assertThat(found.get(1).tenantId()).isNull();
        assertThat(found.get(1).email()).isNull();
        assertThat(keycloak.adminRequests()).extracting(Request::query).containsExactly(
                "email=person.private%40example.com&exact=true&briefRepresentation=false",
                "username=person.private%40example.com&exact=true&briefRepresentation=false");
        assertThat(found.get(0).toString()).doesNotContain(EMAIL);
    }

    @Test
    void aSearchThatIsNotAnsweredWith200IsUnavailable() {
        keycloak.reply("GET", "/admin/realms/nadel/users", 500, "{}");

        assertThatThrownBy(() -> client.findByLogin(EMAIL)).isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("could not be reached");
    }

    @Test
    void aSearchRefusedWithAClientErrorIsUnavailableAndNeverAnEmptyResult(CapturedOutput output) {
        keycloak.reply("GET", "/admin/realms/nadel/users", 400, "{\"error\":\"" + EMAIL + "\"}");
        assertUnavailable(() -> client.findByLogin(EMAIL));

        // nothing queued: the fake answers 404, which is no "nobody has this login" either
        assertUnavailable(() -> client.findByLogin(EMAIL));

        assertThat(keycloak.adminRequests()).hasSize(2).extracting(Request::query)
                .allMatch(query -> query.startsWith("email="), "the username search is never sent");
        assertThat(output).contains("Keycloak find failed: HTTP 400").contains("Keycloak find failed: HTTP 404")
                .doesNotContain(EMAIL);
    }

    @Test
    void anInterruptedCallIsUnavailableAndKeepsTheInterrupt(CapturedOutput output) {
        keycloak.delay = Duration.ofSeconds(1); // the answer is still on its way when the wait sees the interrupt

        Thread.currentThread().interrupt();
        try {
            assertUnavailable(() -> client.get(USER_ID));
        } finally {
            assertThat(Thread.interrupted()).as("the interrupt is restored for the caller").isTrue();
        }
        assertThat(output).contains("Keycloak token was interrupted");
    }

    @Test
    void anAccountWithoutAValidIdIsRefusedAndNeverReturned() {
        keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 200, "{\"id\":\"bad id!\",\"username\":\"x\"}");

        assertThatThrownBy(() -> client.get(USER_ID)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without a valid id");
    }

    @Test
    void theCooperativeOfAnAccountIsReadFromAnArrayOrAStringAndNothingElse() {
        String other = "4f3d9f6c-0000-4000-8000-000000000002";
        String third = "4f3d9f6c-0000-4000-8000-000000000003";
        keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 200, "{\"id\":\"" + USER_ID
                + "\",\"username\":\"a\",\"attributes\":{\"tenant_id\":\"" + TENANT + "\"}}");
        keycloak.reply("GET", "/admin/realms/nadel/users/" + other, 200, "{\"id\":\"" + other
                + "\",\"username\":\"b\",\"attributes\":{\"tenant_id\":[\"" + TENANT + "\",\"" + OTHER + "\"]}}");
        keycloak.reply("GET", "/admin/realms/nadel/users/" + third, 200, "{\"id\":\"" + third
                + "\",\"username\":\"c\",\"attributes\":{\"tenant_id\":7}}");

        assertThat(client.get(USER_ID).orElseThrow().tenantId()).as("a plain string").isEqualTo(TENANT);
        assertThat(client.get(other).orElseThrow().tenantId()).as("two values are no cooperative").isNull();
        assertThat(client.get(third).orElseThrow().tenantId()).as("a number is no cooperative").isNull();
    }

    @Test
    void anUnknownOrMalformedIdIsAMissingAccount() {
        keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 404, "");

        assertThat(client.get(USER_ID)).isEmpty();
        assertThat(client.get("../../clients")).isEmpty();
        assertThatThrownBy(() -> TenantContext.runAs(TENANT, () -> {
            client.logout("../../clients");
            return null;
        })).isInstanceOf(SignInAccountMissingException.class);
        assertThat(keycloak.adminRequests()).hasSize(1);
    }

    @Test
    void creatingAnAccountWritesTheLoginTheNamesAndTheCooperativeWithoutAPassword() {
        keycloak.reply("POST", "/admin/realms/nadel/users", 201, "", Map.of("Location",
                keycloak.url() + "/admin/realms/nadel/users/" + USER_ID));

        SignInAccount account = TenantContext.runAs(TENANT, () -> client.create(" Person.Private@Example.com",
                "Олена", "Коваль"));

        assertThat(account.id()).isEqualTo(USER_ID);
        assertThat(account.tenantId()).isEqualTo(TENANT);
        assertThat(account.enabled()).isTrue();
        assertThat(account.emailVerified()).isFalse();
        JsonNode body = JsonMapper.shared().readTree(keycloak.adminRequests().getLast().body());
        assertThat(body.path("username").asString()).isEqualTo(EMAIL);
        assertThat(body.path("email").asString()).isEqualTo(EMAIL);
        assertThat(body.path("firstName").asString()).isEqualTo("Олена");
        assertThat(body.path("lastName").asString()).isEqualTo("Коваль");
        assertThat(body.path("enabled").asBoolean()).isTrue();
        assertThat(body.path("emailVerified").asBoolean()).isFalse();
        assertThat(body.path("attributes").path("tenant_id").get(0).asString()).isEqualTo(TENANT.toString());
        assertThat(body.has("credentials")).isFalse();
    }

    @Test
    void aLoginCreatedMeanwhileIsLookedUpAgain() {
        keycloak.reply("POST", "/admin/realms/nadel/users", 409, "{\"errorMessage\":\"User exists\"}");
        keycloak.reply("GET", "/admin/realms/nadel/users", 200, "[" + user(USER_ID, EMAIL, OTHER) + "]");
        keycloak.reply("GET", "/admin/realms/nadel/users", 200, "[]");

        SignInAccount account = TenantContext.runAs(TENANT, () -> client.create(EMAIL, "A", "B"));

        assertThat(account.id()).isEqualTo(USER_ID);
        assertThat(account.tenantId()).isEqualTo(OTHER); // the caller checks the cooperative
    }

    @Test
    void aConflictThatFindsNothingOrACreationWithoutALocationIsUnavailable() {
        keycloak.reply("POST", "/admin/realms/nadel/users", 409, "");
        keycloak.reply("GET", "/admin/realms/nadel/users", 200, "[]");
        keycloak.reply("GET", "/admin/realms/nadel/users", 200, "[]");
        assertUnavailable(() -> TenantContext.runAs(TENANT, () -> client.create(EMAIL, "A", "B")));

        keycloak.reply("POST", "/admin/realms/nadel/users", 201, "");
        assertUnavailable(() -> TenantContext.runAs(TENANT, () -> client.create(EMAIL, "A", "B")));

        keycloak.reply("POST", "/admin/realms/nadel/users", 201, "", Map.of("Location", "/users/bad id"));
        assertUnavailable(() -> TenantContext.runAs(TENANT, () -> client.create(EMAIL, "A", "B")));

        keycloak.reply("POST", "/admin/realms/nadel/users", 400, "{\"errorMessage\":\"error-invalid-value\"}");
        assertUnavailable(() -> TenantContext.runAs(TENANT, () -> client.create(EMAIL, "A", "B")));
    }

    @Test
    void creatingWithoutABoundCooperativeIsAProgrammingError() {
        assertThatThrownBy(() -> client.create(EMAIL, "A", "B")).isInstanceOf(IllegalStateException.class);
        assertThat(keycloak.requests).isEmpty();
    }

    // ---- changes and the tenant guard ------------------------------------------------------------------------------

    @Test
    void anUpdateWritesBackTheWholeUserWithTheChangedFields() {
        keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 200, user(USER_ID, EMAIL, TENANT));
        keycloak.reply("PUT", "/admin/realms/nadel/users/" + USER_ID, 204, "");

        TenantContext.runAs(TENANT, () -> {
            client.update(USER_ID, new SignInAccountChange("New.Login@Example.com", true, false,
                    List.of()));
            return null;
        });

        JsonNode body = JsonMapper.shared().readTree(keycloak.adminRequests().getLast().body());
        assertThat(body.path("username").asString()).isEqualTo("new.login@example.com");
        assertThat(body.path("email").asString()).isEqualTo("new.login@example.com");
        assertThat(body.path("emailVerified").asBoolean()).isTrue();
        assertThat(body.path("enabled").asBoolean()).isFalse();
        assertThat(body.path("requiredActions").isArray()).isTrue();
        assertThat(body.path("requiredActions")).isEmpty();
        assertThat(body.path("firstName").asString()).isEqualTo("Person"); // untouched fields stay
        assertThat(body.path("attributes").path("tenant_id").get(0).asString()).isEqualTo(TENANT.toString());
    }

    @Test
    void anUpdateMapsMissingConflictAndUnexpectedAnswers() {
        for (int status : new int[] {404, 409, 400}) {
            keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 200, user(USER_ID, EMAIL, TENANT));
            keycloak.reply("PUT", "/admin/realms/nadel/users/" + USER_ID, status, "");
        }

        assertThatThrownBy(() -> update(SignInAccountChange.enable())).isInstanceOf(SignInAccountMissingException.class);
        assertThatThrownBy(() -> update(SignInAccountChange.enable())).isInstanceOfSatisfying(ApiProblemException.class,
                e -> assertThat(e.code()).isEqualTo("SIGN_IN_ACCOUNT_IN_USE"));
        assertUnavailable(() -> update(SignInAccountChange.enable()));
    }

    @Test
    void aChangeToAnAccountOfAnotherCooperativeIsRefusedBeforeItIsSent(CapturedOutput output) {
        keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 200, user(USER_ID, EMAIL, OTHER));
        keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 200, user(USER_ID, EMAIL, OTHER));
        keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 200, user(USER_ID, EMAIL, OTHER));

        assertThatThrownBy(() -> update(SignInAccountChange.activated()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("SIGN_IN_ACCOUNT_IN_USE"));
        assertThatThrownBy(() -> TenantContext.runAs(TENANT, () -> {
            client.resetPassword(USER_ID, PASSWORD);
            return null;
        })).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> TenantContext.runAs(TENANT, () -> {
            client.logout(USER_ID);
            return null;
        })).isInstanceOf(ApiProblemException.class);

        assertThat(keycloak.adminRequests()).extracting(Request::method).containsOnly("GET");
        assertThat(output).contains("does not belong to cooperative").doesNotContain(EMAIL);
    }

    @Test
    void aChangeToAMissingAccountIsMissing() {
        keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 404, "");

        assertThatThrownBy(() -> TenantContext.runAs(TENANT, () -> {
            client.resetPassword(USER_ID, PASSWORD);
            return null;
        })).isInstanceOf(SignInAccountMissingException.class);
    }

    @Test
    void aPasswordIsSetNotTemporaryAndAPolicyRefusalIsAFieldError(CapturedOutput output) {
        keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 200, user(USER_ID, EMAIL, TENANT));
        keycloak.reply("PUT", "/admin/realms/nadel/users/" + USER_ID + "/reset-password", 204, "");
        resetPassword();
        JsonNode body = JsonMapper.shared().readTree(keycloak.adminRequests().getLast().body());
        assertThat(body.path("type").asString()).isEqualTo("password");
        assertThat(body.path("value").asString()).isEqualTo(PASSWORD);
        assertThat(body.path("temporary").asBoolean(true)).isFalse();

        for (String refusal : List.of("{\"error\":\"invalidPasswordMinLengthMessage\",\"error_description\":\"x\"}",
                "{\"errorMessage\":\"invalidPasswordHistoryMessage\"}")) {
            keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 200, user(USER_ID, EMAIL, TENANT));
            keycloak.reply("PUT", "/admin/realms/nadel/users/" + USER_ID + "/reset-password", 400, refusal);
            assertThatThrownBy(this::resetPassword).isInstanceOfSatisfying(ApiProblemException.class, e -> {
                assertThat(e.status().value()).isEqualTo(400);
                assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
                assertThat(e.errors()).containsExactly(new FieldProblem("password", "PASSWORD_POLICY"));
            });
        }
        for (String other : List.of("{\"error\":\"something else\"}", "")) {
            keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 200, user(USER_ID, EMAIL, TENANT));
            keycloak.reply("PUT", "/admin/realms/nadel/users/" + USER_ID + "/reset-password", 400, other);
            assertUnavailable(this::resetPassword);
        }
        keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 200, user(USER_ID, EMAIL, TENANT));
        keycloak.reply("PUT", "/admin/realms/nadel/users/" + USER_ID + "/reset-password", 404, "");
        assertThatThrownBy(this::resetPassword).isInstanceOf(SignInAccountMissingException.class);
        keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 200, user(USER_ID, EMAIL, TENANT));
        keycloak.reply("PUT", "/admin/realms/nadel/users/" + USER_ID + "/reset-password", 418, "");
        assertUnavailable(this::resetPassword);

        assertThat(output).doesNotContain(PASSWORD);
    }

    @Test
    void aLogoutEndsTheSessionsOfTheAccount() {
        for (int status : new int[] {204, 404, 418}) {
            keycloak.reply("GET", "/admin/realms/nadel/users/" + USER_ID, 200, user(USER_ID, EMAIL, TENANT));
            keycloak.reply("POST", "/admin/realms/nadel/users/" + USER_ID + "/logout", status, "");
        }

        logout();
        assertThat(keycloak.adminRequests().getLast().path()).isEqualTo("/admin/realms/nadel/users/" + USER_ID
                + "/logout");
        assertThatThrownBy(this::logout).isInstanceOf(SignInAccountMissingException.class);
        assertUnavailable(this::logout);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private void update(SignInAccountChange change) {
        TenantContext.runAs(TENANT, () -> {
            client.update(USER_ID, change);
            return null;
        });
    }

    private void resetPassword() {
        TenantContext.runAs(TENANT, () -> {
            client.resetPassword(USER_ID, PASSWORD);
            return null;
        });
    }

    private void logout() {
        TenantContext.runAs(TENANT, () -> {
            client.logout(USER_ID);
            return null;
        });
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiProblemException.class, e -> {
            assertThat(e.status().value()).isEqualTo(503);
            assertThat(e.code()).isEqualTo("SIGN_IN_SERVICE_UNAVAILABLE");
            assertThat(e.getMessage()).doesNotContain(EMAIL).doesNotContain(PASSWORD);
        });
    }

    private static String user(String id, String email, UUID tenant) {
        return "{\"id\":\"" + id + "\",\"username\":\"" + email + "\",\"email\":\"" + email + "\","
                + "\"firstName\":\"Person\",\"lastName\":\"Private\",\"enabled\":true,\"emailVerified\":false,"
                + "\"attributes\":{\"tenant_id\":[\"" + tenant + "\"]},\"requiredActions\":[\"UPDATE_PASSWORD\"]}";
    }

    /** One request the fake received. */
    record Request(String method, String path, String query, String authorization, String body) {
    }

    /**
     * A fake Keycloak on a random local port: the token endpoint answers {@code token-N} with {@link #tokenLifetime}
     * (or {@link #tokenStatus} / {@link #tokenBody}); admin calls answer the replies queued for their method and path,
     * oldest first, and 404 when none is queued.
     */
    static final class FakeKeycloak {

        private record Reply(int status, String body, Map<String, String> headers) {
        }

        final List<Request> requests = new CopyOnWriteArrayList<>();
        private final Map<String, Deque<Reply>> replies = new ConcurrentHashMap<>();
        private final HttpServer server;
        volatile int tokenStatus = 200;
        volatile String tokenBody;
        volatile long tokenLifetime = 300;
        volatile Duration delay = Duration.ZERO;
        private int tokens;

        FakeKeycloak() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                URI uri = exchange.getRequestURI();
                requests.add(new Request(exchange.getRequestMethod(), uri.getPath(), uri.getRawQuery(),
                        exchange.getRequestHeaders().getFirst("Authorization"), body));
                if (!delay.isZero()) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                Reply reply;
                if (uri.getPath().endsWith("/protocol/openid-connect/token")) {
                    reply = tokenReply();
                } else {
                    Deque<Reply> queue = replies.get(exchange.getRequestMethod() + " " + uri.getPath());
                    Reply next = queue == null ? null : queue.poll();
                    reply = next == null ? new Reply(404, "", Map.of()) : next;
                }
                reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
                exchange.close();
            });
            server.start();
        }

        private synchronized Reply tokenReply() {
            if (tokenStatus != 200) {
                return new Reply(tokenStatus, "{\"error\":\"unauthorized_client\"}", Map.of());
            }
            if (tokenBody != null) {
                return new Reply(200, tokenBody, Map.of());
            }
            tokens++;
            return new Reply(200, "{\"access_token\":\"token-" + tokens + "\",\"expires_in\":" + tokenLifetime + "}",
                    Map.of());
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reply(String method, String path, int status, String body) {
            reply(method, path, status, body, Map.of());
        }

        void reply(String method, String path, int status, String body, Map<String, String> headers) {
            replies.computeIfAbsent(method + " " + path, key -> new ArrayDeque<>())
                    .add(new Reply(status, body, headers));
        }

        long tokenRequests() {
            return requests.stream().filter(r -> r.path().endsWith("/protocol/openid-connect/token")).count();
        }

        List<Request> adminRequests() {
            return requests.stream().filter(r -> r.path().startsWith("/admin/")).toList();
        }

        void stop() {
            server.stop(0);
        }
    }

    /** A clock a test moves forward, to let the cached token expire. */
    static final class MovableClock extends Clock {

        private volatile Instant now = Instant.parse("2026-11-05T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
