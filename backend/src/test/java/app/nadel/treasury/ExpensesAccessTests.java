package app.nadel.treasury;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.files.TestImages;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Who reaches the expenses and the categories (feature 018 §2.13, ADR 0009): reading expenses, their files and the
 * categories needs {@code CASHBOOK_VIEW}; recording an expense {@code CASHBOOK_ENTER}; reversing it and changing the
 * categories {@code CASHBOOK_APPROVE}. Permissions, never role names: a custom role with the right alone is enough. A
 * member, a role without the rights and the platform operator are refused (403), a caller without a token is 401, and
 * another cooperative's expenses and categories are not found (404).
 */
class ExpensesAccessTests extends TreasuryApiSupport {

    UUID expense;
    UUID materials;

    @BeforeEach
    void anExpenseWithAFile() throws Exception {
        setOpening(person(tenantA, "CHAIRMAN"), account(tenantA, "CASH"), "500.00", today())
                .andExpect(status().isOk());
        materials = category(tenantA, "EXPENSE", "MATERIALS");
        expense = recordExpense(person(tenantA, "TREASURER"), expense("CASH", today(), "10.00", materials),
                file("a.pdf", "application/pdf", TestImages.pdf()));
    }

    @Test
    void viewersReadExpensesTheirFilesAndTheCategories() throws Exception {
        for (String viewer : List.of(person(tenantA, "CHAIRMAN"), person(tenantA, "TREASURER"),
                customRole(tenantA, "CASHBOOK_VIEW"))) {
            send(viewer, get(EXPENSES), null).andExpect(status().isOk());
            expenseCard(viewer, expense).andExpect(status().isOk());
            download(viewer, expense, 1).andExpect(status().isOk());
            categories(viewer, "EXPENSE", false).andExpect(status().isOk());
        }
    }

    @Test
    void enteringNeedsTheRightToEnterAndNothingElse() throws Exception {
        String enterOnly = customRole(tenantA, "CASHBOOK_ENTER");

        createExpense(enterOnly, expense("CASH", today(), "1.00", materials)).andExpect(status().isCreated());
        send(enterOnly, get(EXPENSES), null).andExpect(status().isForbidden());
        expenseCard(enterOnly, expense).andExpect(status().isForbidden());
        download(enterOnly, expense, 1).andExpect(status().isForbidden());
        reverseExpense(enterOnly, expense, "Помилка").andExpect(status().isForbidden());
        addCategory(enterOnly, "EXPENSE", "Нова").andExpect(status().isForbidden());
        createExpense(customRole(tenantA, "CASHBOOK_VIEW"), expense("CASH", today(), "1.00", materials))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void reversingAndTheCategoriesNeedTheRightToApprove() throws Exception {
        String treasurer = person(tenantA, "TREASURER");
        UUID water = category(tenantA, "EXPENSE", "WATER");
        reverseExpense(treasurer, expense, "Помилка").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        addCategory(treasurer, "EXPENSE", "Нова").andExpect(status().isForbidden());
        updateCategory(treasurer, water, Map.of("name", "Вода і каналізація")).andExpect(status().isForbidden());
        reorderCategories(treasurer, "EXPENSE", List.of()).andExpect(status().isForbidden());

        String approver = customRole(tenantA, "CASHBOOK_APPROVE");
        addCategory(approver, "EXPENSE", "Нова").andExpect(status().isCreated());
        updateCategory(approver, water, Map.of("name", "Вода і каналізація")).andExpect(status().isOk());
        reverseExpense(approver, expense, "Помилка").andExpect(status().isOk());
        categories(approver, "EXPENSE", false).andExpect(status().isForbidden());
    }

    @Test
    void aMemberARoleWithoutTheRightsAndTheOperatorAreRefused() throws Exception {
        for (String refused : List.of(person(tenantA, "MEMBER"), customRole(tenantA, "PAYMENTS_VIEW", "MEMBERS_VIEW"),
                operator())) {
            send(refused, get(EXPENSES), null).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            expenseCard(refused, expense).andExpect(status().isForbidden());
            download(refused, expense, 1).andExpect(status().isForbidden());
            createExpense(refused, expense("CASH", today(), "1.00", materials)).andExpect(status().isForbidden());
            reverseExpense(refused, expense, "Помилка").andExpect(status().isForbidden());
            categories(refused, "EXPENSE", false).andExpect(status().isForbidden());
            addCategory(refused, "EXPENSE", "Нова").andExpect(status().isForbidden());
        }
        send(null, get(EXPENSES), null).andExpect(status().isUnauthorized());
        download(null, expense, 1).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherCooperativeNeverSeesTheseExpensesOrCategories() throws Exception {
        String otherChairman = person(tenantB, "CHAIRMAN");
        setOpening(otherChairman, account(tenantB, "CASH"), "500.00", today()).andExpect(status().isOk());

        send(otherChairman, get(EXPENSES), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
        expenseCard(otherChairman, expense).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        download(otherChairman, expense, 1).andExpect(status().isNotFound());
        reverseExpense(otherChairman, expense, "Помилка").andExpect(status().isNotFound());
        updateCategory(otherChairman, materials, Map.of("name", "Чуже")).andExpect(status().isNotFound());
        // another cooperative's category and files are not usable for an expense
        createExpense(otherChairman, expense("CASH", today(), "1.00", materials)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"));
        createExpense(otherChairman, with(expense("CASH", today(), "1.00", category(tenantB, "EXPENSE", "MATERIALS")),
                "reuseAttachments", List.of(Map.of("expenseId", expense.toString(), "n", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reuseAttachments[0]"));
    }
}
