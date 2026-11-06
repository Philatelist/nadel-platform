package app.nadel.identity.internal;

import app.nadel.identity.SignInAccount;
import app.nadel.identity.SignInAccountChange;
import app.nadel.identity.SignInAccountMissingException;
import app.nadel.identity.SignInAccounts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.tenancy.TenantContext;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link SignInAccounts} on the Keycloak Admin API, with the JDK {@link HttpClient} and Jackson (no Keycloak client
 * library), the pattern of the Telegram client of {@code notifications}.
 * <p>
 * <b>Token.</b> The service account of the confidential client {@code nadel.keycloak.admin.client-id} signs in with
 * {@code client_credentials} at {@code {url}/realms/{realm}/protocol/openid-connect/token} (HTTP Basic). The token is
 * kept until 30 s before it expires; an admin call answered 401 fetches a new one and is sent once more.
 * <p>
 * <b>Calls</b> ({@code {url}/admin/realms/{realm}}): {@code GET /users?email=|username=…&exact=true},
 * {@code POST /users}, {@code GET /users/{id}}, {@code PUT /users/{id}}, {@code PUT /users/{id}/reset-password},
 * {@code POST /users/{id}/logout}. Never a role-mapping endpoint. A change reads the user first, checks its
 * {@code tenant_id} against the bound cooperative (the tenant guard) and writes the whole representation back, so
 * attributes it does not change stay.
 * <p>
 * <b>Failures.</b> No connection, a timeout, a 5xx, a refused service account (401/403) or any other unexpected answer
 * is 503 {@code SIGN_IN_SERVICE_UNAVAILABLE}; a password the realm's policy refuses is 400 {@code PASSWORD_POLICY} on the
 * field {@code password}. The log line names the operation and the HTTP status or the exception class only: never a
 * URL (a search has the e-mail in it), a body (e-mails, passwords), the token or the secret.
 * <p>
 * The secret is required: a blank or missing {@code nadel.keycloak.admin.client-secret} stops the start-up (the dev
 * profile has the dev realm's secret as its default, see {@code application.yml}).
 */
@Component
class KeycloakAdminClient implements SignInAccounts {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);

    private static final String JSON = "application/json";
    private static final long TOKEN_MARGIN_SECONDS = 30;
    /** Keycloak user ids are UUIDs; anything else never reaches a URL. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern REALM = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final String PASSWORD_POLICY_PREFIX = "invalidPassword";

    private final HttpClient http;
    private final String tokenUrl;
    private final String adminUrl;
    private final String basicAuthorization;
    private final Duration readTimeout;
    private final JsonMapper json;
    private final Clock clock;

    private final Object tokenLock = new Object();
    private @Nullable String accessToken;
    private Instant accessTokenValidUntil = Instant.MIN;

    KeycloakAdminClient(KeycloakAdminProperties properties, JsonMapper json, Clock clock) {
        if (!properties.hasSecret()) {
            throw new IllegalStateException("nadel.keycloak.admin.client-secret (NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET) "
                    + "is not set: the backend cannot manage sign-in accounts without it");
        }
        if (!REALM.matcher(properties.realm()).matches()) {
            throw new IllegalStateException("nadel.keycloak.admin.realm is not a realm name");
        }
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String url = properties.url().toString();
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.tokenUrl = base + "/realms/" + properties.realm() + "/protocol/openid-connect/token";
        this.adminUrl = base + "/admin/realms/" + properties.realm();
        this.basicAuthorization = "Basic " + Base64.getEncoder().encodeToString(
                (form(properties.clientId()) + ":" + form(properties.clientSecret())).getBytes(StandardCharsets.UTF_8));
        this.readTimeout = properties.readTimeout();
        this.json = json;
        this.clock = clock;
    }

    @Override
    public List<SignInAccount> findByLogin(String login) {
        String value = form(login.strip().toLowerCase(Locale.ROOT));
        Map<String, SignInAccount> found = new LinkedHashMap<>();
        for (String field : List.of("email", "username")) {
            Reply reply = admin("GET", "/users?" + field + "=" + value + "&exact=true&briefRepresentation=false", null,
                    "find");
            if (reply.status() != 200) {
                throw unexpected("find", reply.status());
            }
            JsonNode users = parse(reply.body());
            if (users == null || !users.isArray()) {
                throw unexpected("find", reply.status());
            }
            for (JsonNode user : users) {
                SignInAccount account = account(user);
                found.putIfAbsent(account.id(), account);
            }
        }
        return List.copyOf(found.values());
    }

    @Override
    public SignInAccount create(String email, String firstName, String lastName) {
        UUID tenant = boundTenant();
        String login = email.strip().toLowerCase(Locale.ROOT);
        ObjectNode user = json.createObjectNode();
        user.put("username", login);
        user.put("email", login);
        user.put("firstName", firstName);
        user.put("lastName", lastName);
        user.put("enabled", true);
        user.put("emailVerified", false);
        user.putObject("attributes").putArray(TenantContext.TENANT_CLAIM).add(tenant.toString());
        Reply reply = admin("POST", "/users", json.writeValueAsString(user), "create");
        if (reply.status() == 409) {
            // created meanwhile (a concurrent invitation) or held by another account: the caller decides by its tenant
            return findByLogin(login).stream().findFirst().orElseThrow(() -> unexpected("create", 409));
        }
        if (reply.status() != 201 || reply.location() == null) {
            throw unexpected("create", reply.status());
        }
        String location = reply.location();
        String id = location.substring(location.lastIndexOf('/') + 1);
        if (!ID.matcher(id).matches()) {
            throw unexpected("create", reply.status());
        }
        return new SignInAccount(id, login, login, true, false, tenant);
    }

    @Override
    public Optional<SignInAccount> get(String id) {
        return read(id).map(KeycloakAdminClient::account);
    }

    @Override
    public void update(String id, SignInAccountChange change) {
        ObjectNode user = guarded(id, "update");
        if (change.login() != null) {
            String login = change.login().strip().toLowerCase(Locale.ROOT);
            user.put("username", login);
            user.put("email", login);
        }
        if (change.emailVerified() != null) {
            user.put("emailVerified", change.emailVerified());
        }
        if (change.enabled() != null) {
            user.put("enabled", change.enabled());
        }
        if (change.requiredActions() != null) {
            ArrayNode actions = user.putArray("requiredActions");
            change.requiredActions().forEach(actions::add);
        }
        Reply reply = admin("PUT", "/users/" + id, json.writeValueAsString(user), "update");
        switch (reply.status()) {
            case 204, 200 -> { }
            case 404 -> throw new SignInAccountMissingException(id);
            case 409 -> throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.SIGN_IN_ACCOUNT_IN_USE,
                    "Another sign-in account already uses this login.");
            default -> throw unexpected("update", reply.status());
        }
    }

    @Override
    public void resetPassword(String id, String password) {
        guarded(id, "reset-password");
        ObjectNode credential = json.createObjectNode();
        credential.put("type", "password");
        credential.put("value", password);
        credential.put("temporary", false);
        Reply reply = admin("PUT", "/users/" + id + "/reset-password", json.writeValueAsString(credential),
                "reset-password");
        switch (reply.status()) {
            case 204, 200 -> { }
            case 404 -> throw new SignInAccountMissingException(id);
            case 400 -> {
                if (!refusedByPolicy(reply.body())) {
                    throw unexpected("reset-password", 400);
                }
                throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                        "The request is not valid.",
                        List.of(new FieldProblem("password", ProblemCodes.PASSWORD_POLICY)));
            }
            default -> throw unexpected("reset-password", reply.status());
        }
    }

    @Override
    public void logout(String id) {
        guarded(id, "logout");
        Reply reply = admin("POST", "/users/" + id + "/logout", null, "logout");
        switch (reply.status()) {
            case 204, 200 -> { }
            case 404 -> throw new SignInAccountMissingException(id);
            default -> throw unexpected("logout", reply.status());
        }
    }

    /** The user of the bound cooperative, as Keycloak represents it; the tenant guard of every change. */
    private ObjectNode guarded(String id, String operation) {
        UUID tenant = boundTenant();
        ObjectNode user = read(id).orElseThrow(() -> new SignInAccountMissingException(id));
        if (!account(user).belongsTo(tenant)) {
            log.warn("Keycloak {} refused: user {} does not belong to cooperative {}", operation, id, tenant);
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.SIGN_IN_ACCOUNT_IN_USE,
                    "The sign-in account belongs to another cooperative.");
        }
        return user;
    }

    private Optional<ObjectNode> read(String id) {
        if (!ID.matcher(id).matches()) {
            return Optional.empty();
        }
        Reply reply = admin("GET", "/users/" + id, null, "get");
        if (reply.status() == 404) {
            return Optional.empty();
        }
        JsonNode user = reply.status() == 200 ? parse(reply.body()) : null;
        if (!(user instanceof ObjectNode object)) {
            throw unexpected("get", reply.status());
        }
        return Optional.of(object);
    }

    /** One admin call with the service account's token; a 401 fetches a new token and sends the call once more. */
    private Reply admin(String method, String path, @Nullable String body, String operation) {
        Reply reply = send(adminRequest(method, path, body, token(false)), operation);
        if (reply.status() == 401) {
            reply = send(adminRequest(method, path, body, token(true)), operation);
            if (reply.status() == 401) {
                throw unexpected(operation, 401);
            }
        }
        if (reply.status() == 403 || reply.status() >= 500) {
            throw unexpected(operation, reply.status());
        }
        return reply;
    }

    private HttpRequest adminRequest(String method, String path, @Nullable String body, String bearer) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(adminUrl + path))
                .timeout(readTimeout)
                .header("Authorization", "Bearer " + bearer)
                .header("Accept", JSON);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", JSON)
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return request.build();
    }

    /** The service account's access token, from the cache unless it is about to expire or {@code renew} is set. */
    private String token(boolean renew) {
        synchronized (tokenLock) {
            Instant now = clock.instant();
            if (!renew && accessToken != null && now.isBefore(accessTokenValidUntil)) {
                return accessToken;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                    .timeout(readTimeout)
                    .header("Authorization", basicAuthorization)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", JSON)
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                    .build();
            Reply reply = send(request, "token");
            JsonNode answer = reply.status() == 200 ? parse(reply.body()) : null;
            String token = answer == null ? "" : answer.path("access_token").asString("");
            if (token.isEmpty()) {
                accessToken = null;
                throw unexpected("token", reply.status());
            }
            long lifetime = Math.max(0, answer.path("expires_in").asLong(0) - TOKEN_MARGIN_SECONDS);
            accessToken = token;
            accessTokenValidUntil = now.plusSeconds(lifetime);
            return token;
        }
    }

    private Reply send(HttpRequest request, String operation) {
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(response.statusCode(), response.body(),
                    response.headers().firstValue("Location").orElse(null));
        } catch (IOException e) {
            // the cause is not chained: its message may hold the URL, and a search URL holds an e-mail
            log.warn("Keycloak {} failed: {}", operation, e.getClass().getSimpleName());
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Keycloak {} was interrupted", operation);
            throw unavailable();
        }
    }

    /** Whether a 400 of reset-password is the realm's password policy ({@code invalidPassword…} error codes). */
    private boolean refusedByPolicy(String body) {
        JsonNode answer = parse(body);
        if (answer == null) {
            return false;
        }
        for (String field : List.of("error", "errorMessage")) {
            if (answer.path(field).asString("").startsWith(PASSWORD_POLICY_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    private @Nullable JsonNode parse(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static SignInAccount account(JsonNode user) {
        String id = user.path("id").asString("");
        if (!ID.matcher(id).matches()) {
            throw new IllegalStateException("Keycloak answered a user without a valid id");
        }
        JsonNode email = user.path("email");
        return new SignInAccount(id, user.path("username").asString(""),
                email.isString() && !email.asString().isBlank() ? email.asString() : null,
                user.path("enabled").asBoolean(false), user.path("emailVerified").asBoolean(false),
                tenantOf(user.path("attributes").path(TenantContext.TENANT_CLAIM)));
    }

    private static @Nullable UUID tenantOf(JsonNode values) {
        List<String> candidates = new ArrayList<>();
        if (values.isArray()) {
            values.forEach(value -> candidates.add(value.asString("")));
        } else if (values.isString()) {
            candidates.add(values.asString());
        }
        if (candidates.size() != 1) {
            return null;
        }
        try {
            return UUID.fromString(candidates.getFirst().strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID boundTenant() {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("No cooperative is bound"));
    }

    private static String form(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static ApiProblemException unexpected(String operation, int status) {
        log.warn("Keycloak {} failed: HTTP {}", operation, status);
        return unavailable();
    }

    private static ApiProblemException unavailable() {
        return new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, ProblemCodes.SIGN_IN_SERVICE_UNAVAILABLE,
                "The sign-in service could not be reached.");
    }

    /** One answer of Keycloak: never logged, the body may hold e-mails. */
    private record Reply(int status, String body, @Nullable String location) {

        @Override
        public String toString() {
            return "Reply[status=" + status + "]";
        }
    }

    /** Binds {@link KeycloakAdminProperties}. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KeycloakAdminProperties.class)
    static class KeycloakAdminConfiguration {
    }
}
