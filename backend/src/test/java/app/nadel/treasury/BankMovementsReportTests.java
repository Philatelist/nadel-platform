package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Bank movements («Рух коштів по банку», {@code bank-movements}, feature 018 §2.12) through the API: the bank book as
 * the cash book shows the cash (each day's closing balance is the bank balance of «Каса і банк»), the counterparty of a
 * bank expense, and the sheet {@code months} with the reconciliation status of every month of the period.
 */
class BankMovementsReportTests extends TreasuryReportsSupport {

    @Test
    void theBankBookFollowsTheBankBalanceAndListsTheStatusOfEveryMonth() throws Exception {
        String chairman = person(tenantA, "CHAIRMAN");
        LocalDate opening = today().minusDays(40);
        setOpening(chairman, account(tenantA, "CASH"), "1000.00", opening).andExpect(status().isOk());
        setOpening(chairman, account(tenantA, "BANK"), "5000.00", opening).andExpect(status().isOk());
        UUID plot = paymentPlot(tenantA);
        pay(chairman, plot, "BANK", "800.00", today().minusDays(35));
        recordExpense(chairman, with(expense("BANK", today().minusDays(9), "150.00",
                category(tenantA, "EXPENSE", "BANK_FEES")), "counterparty", "ПриватБанк"));
        recordOtherIncome(chairman, otherIncome("BANK", today().minusDays(5), "12.50",
                category(tenantA, "INCOME", "BANK_INTEREST")));
        recordHandover(chairman, handover(today().minusDays(5), "300.00", "КВ-9"));
        // the opening month is reconciled after its lines were entered, so nothing reopens it
        ownerJdbc().update("insert into bank_reconciliation (tenant_id, month, statement_opening, statement_incoming, "
                + "statement_outgoing, statement_closing, status, reconciled_by, reconciled_at) values (?, ?, 0, 0, "
                + "0, 0, 'RECONCILED', ?, now())", tenantA, YearMonth.from(opening).atDay(1), lastPerson);

        JsonNode preview = preview(chairman, "bank-movements", opening, today());
        JsonNode main = sheet(preview, "main");

        assertThat(cell(main, main.get("rows").get(0), "opening")).isEqualTo("5000.00");
        for (JsonNode day : rowsWith(main, "line", "DAY")) {
            LocalDate date = LocalDate.parse(cell(main, day, "date"));
            assertThat(cell(main, day, "closing")).as("closing of %s", date)
                    .isEqualTo(balanceOn(chairman, "BANK", date));
        }
        assertThat(rowsWith(main, "line", "DAY")).hasSize(3);
        assertThat(cell(main, main.get("rows").get(main.get("rows").size() - 1), "closing"))
                .isEqualTo(balanceOn(chairman, "BANK", today())).isEqualTo("5962.50");
        JsonNode fee = rowsWith(main, "kind", "EXPENSE").getFirst();
        assertThat(cell(main, fee, "counterparty")).isEqualTo("ПриватБанк");
        assertThat(cell(main, fee, "spending")).isEqualTo("150.00");
        assertThat(cell(main, rowsWith(main, "kind", "HANDOVER_IN").getFirst(), "receipt")).isEqualTo("300.00");
        assertThat(cell(main, total(main, "all"), "receipt")).isEqualTo("1112.50");
        assertThat(cell(main, total(main, "all"), "spending")).isEqualTo("150.00");
        assertThat(rowsWith(main, "kind", "HANDOVER_OUT")).isEmpty();

        JsonNode months = sheet(preview, "months");
        YearMonth first = YearMonth.from(opening);
        YearMonth last = YearMonth.from(today());
        assertThat(months.get("rows")).hasSize((int) first.until(last, ChronoUnit.MONTHS) + 1);
        JsonNode firstMonth = months.get("rows").get(0);
        assertThat(cell(months, firstMonth, "month")).isEqualTo(first.toString());
        assertThat(cell(months, firstMonth, "reconciliation")).isEqualTo("RECONCILED");
        JsonNode lastMonth = months.get("rows").get(months.get("rows").size() - 1);
        assertThat(cell(months, lastMonth, "month")).isEqualTo(last.toString());
        assertThat(cell(months, lastMonth, "reconciliation")).isEqualTo("NOT_RECONCILED");

        reportFile(chairman, "bank-movements", "from", opening.toString(), "to", today().toString(), "lang", "en")
                .andExpect(status().isOk());
    }

    @Test
    void theBankBookNeedsTheBankOpeningBalance() throws Exception {
        String chairman = person(tenantA, "CHAIRMAN");
        setOpening(chairman, account(tenantA, "CASH"), "10.00", today().minusDays(5)).andExpect(status().isOk());

        report(chairman, "bank-movements", "from", today().minusDays(5).toString(), "to", today().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPENING_BALANCE_MISSING"));
    }
}
