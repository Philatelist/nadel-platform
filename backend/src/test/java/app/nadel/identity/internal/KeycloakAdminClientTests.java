package app.nadel.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.TestKeycloak;
import app.nadel.identity.SignInAccount;
import app.nadel.identity.SignInAccountChange;
import app.nadel.identity.SignInAccountMissingException;
import app.nadel.notifications.ClosedPort;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.tenancy.TenantContext;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Keycloak Admin API client against a real Keycloak with the project's realm ({@link TestKeycloak}, feature 020):
 * an account made, found, read, changed, given a password and signed in with, the tenant guard, and the error mapping
 * of a wrong secret, a closed port and a password the realm's policy {@code length(8)} refuses.
 */
@Tag("keycloak")
class KeycloakAdminClientTests {

    private static final UUID DEMO = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final TestKeycloak keycloak = TestKeycloak.shared();
    private final KeycloakAdminClient client = client(keycloak.url(), TestKeycloak.ADMIN_CLIENT_SECRET);

    private static KeycloakAdminClient client(String url, String secret) {
        return new KeycloakAdminClient(new KeycloakAdminProperties(URI.create(url), TestKeycloak.REALM,
                TestKeycloak.ADMIN_CLIENT_ID, secret, Duration.ofSeconds(2), Duration.ofSeconds(10)),
                JsonMapper.shared(), Clock.systemUTC());
    }

    private static <T> T as(UUID tenant, Supplier<T> work) {
        return TenantContext.runAs(tenant, work);
    }

    private static String freshEmail() {
        return "kc-client-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void anAccountIsMadeFoundChangedGivenAPasswordAndSignedInWith() {
        String email = freshEmail();

        SignInAccount created = as(DEMO, () -> client.create(email.toUpperCase(), "Олена", "Коваль"));
        assertThat(created.tenantId()).isEqualTo(DEMO);

        List<SignInAccount> found = client.findByLogin(email);
        assertThat(found).singleElement().satisfies(account -> {
            assertThat(account.id()).isEqualTo(created.id());
            assertThat(account.username()).isEqualTo(email);
            assertThat(account.email()).isEqualTo(email);
            assertThat(account.enabled()).isTrue();
            assertThat(account.emailVerified()).isFalse();
            assertThat(account.tenantId()).isEqualTo(DEMO);
        });
        JsonNode stored = keycloak.usersByEmail(email).getFirst();
        assertThat(stored.path("firstName").asString()).isEqualTo("Олена");
        assertThat(stored.path("lastName").asString()).isEqualTo("Коваль");
        assertThat(stored.path("attributes").path("tenant_id").get(0).asString()).isEqualTo(DEMO.toString());

        // no password yet: nobody can sign in
        assertThat(keycloak.signIn(email, "anything-at-all")).isNotEqualTo(200);

        as(DEMO, () -> {
            client.resetPassword(created.id(), "correct-horse-1");
            client.update(created.id(), SignInAccountChange.activated());
            return null;
        });
        SignInAccount activated = client.get(created.id()).orElseThrow();
        assertThat(activated.emailVerified()).isTrue();
        assertThat(activated.enabled()).isTrue();
        assertThat(activated.tenantId()).as("the attribute stays after a whole-user update").isEqualTo(DEMO);
        assertThat(keycloak.signIn(email, "correct-horse-1")).isEqualTo(200);

        // turned off: the password no longer signs in; logout works on the account
        as(DEMO, () -> {
            client.update(created.id(), new SignInAccountChange(null, null, false, null));
            client.logout(created.id());
            return null;
        });
        assertThat(client.get(created.id()).orElseThrow().enabled()).isFalse();
        assertThat(keycloak.signIn(email, "correct-horse-1")).isNotEqualTo(200);
    }

    @Test
    void removingTheCredentialsLeavesTheAccountWithoutAPassword() {
        String email = freshEmail();
        SignInAccount created = as(DEMO, () -> client.create(email, "A", "B"));
        as(DEMO, () -> {
            client.resetPassword(created.id(), "correct-horse-1");
            client.update(created.id(), SignInAccountChange.activated());
            return null;
        });
        assertThat(keycloak.credentials(created.id())).hasSize(1);

        as(DEMO, () -> {
            client.removeCredentials(created.id());
            client.removeCredentials(created.id()); // nothing left: still fine
            return null;
        });

        assertThat(keycloak.credentials(created.id())).isEmpty();
        assertThat(keycloak.signIn(email, "correct-horse-1")).isNotEqualTo(200);
        SignInAccount kept = client.get(created.id()).orElseThrow();
        assertThat(kept.enabled()).as("only the credentials go").isTrue();
        assertThat(kept.tenantId()).isEqualTo(DEMO);
        assertThatThrownBy(() -> as(DEMO, () -> {
            client.removeCredentials(UUID.randomUUID().toString());
            return null;
        })).isInstanceOf(SignInAccountMissingException.class);
    }

    @Test
    void aNewLoginChangesTheUserNameAndTheEmailTogether() {
        String email = freshEmail();
        SignInAccount created = as(DEMO, () -> client.create(email, "A", "B"));
        String renamed = freshEmail();

        as(DEMO, () -> {
            client.update(created.id(), new SignInAccountChange(renamed, false, true, null));
            return null;
        });

        assertThat(client.findByLogin(renamed)).singleElement().satisfies(account -> {
            assertThat(account.id()).isEqualTo(created.id());
            assertThat(account.username()).isEqualTo(renamed);
            assertThat(account.email()).isEqualTo(renamed);
        });
        assertThat(client.findByLogin(email)).isEmpty();
    }

    @Test
    void aMemberCannotChangeTheirOwnUserNameOrEmailInTheAccountConsole() {
        String email = freshEmail();
        SignInAccount created = as(DEMO, () -> client.create(email, "A", "B"));
        as(DEMO, () -> {
            client.resetPassword(created.id(), "correct-horse-1");
            client.update(created.id(), SignInAccountChange.activated());
            return null;
        });
        String token = keycloak.userToken(email, "correct-horse-1");
        String stolen = freshEmail();

        keycloak.call("POST", "/realms/" + TestKeycloak.REALM + "/account", token,
                "{\"username\":\"" + stolen + "\",\"email\":\"" + stolen
                        + "\",\"firstName\":\"A\",\"lastName\":\"B\"}");

        assertThat(client.findByLogin(stolen)).isEmpty();
        assertThat(client.findByLogin(email)).singleElement()
                .satisfies(account -> assertThat(account.username()).isEqualTo(email));
        assertThat(keycloak.usersByEmail(email)).hasSize(1);
    }

    @Test
    void aLoginThatExistsIsAnsweredByCreateWithItsOwnCooperative() {
        String email = freshEmail();
        SignInAccount first = as(OTHER, () -> client.create(email, "A", "B"));

        SignInAccount again = as(DEMO, () -> client.create(email, "A", "B"));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.tenantId()).isEqualTo(OTHER);
        assertThat(keycloak.usersByEmail(email)).hasSize(1);
    }

    @Test
    void anAccountOfAnotherCooperativeIsNeverChanged() {
        String email = freshEmail();
        SignInAccount foreign = as(OTHER, () -> client.create(email, "A", "B"));

        for (Runnable change : List.<Runnable>of(
                () -> client.update(foreign.id(), SignInAccountChange.activated()),
                () -> client.resetPassword(foreign.id(), "correct-horse-1"),
                () -> client.removeCredentials(foreign.id()),
                () -> client.logout(foreign.id()))) {
            assertThatThrownBy(() -> as(DEMO, () -> {
                change.run();
                return null;
            })).isInstanceOfSatisfying(ApiProblemException.class,
                    e -> assertThat(e.code()).isEqualTo("SIGN_IN_ACCOUNT_IN_USE"));
        }
        SignInAccount unchanged = client.get(foreign.id()).orElseThrow();
        assertThat(unchanged.emailVerified()).isFalse();
        assertThat(keycloak.signIn(email, "correct-horse-1")).isNotEqualTo(200);
    }

    @Test
    void anUnknownAccountIsMissing() {
        String unknown = UUID.randomUUID().toString();

        assertThat(client.get(unknown)).isEmpty();
        assertThatThrownBy(() -> as(DEMO, () -> {
            client.resetPassword(unknown, "correct-horse-1");
            return null;
        })).isInstanceOf(SignInAccountMissingException.class);
    }

    @Test
    void aShortPasswordIsRefusedByTheRealmsPolicy() {
        SignInAccount account = as(DEMO, () -> client.create(freshEmail(), "A", "B"));

        assertThatThrownBy(() -> as(DEMO, () -> {
            client.resetPassword(account.id(), "short");
            return null;
        })).isInstanceOfSatisfying(ApiProblemException.class, e -> {
            assertThat(e.status().value()).isEqualTo(400);
            assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
            assertThat(e.errors()).containsExactly(new FieldProblem("password", "PASSWORD_POLICY"));
        });
    }

    @Test
    void aWrongSecretOrAClosedPortIsUnavailable() {
        for (KeycloakAdminClient broken : List.of(client(keycloak.url(), "not-the-secret"),
                client("http://127.0.0.1:" + ClosedPort.next(), TestKeycloak.ADMIN_CLIENT_SECRET))) {
            assertThatThrownBy(() -> broken.findByLogin("chairman@demo.local"))
                    .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                        assertThat(e.status().value()).isEqualTo(503);
                        assertThat(e.code()).isEqualTo("SIGN_IN_SERVICE_UNAVAILABLE");
                    });
        }
    }

    @Test
    void theDemoUsersAreFoundWithTheirCooperative() {
        assertThat(client.findByLogin("member@other.local")).singleElement()
                .satisfies(account -> assertThat(account.tenantId()).isEqualTo(OTHER));
        assertThat(client.findByLogin("Chairman@Demo.Local")).singleElement()
                .satisfies(account -> assertThat(account.tenantId()).isEqualTo(DEMO));
        assertThat(client.findByLogin(freshEmail())).isEmpty();
    }
}
