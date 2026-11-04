package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Who may download the reconciliation act of a plot ({@code GET /plots/{id}/reconciliation-act.pdf}, feature 018
 * §2.10, §2.13, technical considerations 2.8; CLAUDE.md "Security and data"): no permission annotation;
 * {@code CHARGES_VIEW} (the chairman, the treasurer, a custom role with it) for any plot; anyone else only for a plot
 * they own now. Another member's plot, a plot owned in the past, an unknown id and a plot of another cooperative are the
 * same 404 {@code PLOT_NOT_FOUND}, answered before the period and the requisites are looked at; the platform operator is
 * 403; no token 401.
 */
class ReconciliationActAccessTests extends DocumentsTestSupport {

    private UUID own;
    private UUID others;
    private LocalDate first;

    @BeforeEach
    void twoPlots() {
        own = plot("9", "Садова");
        own(memberId, own);
        others = plot("10", "Лісова");
        own(memberNamed("Ткаченко", "Марія", "Іванівна"), others);
        charge(own, "MEMBERSHIP", "75.00", previousMonth());
        charge(others, "MEMBERSHIP", "90.00", previousMonth());
        first = YearMonth.parse(previousMonth()).atDay(1);
    }

    private ResultActions act(String bearer, UUID plot, LocalDate from, LocalDate to) throws Exception {
        return send(bearer, get(ReconciliationActTests.ACT.formatted(plot)).param("from", from.toString())
                .param("to", to.toString()), null);
    }

    private ResultActions act(String bearer, UUID plot) throws Exception {
        return act(bearer, plot, first, today());
    }

    private String pdfText(String bearer, UUID plot) throws Exception {
        return text(act(bearer, plot).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private static void plotNotFound(ResultActions call) throws Exception {
        call.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
    }

    @Test
    void theBoardAndAViewerOfChargesGetTheActOfAnyPlot() throws Exception {
        assertThat(pdfText(chairman, others)).contains("Ділянка № 10");
        assertThat(pdfText(treasurer, own)).contains("Ділянка № 9");
        assertThat(pdfText(customRole("CHARGES_VIEW"), others)).contains("Ділянка № 10");
    }

    @Test
    void aMemberGetsTheActOfTheirOwnPlot() throws Exception {
        assertThat(pdfText(member, own)).contains("Ділянка № 9, Садова", "Власники: " + MEMBER_NAME);
    }

    @Test
    void anotherMembersPlotIsNotFoundForAMemberWhateverThePeriodOrTheRequisites() throws Exception {
        plotNotFound(act(member, others));
        plotNotFound(act(member, UUID.randomUUID()));
        // the same answer before the period is looked at
        plotNotFound(act(member, others, today(), today().plusDays(5)));
        owner().update("update cooperative_settings set iban = null where tenant_id = ?", tenant);
        plotNotFound(act(member, others));
        act(member, own).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUISITES_MISSING"));
    }

    @Test
    void aPlotOwnedInThePastAndARoleWithoutChargesViewFindNothing() throws Exception {
        owner().update("update ownership set valid_to = ? where plot_id = ? and member_id = ?",
                today().minusDays(10), own, memberId);
        plotNotFound(act(member, own));
        plotNotFound(act(customRole("CASHBOOK_VIEW"), own));
    }

    @Test
    void anotherCooperativeFindsNothingEvenWithEveryPermission() throws Exception {
        String email = "reconciliation-other-" + UUID.randomUUID() + "@example.com";
        UUID otherChairman = newMember(OTHER_TENANT, email);
        giveBaseRole(OTHER_TENANT, otherChairman, "CHAIRMAN");
        String other = TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), email);

        plotNotFound(act(other, own));
        plotNotFound(act(other, others));
    }

    @Test
    void thePlatformOperatorIsForbiddenAndNoTokenIsUnauthenticated() throws Exception {
        act(TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer(), own)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        act(null, own).andExpect(status().isUnauthorized());
    }
}
