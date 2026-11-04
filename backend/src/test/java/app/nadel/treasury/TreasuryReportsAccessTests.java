package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Who may run the four cash book reports (feature 018 §2.12, §2.13; technical considerations 2.7): they need
 * {@code CASHBOOK_VIEW} and not {@code REPORTS_VIEW}. A custom role with only {@code CASHBOOK_VIEW} (an audit
 * committee) sees exactly those four and may run them, and no other report; a role with only {@code REPORTS_VIEW}
 * sees none of them and is refused; a member, the platform operator and a caller without a token are refused.
 */
class TreasuryReportsAccessTests extends TreasuryReportsSupport {

    private static final List<String> CASH_BOOK_REPORTS = List.of("cash-book", "bank-movements", "income-expense",
            "expenses");

    private LocalDate from;

    @BeforeEach
    void openings() throws Exception {
        String chairman = person(tenantA, "CHAIRMAN");
        from = today().minusDays(3);
        setOpening(chairman, account(tenantA, "CASH"), "100.00", from).andExpect(status().isOk());
        setOpening(chairman, account(tenantA, "BANK"), "100.00", from).andExpect(status().isOk());
    }

    @Test
    void aRoleWithOnlyTheCashBookViewSeesExactlyTheFourAndRunsThem() throws Exception {
        String auditor = customRole(tenantA, "CASHBOOK_VIEW");

        assertThat(listedCodes(auditor)).containsExactlyElementsOf(CASH_BOOK_REPORTS);
        for (String code : CASH_BOOK_REPORTS) {
            report(auditor, code, "from", from.toString(), "to", today().toString()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(code));
            reportFile(auditor, code, "from", from.toString(), "to", today().toString()).andExpect(status().isOk());
        }
        for (String code : List.of("plot-balances", "charges-by-month", "payments")) {
            report(auditor, code).andExpect(status().isForbidden());
        }
    }

    @Test
    void aRoleWithOnlyTheReportsViewSeesNoneOfThemAndIsRefused() throws Exception {
        String reports = customRole(tenantA, "REPORTS_VIEW");

        assertThat(listedCodes(reports)).isNotEmpty().doesNotContainAnyElementsOf(CASH_BOOK_REPORTS)
                .contains("plot-balances");
        for (String code : CASH_BOOK_REPORTS) {
            report(reports, code, "from", from.toString(), "to", today().toString())
                    .andExpect(status().isForbidden());
            reportFile(reports, code, "from", from.toString(), "to", today().toString())
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void theTreasurerAndTheChairmanSeeAllOfThem() throws Exception {
        assertThat(listedCodes(person(tenantA, "TREASURER"))).containsAll(CASH_BOOK_REPORTS).contains("payments");
        assertThat(listedCodes(person(tenantA, "CHAIRMAN"))).containsAll(CASH_BOOK_REPORTS);
    }

    @Test
    void aMemberTheOperatorAndNoTokenAreRefused() throws Exception {
        String member = person(tenantA, "MEMBER");
        for (String code : CASH_BOOK_REPORTS) {
            report(member, code).andExpect(status().isForbidden());
            report(operator(), code).andExpect(status().isForbidden());
            report(null, code).andExpect(status().isUnauthorized());
        }
        send(member, get(REPORTS), null).andExpect(status().isForbidden());
        send(operator(), get(REPORTS), null).andExpect(status().isForbidden());
    }
}
