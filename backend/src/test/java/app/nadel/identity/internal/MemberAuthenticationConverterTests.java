package app.nadel.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.AccessProfileSource;
import app.nadel.identity.Permission;
import app.nadel.identity.RoleRef;
import app.nadel.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class MemberAuthenticationConverterTests {

    static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    final FakeSource source = new FakeSource();
    final MemberAuthenticationConverter converter = new MemberAuthenticationConverter(source);

    @Test
    void permissionsBecomeAuthorities() {
        source.profile = new AccessProfile(TENANT, MEMBER, "sub-1", "Shevchenko Iryna",
                Set.of(Permission.MEMBERS_VIEW, Permission.AUDIT_VIEW),
                List.of(new RoleRef(UUID.randomUUID(), "Treasurer", "TREASURER", false)), false);

        var token = (MemberAuthenticationToken) converter.convert(jwt(TENANT.toString(), true, null));

        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("PERM_MEMBERS_VIEW", "PERM_AUDIT_VIEW");
        assertThat(token.profile()).isSameAs(source.profile);
        assertThat(token.getName()).isEqualTo("sub-1");
    }

    @Test
    void sourceGetsTokenFactsAndRunsInTheTenantOfTheToken() {
        source.profile = new AccessProfile(TENANT, MEMBER, "sub-1", "X", Set.of(), List.of(), false);

        converter.convert(jwt(TENANT.toString(), true, null));

        assertThat(source.calls).containsExactly(new Call(TENANT, "sub-1", "user@demo.local", true, TENANT));
    }

    @Test
    void unverifiedEmailIsPassedAsUnverified() {
        source.profile = new AccessProfile(TENANT, MEMBER, "sub-1", "X", Set.of(), List.of(), false);

        converter.convert(jwt(TENANT.toString(), false, null));

        assertThat(source.calls.get(0).emailVerified()).isFalse();
    }

    @Test
    void operatorNeverReachesTheSource() {
        var token = (MemberAuthenticationToken) converter.convert(
                jwt(null, true, List.of("PLATFORM_OPERATOR")));

        assertThat(source.calls).isEmpty();
        assertThat(token.profile().platformOperator()).isTrue();
        assertThat(token.profile().memberId()).isNull();
        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_PLATFORM_OPERATOR");
    }

    @Test
    void operatorRoleWinsEvenWithATenantClaim() {
        converter.convert(jwt(TENANT.toString(), true, List.of("PLATFORM_OPERATOR")));

        assertThat(source.calls).isEmpty();
    }

    @Test
    void tokenWithoutTenantIsNotLinked() {
        assertThatThrownBy(() -> converter.convert(jwt(null, true, null)))
                .isInstanceOf(MemberNotLinkedException.class);
        assertThat(source.calls).isEmpty();
    }

    @Test
    void tokenWithMalformedTenantIsNotLinked() {
        assertThatThrownBy(() -> converter.convert(jwt("not-a-uuid", true, null)))
                .isInstanceOf(MemberNotLinkedException.class);
    }

    @Test
    void personWithoutActiveMemberIsNotLinked() {
        source.profile = null;

        assertThatThrownBy(() -> converter.convert(jwt(TENANT.toString(), true, null)))
                .isInstanceOf(MemberNotLinkedException.class);
    }

    @Test
    void lookupFailureIsNotMistakenForABadToken() {
        source.failure = new IllegalStateException("database down");

        assertThatThrownBy(() -> converter.convert(jwt(TENANT.toString(), true, null)))
                .isInstanceOf(AuthenticationServiceException.class);
    }

    @Test
    void tenantBindingIsReleasedAfterwards() {
        source.profile = new AccessProfile(TENANT, MEMBER, "sub-1", "X", Set.of(), List.of(), false);

        converter.convert(jwt(TENANT.toString(), true, null));

        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    private static Jwt jwt(@Nullable String tenant, boolean emailVerified, @Nullable List<String> realmRoles) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("sub-1")
                .claim("email", "user@demo.local")
                .claim("email_verified", emailVerified);
        if (tenant != null) {
            builder.claim("tenant_id", tenant);
        }
        if (realmRoles != null) {
            builder.claim("realm_access", Map.of("roles", realmRoles));
        }
        return builder.build();
    }

    record Call(UUID tenantId, String subject, String email, boolean emailVerified, UUID boundTenant) {
    }

    static class FakeSource implements AccessProfileSource {

        final List<Call> calls = new ArrayList<>();
        @Nullable AccessProfile profile;
        @Nullable RuntimeException failure;

        @Override
        public Optional<AccessProfile> resolve(UUID tenantId, String subject, @Nullable String email,
                boolean emailVerified) {
            calls.add(new Call(tenantId, subject, email, emailVerified,
                    TenantContext.currentTenantId().orElse(null)));
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(profile);
        }
    }
}
