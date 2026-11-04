package app.nadel.treasury;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Who reaches the money accounts (feature 018 §2.13, ADR 0009): the accounts and their journals need
 * {@code CASHBOOK_VIEW} (chairman, treasurer, or a custom role with it), the opening balance {@code CASHBOOK_APPROVE}
 * (the chairman, or a custom role with it); permissions, never role names. A member and the platform operator are
 * refused (403), a caller without a token is 401, and another cooperative's accounts are not found (404).
 */
class MoneyAccountsAccessTests extends TreasuryApiSupport {

    UUID cash;

    @BeforeEach
    void cashAccount() {
        cash = account(tenantA, "CASH");
    }

    @Test
    void viewersSeeTheAccountsAndJournals() throws Exception {
        for (String viewer : List.of(person(tenantA, "CHAIRMAN"), person(tenantA, "TREASURER"),
                customRole(tenantA, "CASHBOOK_VIEW"))) {
            accounts(viewer).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
            movements(viewer, cash, today(), today()).andExpect(status().isOk());
        }
    }

    @Test
    void onlyApproversEnterTheOpeningBalance() throws Exception {
        for (String refused : List.of(person(tenantA, "TREASURER"), customRole(tenantA, "CASHBOOK_VIEW",
                "CASHBOOK_ENTER"))) {
            setOpening(refused, cash, "100.00", today()).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        // the right alone is enough, without the right to view
        String approver = customRole(tenantA, "CASHBOOK_APPROVE");
        setOpening(approver, cash, "100.00", today()).andExpect(status().isOk());
        accounts(approver).andExpect(status().isForbidden());
        setOpening(person(tenantA, "CHAIRMAN"), account(tenantA, "BANK"), "5.00", today())
                .andExpect(status().isOk());
    }

    @Test
    void aMemberARoleWithoutTheRightsAndTheOperatorAreRefused() throws Exception {
        for (String refused : List.of(person(tenantA, "MEMBER"), customRole(tenantA, "PAYMENTS_VIEW", "REPORTS_VIEW"),
                operator())) {
            accounts(refused).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            movements(refused, cash, today(), today()).andExpect(status().isForbidden());
            setOpening(refused, cash, "100.00", today()).andExpect(status().isForbidden());
        }
        send(null, get(ACCOUNTS), null).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherCooperativeNeverSeesTheseAccounts() throws Exception {
        String otherChairman = person(tenantB, "CHAIRMAN");

        accounts(otherChairman).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", Matchers.containsInAnyOrder(account(tenantB, "CASH").toString(),
                        account(tenantB, "BANK").toString())));
        movements(otherChairman, cash, today(), today()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        setOpening(otherChairman, cash, "100.00", today()).andExpect(status().isNotFound());
        accounts(person(tenantA, "TREASURER")).andExpect(jsonPath("$[0].opening").doesNotExist());
    }
}
