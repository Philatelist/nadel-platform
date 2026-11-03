package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The expense and income categories (feature 018 §2.9): each list seeded with its system codes and no names (the
 * client names them), added, renamed (old expenses show the new name), reordered and archived (no longer offered, still
 * on old expenses), never two of one list with the same name ignoring case; every change audited as
 * {@code CASHBOOK_CATEGORY_*}.
 */
class CategoriesTests extends TreasuryApiSupport {

    static final List<String> EXPENSE_CODES = List.of("ELECTRICITY_SUPPLIER", "WATER", "PAYROLL_TAXES",
            "NETWORK_REPAIR", "MATERIALS", "WASTE", "BANK_FEES", "TAXES_FEES", "OFFICE_COMMS", "OTHER");

    String chairman;
    UUID chairmanId;
    String treasurer;

    @BeforeEach
    void people() {
        chairman = person(tenantA, "CHAIRMAN");
        chairmanId = lastPerson;
        treasurer = person(tenantA, "TREASURER");
    }

    @Test
    void bothListsAreSeededInOrderWithTheirCodesAndNoNames() throws Exception {
        JsonNode expense = body(categories(treasurer, "EXPENSE", false).andExpect(status().isOk()));
        JsonNode income = body(categories(treasurer, "INCOME", false).andExpect(status().isOk()));

        assertThat(codes(expense)).containsExactlyElementsOf(EXPENSE_CODES);
        assertThat(codes(income)).containsExactly("BANK_INTEREST", "GRANTS", "SALES", "OTHER");
        for (JsonNode category : expense) {
            assertThat(category.has("name")).isFalse();
            assertThat(category.get("kind").asString()).isEqualTo("EXPENSE");
            assertThat(category.get("archived").asBoolean()).isFalse();
        }
        assertThat(expense.get(0).get("sortOrder").asInt()).isEqualTo(1);
        // the expense list is the default
        assertThat(codes(body(send(treasurer, get(CATEGORIES), null).andExpect(status().isOk()))))
                .containsExactlyElementsOf(EXPENSE_CODES);
    }

    @Test
    void aRenamedCategoryShowsItsNewNameOnOldExpensesToo() throws Exception {
        UUID other = category(tenantA, "EXPENSE", "OTHER");
        setOpening(chairman, account(tenantA, "CASH"), "100.00", today()).andExpect(status().isOk());
        UUID expense = recordExpense(treasurer, expense("CASH", today(), "5.00", other));

        updateCategory(chairman, other, Map.of("name", "  Інші витрати  ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Інші витрати"))
                .andExpect(jsonPath("$.systemCode").value("OTHER"));

        expenseCard(treasurer, expense).andExpect(jsonPath("$.category.name").value("Інші витрати"))
                .andExpect(jsonPath("$.category.systemCode").value("OTHER"));
        assertThat(ownerJdbc().queryForObject("select name from expense_category where id = ?", String.class, other))
                .isEqualTo("Інші витрати");
        // the same name again changes nothing and writes no entry
        updateCategory(chairman, other, Map.of("name", "Інші витрати")).andExpect(status().isOk());
        Map<String, Object> entry = awaitAudit(other, "CASHBOOK_CATEGORY_RENAMED", 1).getFirst();
        assertThat(entry).containsEntry("entity_type", "CASHBOOK_CATEGORY")
                .containsEntry("entity_label", "Інші витрати").containsEntry("actor_member_id", chairmanId);
        assertThat((String) entry.get("changes")).contains("base:OTHER", "Інші витрати");
        assertThat(ownerJdbc().queryForObject("select count(*) from audit_entry where entity_id = ? and action = ?",
                Integer.class, other, "CASHBOOK_CATEGORY_RENAMED")).isOne();
    }

    @Test
    void anArchivedCategoryIsNotOfferedButStaysOnOldExpenses() throws Exception {
        UUID waste = category(tenantA, "EXPENSE", "WASTE");
        setOpening(chairman, account(tenantA, "CASH"), "100.00", today()).andExpect(status().isOk());
        UUID expense = recordExpense(treasurer, expense("CASH", today(), "5.00", waste));

        updateCategory(chairman, waste, Map.of("archived", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));

        assertThat(codes(body(categories(treasurer, "EXPENSE", false)))).doesNotContain("WASTE");
        assertThat(codes(body(categories(treasurer, "EXPENSE", true)))).contains("WASTE");
        createExpense(treasurer, expense("CASH", today(), "5.00", waste)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"))
                .andExpect(jsonPath("$.errors[0].code").value("ARCHIVED"));
        expenseCard(treasurer, expense).andExpect(jsonPath("$.category.systemCode").value("WASTE"))
                .andExpect(jsonPath("$.category.archived").value(true));
        Map<String, Object> entry = awaitAudit(waste, "CASHBOOK_CATEGORY_ARCHIVED", 1).getFirst();
        assertThat(entry).containsEntry("entity_label", "base:WASTE");
        assertThat((String) entry.get("changes")).contains("\"archived\"", "false", "true");
        // archiving again changes nothing; it can be offered again
        updateCategory(chairman, waste, Map.of("archived", true)).andExpect(status().isOk());
        updateCategory(chairman, waste, Map.of("archived", false)).andExpect(jsonPath("$.archived").value(false));
        awaitAudit(waste, "CASHBOOK_CATEGORY_ARCHIVED", 2);
        assertThat(codes(body(categories(treasurer, "EXPENSE", false)))).contains("WASTE");
    }

    @Test
    void aCategoryIsAddedAtTheEndAndNoTwoOfOneListShareAName() throws Exception {
        JsonNode added = body(addCategory(chairman, "EXPENSE", " Охорона ").andExpect(status().isCreated()));

        assertThat(added.get("name").asString()).isEqualTo("Охорона");
        assertThat(added.has("systemCode")).isFalse();
        assertThat(added.get("sortOrder").asInt()).isEqualTo(11);
        UUID id = UUID.fromString(added.get("id").asString());
        addCategory(chairman, "EXPENSE", "ОХОРОНА").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_NAME_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("CATEGORY_NAME_TAKEN"));
        updateCategory(chairman, category(tenantA, "EXPENSE", "WATER"), Map.of("name", "охорона"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATEGORY_NAME_TAKEN"));
        // an archived category keeps its name taken; the other list and another cooperative are free
        updateCategory(chairman, id, Map.of("archived", true)).andExpect(status().isOk());
        addCategory(chairman, "EXPENSE", "Охорона").andExpect(status().isConflict());
        addCategory(chairman, "INCOME", "Охорона").andExpect(status().isCreated());
        addCategory(person(tenantB, "CHAIRMAN"), "EXPENSE", "Охорона").andExpect(status().isCreated());
        // a blank name
        addCategory(chairman, "EXPENSE", "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        addCategory(chairman, "EXPENSE", "").andExpect(status().isBadRequest());
        updateCategory(chairman, id, Map.of("name", " ")).andExpect(status().isBadRequest());
        updateCategory(chairman, UUID.randomUUID(), Map.of("name", "Нова")).andExpect(status().isNotFound());

        Map<String, Object> entry = awaitAudit(id, "CASHBOOK_CATEGORY_ADDED", 1).getFirst();
        assertThat(entry).containsEntry("entity_label", "Охорона");
        assertThat((String) entry.get("changes")).contains("\"kind\"", "EXPENSE", "\"name\"", "Охорона");
    }

    @Test
    void aListIsReorderedAsAWholeAndEachMovedCategoryIsAudited() throws Exception {
        List<UUID> ids = ids(body(categories(chairman, "INCOME", false)));
        List<UUID> reversed = new ArrayList<>(ids.reversed());

        reorderCategories(chairman, "INCOME", reversed).andExpect(status().isNoContent());

        assertThat(ids(body(categories(chairman, "INCOME", false)))).containsExactlyElementsOf(reversed);
        Map<String, Object> entry = awaitAudit(reversed.getFirst(), "CASHBOOK_CATEGORY_REORDERED", 1).getFirst();
        assertThat((String) entry.get("changes")).contains("\"position\"", "\"4\"", "\"1\"");
        await().atMost(Duration.ofSeconds(10)).until(() -> reorderedEntries() == 4);
        // the same order again moves nothing; a stale or partial list is refused
        reorderCategories(chairman, "INCOME", reversed).andExpect(status().isNoContent());
        assertThat(reorderedEntries()).isEqualTo(4);
        reorderCategories(chairman, "INCOME", reversed.subList(0, 3)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("ids"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        reorderCategories(chairman, "EXPENSE", reversed).andExpect(status().isBadRequest());
        List<UUID> duplicated = new ArrayList<>(reversed.subList(0, 3));
        duplicated.add(reversed.getFirst());
        reorderCategories(chairman, "INCOME", duplicated).andExpect(status().isBadRequest());
    }

    private int reorderedEntries() {
        return ownerJdbc().queryForObject("select count(*) from audit_entry where tenant_id = ? and action = ?",
                Integer.class, tenantA, "CASHBOOK_CATEGORY_REORDERED");
    }

    private static List<String> codes(JsonNode categories) {
        List<String> codes = new ArrayList<>();
        categories.forEach(category -> codes.add(category.has("systemCode") ? category.get("systemCode").asString()
                : category.get("name").asString()));
        return codes;
    }

    private static List<UUID> ids(JsonNode categories) {
        List<UUID> ids = new ArrayList<>();
        categories.forEach(category -> ids.add(UUID.fromString(category.get("id").asString())));
        return ids;
    }
}
