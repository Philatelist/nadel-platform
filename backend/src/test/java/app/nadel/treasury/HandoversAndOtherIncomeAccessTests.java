package app.nadel.treasury;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Who records and reverses handovers and other income (feature 018 §2.13, ADR 0009): recording needs
 * {@code CASHBOOK_ENTER}, reversing {@code CASHBOOK_APPROVE}. Permissions, never role names: a custom role with the
 * right alone is enough, and the treasurer (view and enter) may record but not reverse. A member, a role without the
 * rights and the platform operator are refused (403), a caller without a token is 401, and another cooperative's
 * handovers, income and income categories are not found (404, or 400 {@code categoryId} for a category).
 */
class HandoversAndOtherIncomeAccessTests extends TreasuryApiSupport {

    UUID interest;
    UUID handover;
    UUID income;

    @BeforeEach
    void aHandoverAndAnIncome() throws Exception {
        String chairman = person(tenantA, "CHAIRMAN");
        setOpening(chairman, account(tenantA, "CASH"), "500.00", today()).andExpect(status().isOk());
        setOpening(chairman, account(tenantA, "BANK"), "500.00", today()).andExpect(status().isOk());
        interest = category(tenantA, "INCOME", "BANK_INTEREST");
        String treasurer = person(tenantA, "TREASURER");
        handover = recordHandover(treasurer, handover(today(), "10.00", "КВ-1"));
        income = recordOtherIncome(treasurer, otherIncome("BANK", today(), "5.00", interest));
    }

    @Test
    void recordingNeedsTheRightToEnterAndNothingElse() throws Exception {
        String enterOnly = customRole(tenantA, "CASHBOOK_ENTER");
        createHandover(enterOnly, handover(today(), "1.00", "КВ-2")).andExpect(status().isCreated());
        createOtherIncome(enterOnly, otherIncome("CASH", today(), "1.00", interest)).andExpect(status().isCreated());
        reverseHandover(enterOnly, handover, "Помилка").andExpect(status().isForbidden());
        reverseOtherIncome(enterOnly, income, "Помилка").andExpect(status().isForbidden());

        String viewOnly = customRole(tenantA, "CASHBOOK_VIEW");
        createHandover(viewOnly, handover(today(), "1.00", "КВ-3")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        createOtherIncome(viewOnly, otherIncome("CASH", today(), "1.00", interest)).andExpect(status().isForbidden());
    }

    @Test
    void reversingNeedsTheRightToApprove() throws Exception {
        String treasurer = person(tenantA, "TREASURER");
        reverseHandover(treasurer, handover, "Помилка").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        reverseOtherIncome(treasurer, income, "Помилка").andExpect(status().isForbidden());

        String approver = customRole(tenantA, "CASHBOOK_APPROVE");
        reverseHandover(approver, handover, "Помилка").andExpect(status().isOk());
        reverseOtherIncome(approver, income, "Помилка").andExpect(status().isOk());
        createHandover(approver, handover(today(), "1.00", "КВ-4")).andExpect(status().isForbidden());
        createOtherIncome(approver, otherIncome("CASH", today(), "1.00", interest)).andExpect(status().isForbidden());
    }

    @Test
    void aMemberARoleWithoutTheRightsAndTheOperatorAreRefused() throws Exception {
        for (String refused : List.of(person(tenantA, "MEMBER"), customRole(tenantA, "PAYMENTS_VIEW", "MEMBERS_VIEW"),
                operator())) {
            createHandover(refused, handover(today(), "1.00", "КВ-5")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            reverseHandover(refused, handover, "Помилка").andExpect(status().isForbidden());
            createOtherIncome(refused, otherIncome("CASH", today(), "1.00", interest))
                    .andExpect(status().isForbidden());
            reverseOtherIncome(refused, income, "Помилка").andExpect(status().isForbidden());
        }
        createHandover(null, handover(today(), "1.00", "КВ-6")).andExpect(status().isUnauthorized());
        reverseOtherIncome(null, income, "Помилка").andExpect(status().isUnauthorized());
    }

    @Test
    void anotherCooperativeNeverReachesTheseEntriesOrCategories() throws Exception {
        String otherChairman = person(tenantB, "CHAIRMAN");
        setOpening(otherChairman, account(tenantB, "CASH"), "500.00", today()).andExpect(status().isOk());

        reverseHandover(otherChairman, handover, "Помилка").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        reverseOtherIncome(otherChairman, income, "Помилка").andExpect(status().isNotFound());
        createOtherIncome(otherChairman, otherIncome("CASH", today(), "1.00", interest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"));
    }
}
