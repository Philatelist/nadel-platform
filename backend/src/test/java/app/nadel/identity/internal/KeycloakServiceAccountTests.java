package app.nadel.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.TestKeycloak;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The backend's service account ({@code nadel-backend-admin}, feature 020, technical considerations 2.3) may manage
 * the users of realm {@code nadel} and nothing else: the {@code master} realm, the realm's clients and the realm's
 * settings are refused. Run against the project's realm file in {@link TestKeycloak}.
 */
@Tag("keycloak")
class KeycloakServiceAccountTests {

    private final TestKeycloak keycloak = TestKeycloak.shared();

    @Test
    void theServiceAccountReadsTheUsersOfItsRealm() {
        assertThat(keycloak.call("GET", "/admin/realms/nadel/users?max=1", keycloak.serviceAccountToken(), null)
                .statusCode()).isEqualTo(200);
    }

    @Test
    void theServiceAccountCannotReachTheMasterRealm() {
        // a token of realm nadel has no rights on master: refused (403), never served
        assertThat(keycloak.call("GET", "/admin/realms/master/users", keycloak.serviceAccountToken(), null)
                .statusCode()).isIn(401, 403);
    }

    @Test
    void theServiceAccountCannotReadTheClientsOfItsRealm() {
        assertThat(keycloak.call("GET", "/admin/realms/nadel/clients", keycloak.serviceAccountToken(), null)
                .statusCode()).isEqualTo(403);
    }

    @Test
    void theServiceAccountCannotChangeTheRealm() {
        assertThat(keycloak.call("PUT", "/admin/realms/nadel", keycloak.serviceAccountToken(),
                "{\"registrationAllowed\":true}").statusCode()).isEqualTo(403);
    }

    @Test
    void theServiceAccountCannotGiveRoles() {
        String token = keycloak.serviceAccountToken();
        String userId = keycloak.usersByEmail("member@demo.local").getFirst().path("id").asString();

        assertThat(keycloak.call("POST", "/admin/realms/nadel/users/" + userId + "/role-mappings/realm", token,
                "[{\"name\":\"PLATFORM_OPERATOR\"}]").statusCode()).isNotEqualTo(204);
        assertThat(keycloak.call("GET", "/admin/realms/nadel/users/" + userId + "/role-mappings/realm",
                keycloak.masterToken(), null).body()).doesNotContain("PLATFORM_OPERATOR");
    }
}
