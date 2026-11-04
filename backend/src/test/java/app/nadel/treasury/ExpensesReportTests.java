package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * The expenses report («Витрати», {@code expenses}, feature 018 §2.12) through the API: the original expenses of the
 * period oldest first, each with its expense id and number of files (the screen links them), the counterparty, a
 * reversed expense marked and left out of the total; the account and category filters; unknown or income categories
 * refused.
 */
class ExpensesReportTests extends TreasuryReportsSupport {

    private String chairman;
    private LocalDate from;
    private UUID materials;
    private UUID bankFees;
    private UUID withFiles;
    private UUID fee;
    private UUID reversed;

    @BeforeEach
    void expenses() throws Exception {
        chairman = person(tenantA, "CHAIRMAN");
        from = today().minusDays(10);
        setOpening(chairman, account(tenantA, "CASH"), "1000.00", from).andExpect(status().isOk());
        setOpening(chairman, account(tenantA, "BANK"), "1000.00", from).andExpect(status().isOk());
        materials = category(tenantA, "EXPENSE", "MATERIALS");
        bankFees = category(tenantA, "EXPENSE", "BANK_FEES");
        withFiles = recordExpense(chairman, with(with(with(expense("CASH", today().minusDays(8), "100.00", materials),
                "counterparty", "ФОП Іваненко"), "documentNumber", "Ч-1"), "documentDate",
                today().minusDays(8).toString()), pdf("first.pdf"), pdf("second.pdf"));
        fee = recordExpense(chairman, expense("BANK", today().minusDays(6), "40.00", bankFees));
        reversed = recordExpense(chairman, expense("CASH", today().minusDays(5), "30.00", materials));
        reverseExpense(chairman, reversed, "Внесено двічі").andExpect(status().isOk());
    }

    @Test
    void everyExpenseIsListedWithItsFilesAndAReversedOneCountsInNoTotal() throws Exception {
        JsonNode preview = preview(chairman, "expenses", from, today());
        JsonNode sheet = sheet(preview, "main");

        assertThat(expenseIds(sheet)).containsExactly(withFiles, fee, reversed);
        JsonNode first = sheet.get("rows").get(0);
        assertThat(cell(sheet, first, "date")).isEqualTo(today().minusDays(8).toString());
        assertThat(cell(sheet, first, "account")).isEqualTo("CASH");
        assertThat(cell(sheet, first, "category")).isEqualTo(materials.toString());
        assertThat(cell(sheet, first, "counterparty")).isEqualTo("ФОП Іваненко");
        assertThat(cell(sheet, first, "document")).isEqualTo("Ч-1");
        assertThat(cell(sheet, first, "documentDate")).isEqualTo(today().minusDays(8).toString());
        assertThat(cell(sheet, first, "amount")).isEqualTo("100.00");
        assertThat(cell(sheet, first, "attachments")).isEqualTo("2");
        assertThat(cell(sheet, first, "reversed")).isEqualTo("false");
        assertThat(cell(sheet, sheet.get("rows").get(2), "reversed")).isEqualTo("true");
        assertThat(cell(sheet, total(sheet, "all"), "amount")).isEqualTo("140.00");
        List<String> codes = new ArrayList<>();
        preview.get("expenseCategories").forEach(category -> codes.add(category.get("systemCode").asString()));
        assertThat(codes).containsExactlyInAnyOrder("MATERIALS", "BANK_FEES");

        reportFile(chairman, "expenses", "from", from.toString(), "to", today().toString(), "lang", "uk")
                .andExpect(status().isOk());
    }

    @Test
    void theAccountAndCategoryFiltersChooseTheExpenses() throws Exception {
        assertThat(expenseIds(sheet(preview(chairman, "expenses", from, today(), "expenseCategoryIds",
                materials.toString()), "main"))).containsExactly(withFiles, reversed);
        assertThat(expenseIds(sheet(preview(chairman, "expenses", from, today(), "account", "BANK"), "main")))
                .containsExactly(fee);
        JsonNode both = preview(chairman, "expenses", from, today(), "account", "BANK", "expenseCategoryIds",
                materials.toString());
        assertThat(sheet(both, "main").get("rows")).isEmpty();
        assertThat(both.get("filters").toString()).contains("expenseCategoryIds").contains("account");
        assertThat(expenseIds(sheet(preview(chairman, "expenses", today().minusDays(6), today()), "main")))
                .containsExactly(fee, reversed);
    }

    @Test
    void aCategoryThatIsNotAnExpenseCategoryOfTheCooperativeIsRefused() throws Exception {
        for (UUID category : List.of(UUID.randomUUID(), category(tenantA, "INCOME", "BANK_INTEREST"),
                category(tenantB, "EXPENSE", "MATERIALS"))) {
            report(chairman, "expenses", "from", from.toString(), "to", today().toString(), "expenseCategoryIds",
                    category.toString())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("REPORT_FILTER_INVALID"))
                    .andExpect(jsonPath("$.errors[0].field").value("expenseCategoryIds[0]"))
                    .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        }
    }

    private static List<UUID> expenseIds(JsonNode sheet) {
        List<UUID> ids = new ArrayList<>();
        sheet.get("rows").forEach(row -> ids.add(UUID.fromString(row.get("expenseId").asString())));
        return ids;
    }

    private static MockMultipartFile pdf(String name) {
        return file(name, "application/pdf", "%PDF-1.4\n1 0 obj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII));
    }
}
