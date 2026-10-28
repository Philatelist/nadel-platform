package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Who may do what with debt certificates (feature 016 §2.7, §2.9, §2.10, technical considerations 2.8; CLAUDE.md
 * "Security and data"), per endpoint: {@code POST /plots/{id}/certificates} and {@code POST /certificates/{id}/annul}
 * need {@code CERTIFICATES_ISSUE} (the chairman; not the treasurer); {@code GET /certificates} and
 * {@code GET /certificates/{id}/pdf} need {@code CHARGES_VIEW} (the chairman and the treasurer). A member, even for
 * their own plot, and the electrician are 403; another cooperative finds nothing (404, an empty register); the platform
 * operator is 403; no token 401.
 */
class CertificateAccessTests extends CertificateTestSupport {

    private UUID own;
    private UUID certificate;

    @BeforeEach
    void aCertificate() throws Exception {
        own = memberPlot("9");
        charge(own, "MEMBERSHIP", "75.00", previousMonth());
        certificate = idOf(certificate(own, null));
    }

    @AfterEach
    void unlinkDemoUsers() {
        // a first sign-in links a demo member to its subject; other tests expect them unlinked
        unlinkByEmail(DEMO_TENANT, "electrician@demo.local");
    }

    private static void forbidden(ResultActions call) throws Exception {
        call.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    private static void notFound(ResultActions call) throws Exception {
        call.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private void readsTheRegisterAndThePdf(String bearer) throws Exception {
        register(bearer, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(certificate.toString()));
        ResultActions pdf = certificatePdf(bearer, certificate).andExpect(status().isOk());
        assertThat(pdf.andReturn().getResponse().getContentType()).startsWith("application/pdf");
    }

    private void everythingForbidden(String bearer) throws Exception {
        forbidden(issueCertificate(bearer, own, null));
        forbidden(register(bearer, null));
        forbidden(certificatePdf(bearer, certificate));
        forbidden(certificatePdf(bearer, UUID.randomUUID())); // the same answer whatever the id
        forbidden(annul(bearer, certificate, "Помилка"));
    }

    private boolean annulled() {
        return Boolean.TRUE.equals(owner().queryForObject("select annulled_at is not null from certificate "
                + "where id = ?", Boolean.class, certificate));
    }

    @Test
    void theChairmanIssuesReadsAndAnnuls() throws Exception {
        issueCertificate(chairman, own, null).andExpect(status().isCreated());
        readsTheRegisterAndThePdf(chairman);
        annul(chairman, certificate, "Помилка").andExpect(status().isOk());
        assertThat(annulled()).isTrue();
    }

    @Test
    void theTreasurerReadsTheRegisterButCannotIssueOrAnnul() throws Exception {
        readsTheRegisterAndThePdf(treasurer);

        forbidden(issueCertificate(treasurer, own, null));
        forbidden(annul(treasurer, certificate, "Помилка"));
        assertThat(annulled()).isFalse();
        assertThat(owner().queryForObject("select count(*) from certificate where tenant_id = ?", Integer.class,
                tenant)).isEqualTo(1);
    }

    @Test
    void aCustomRoleNeedsTheRightPermissionForEachEndpoint() throws Exception {
        String issuer = customRole("CERTIFICATES_ISSUE");
        issueCertificate(issuer, own, null).andExpect(status().isCreated());
        forbidden(register(issuer, null));
        forbidden(certificatePdf(issuer, certificate));

        String viewer = customRole("CHARGES_VIEW");
        readsTheRegisterAndThePdf(viewer);
        forbidden(issueCertificate(viewer, own, null));
        forbidden(annul(viewer, certificate, "Помилка"));

        annul(issuer, certificate, "Помилка").andExpect(status().isOk());
    }

    @Test
    void aMemberHasNoAccessToCertificatesEvenOfTheirOwnPlot() throws Exception {
        everythingForbidden(member);
        assertThat(annulled()).isFalse();
    }

    @Test
    void theElectricianIsForbidden() throws Exception {
        everythingForbidden(TestTokens.bearer(DEMO_TENANT, "sub-electrician@demo.local", "electrician@demo.local"));
    }

    @Test
    void anotherCooperativeFindsNothingEvenWithEveryPermission() throws Exception {
        String email = "certificates-other-" + UUID.randomUUID() + "@example.com";
        UUID otherChairman = newMember(OTHER_TENANT, email);
        giveBaseRole(OTHER_TENANT, otherChairman, "CHAIRMAN");
        String other = TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), email);

        notFound(certificatePdf(other, certificate));
        notFound(annul(other, certificate, "Помилка"));
        issueCertificate(other, own, null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        register(other, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + certificate + "')]").isEmpty());

        assertThat(annulled()).isFalse();
        assertThat(owner().queryForObject("select count(*) from certificate where tenant_id = ?", Integer.class,
                OTHER_TENANT)).isZero();
        assertThat(owner().queryForObject("select count(*) from document_counter where tenant_id = ?",
                Integer.class, OTHER_TENANT)).isZero();
    }

    @Test
    void thePlatformOperatorIsForbiddenAndNoTokenIsUnauthenticated() throws Exception {
        everythingForbidden(TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR")
                .bearer());

        issueCertificate(null, own, null).andExpect(status().isUnauthorized());
        register(null, null).andExpect(status().isUnauthorized());
        certificatePdf(null, certificate).andExpect(status().isUnauthorized());
        annul(null, certificate, "Помилка").andExpect(status().isUnauthorized());
    }
}
