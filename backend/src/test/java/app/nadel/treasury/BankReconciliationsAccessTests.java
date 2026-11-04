package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Who may see and work the bank reconciliation (feature 018 §2.13; ADR 0009): the comparison needs
 * {@code CASHBOOK_VIEW}, the statement and «Звірено» {@code CASHBOOK_ENTER}, a bank difference {@code CASHBOOK_APPROVE}
 * (the treasurer is refused); a member and the platform operator are 403; another cooperative only ever sees its own
 * months, never this one's statement or lines.
 */
class BankReconciliationsAccessTests extends TreasuryApiSupport {

    String chairman;
    String treasurer;
    YearMonth month;

    @BeforeEach
    void bankWithAStatement() throws Exception {
        chairman = person(tenantA, "CHAIRMAN");
        treasurer = person(tenantA, "TREASURER");
        month = YearMonth.from(today()).minusMonths(1);
        LocalDate opened = month.atDay(1);
        setOpening(chairman, account(tenantA, "BANK"), "500.00", opened).andExpect(status().isOk());
        enterStatement(treasurer, month, "500.00", "0.00", "0.00", "500.00").andExpect(status().isOk());
    }

    @Test
    void theTreasurerSeesEntersAndConfirmsButCannotBookADifference() throws Exception {
        reconciliation(treasurer, month).andExpect(status().isOk());
        enterStatement(treasurer, month, "500.00", "0.00", "0.00", "500.00").andExpect(status().isOk());
        bookBankDifference(treasurer, month, "-1.00", "Комісія").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        confirmMonth(treasurer, month).andExpect(status().isOk());
    }

    @Test
    void aViewOnlyRoleSeesTheMonthButCannotEnterConfirmOrBook() throws Exception {
        String viewer = customRole(tenantA, "CASHBOOK_VIEW");

        reconciliation(viewer, month).andExpect(status().isOk());
        accounts(viewer).andExpect(status().isOk());
        enterStatement(viewer, month, "500.00", "0.00", "0.00", "500.00").andExpect(status().isForbidden());
        confirmMonth(viewer, month).andExpect(status().isForbidden());
        bookBankDifference(viewer, month, "-1.00", "Комісія").andExpect(status().isForbidden());
    }

    @Test
    void aCustomRoleWithApproveBooksADifferenceAndOneWithEnterConfirms() throws Exception {
        String approver = customRole(tenantA, "CASHBOOK_APPROVE");
        String enterer = customRole(tenantA, "CASHBOOK_ENTER");

        reconciliation(approver, month).andExpect(status().isForbidden());
        bookBankDifference(approver, month, "-1.00", "Комісія").andExpect(status().isOk());
        enterStatement(enterer, month, "500.00", "0.00", "1.00", "499.00").andExpect(status().isOk());
        confirmMonth(enterer, month).andExpect(status().isOk());
    }

    @Test
    void aMemberAndThePlatformOperatorAreForbiddenAndNoTokenIsUnauthorized() throws Exception {
        String member = person(tenantA, "MEMBER");
        for (String bearer : new String[] {member, operator()}) {
            reconciliation(bearer, month).andExpect(status().isForbidden());
            enterStatement(bearer, month, "500.00", "0.00", "0.00", "500.00").andExpect(status().isForbidden());
            bookBankDifference(bearer, month, "-1.00", "x").andExpect(status().isForbidden());
            confirmMonth(bearer, month).andExpect(status().isForbidden());
        }
        reconciliation(null, month).andExpect(status().isUnauthorized());
        confirmMonth(null, month).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherCooperativeSeesOnlyItsOwnMonthEvenWithEveryPermission() throws Exception {
        String other = person(tenantB, "CHAIRMAN");
        reconciliation(other, month).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPENING_BALANCE_MISSING"));
        setOpening(other, account(tenantB, "BANK"), "7.00", month.atDay(1)).andExpect(status().isOk());

        reconciliation(other, month).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(account(tenantB, "BANK").toString()))
                .andExpect(jsonPath("$.status").value("NOT_RECONCILED"))
                .andExpect(jsonPath("$.books.opening").value("7.00"))
                .andExpect(jsonPath("$.statement").doesNotExist());
        confirmMonth(other, month).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANK_MONTH_NOT_BALANCED"));
        bookBankDifference(other, month, "-1.00", "x").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANK_STATEMENT_MISSING"));
        assertThat(ownerJdbc().queryForObject("select status from bank_reconciliation where tenant_id = ?",
                String.class, tenantA)).isEqualTo("NOT_RECONCILED");
        assertThat(ownerJdbc().queryForObject("select count(*) from account_movement where tenant_id = ?",
                Integer.class, tenantA)).isZero();
    }
}
