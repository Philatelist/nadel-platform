package app.nadel.identity.internal;

import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code nadel.keycloak.admin.*}: where the Keycloak Admin API is and how the backend's service account signs in to it
 * (feature 020, technical considerations 2.2).
 *
 * @param url            Keycloak's base URL ({@code NADEL_KEYCLOAK_ADMIN_URL}): {@code http://localhost:8180} locally,
 *                       {@code http://keycloak:8080/auth} on the stand (inside its network: the proxy blocks
 *                       {@code /auth/admin}); a trailing slash is ignored
 * @param realm          the realm of the cooperatives ({@code NADEL_KEYCLOAK_REALM}, {@code nadel})
 * @param clientId       the confidential client with the service account ({@code NADEL_KEYCLOAK_ADMIN_CLIENT_ID},
 *                       {@code nadel-backend-admin})
 * @param clientSecret   its secret ({@code NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET}); the dev profile has the dev realm's
 *                       value as a default, every other profile refuses to start without it. Blank is missing. Never
 *                       logged: {@link #toString()} leaves it out
 * @param connectTimeout how long to wait for a connection (5 s)
 * @param readTimeout    how long to wait for an answer (10 s)
 */
@ConfigurationProperties("nadel.keycloak.admin")
public record KeycloakAdminProperties(@Nullable URI url, @Nullable String realm, @Nullable String clientId,
        @Nullable String clientSecret, @Nullable Duration connectTimeout, @Nullable Duration readTimeout) {

    public KeycloakAdminProperties {
        url = url == null ? URI.create("http://localhost:8180") : url;
        realm = realm == null || realm.isBlank() ? "nadel" : realm.strip();
        clientId = clientId == null || clientId.isBlank() ? "nadel-backend-admin" : clientId.strip();
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }

    /** Whether a secret is set: null and blank are missing. */
    boolean hasSecret() {
        return clientSecret != null && !clientSecret.isBlank();
    }

    /** Never prints the secret. */
    @Override
    public String toString() {
        return "KeycloakAdminProperties[url=" + url + ", realm=" + realm + ", clientId=" + clientId + ", clientSecret="
                + (hasSecret() ? "<set>" : "<missing>") + ", connectTimeout=" + connectTimeout + ", readTimeout="
                + readTimeout + "]";
    }
}
