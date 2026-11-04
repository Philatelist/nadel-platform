package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.tenancy.TenantContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reconciliation act of a plot ({@code GET /plots/{id}/reconciliation-act.pdf}, feature 018 §2.10, technical
 * considerations 2.8) end to end on the documents' cooperative: plot 9 of the member, membership 300.00 charged on the
 * first day of the previous month, a cash payment of 120.00 today (receipt 1) and one of 50.00 (receipt 2) reversed
 * today. From the first day of the previous month to today the act shows the requisites, the plot and its owner, the
 * period, the balances at both ends, the lines with «Нараховано» and «Сплачено», the turnover, «заборгованість …
 * становить 180,00 ₴» and the signature blocks; an overpayment and a settled plot give the other two sentences; the
 * same period gives the same bytes; a start before the plot's first ledger day gives the act from that day; without a
 * treasurer the cooperative's line is empty; the period, common land and the requisites are checked; the facade draws
 * the same file.
 */
class ReconciliationActTests extends DocumentsTestSupport {

    static final String ACT = "/api/v1/plots/%s/reconciliation-act.pdf";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String[] MONTHS = {"січень", "лютий", "березень", "квітень", "травень", "червень", "липень",
        "серпень", "вересень", "жовтень", "листопад", "грудень"};

    @Autowired
    DocumentsFacade facade;

    @Autowired
    TransactionTemplate tx;

    private UUID nine;
    private LocalDate first;

    @BeforeEach
    void thePlot() {
        nine = plot("9", "Садова");
        own(memberId, nine);
        charge(nine, "MEMBERSHIP", "300.00", previousMonth());
        first = YearMonth.parse(previousMonth()).atDay(1);
    }

    ResultActions act(String bearer, UUID plot, LocalDate from, LocalDate to) throws Exception {
        return send(bearer, get(ACT.formatted(plot)).param("from", from.toString()).param("to", to.toString()), null);
    }

    private byte[] pdf(UUID plot, LocalDate from, LocalDate to) throws Exception {
        return act(chairman, plot, from, to).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
    }

    /** The text of the act with every run of white space (no-break spaces and line breaks included) as one space. */
    private String text(UUID plot, LocalDate from, LocalDate to) throws Exception {
        return flat(text(pdf(plot, from, to)));
    }

    private static String flat(String text) {
        return text.replaceAll("[\\s\\u00a0\\u202f]+", " ");
    }

    private static String day(LocalDate day) {
        return DAY.format(day);
    }

    private static String month(LocalDate day) {
        return MONTHS[day.getMonthValue() - 1] + " " + day.getYear();
    }

    @Test
    void theActShowsTheRequisitesThePlotTheBalancesTheLinesTheTurnoverTheDebtAndTheSignatures() throws Exception {
        pay(nine, "CASH", "120.00", null);
        reverse(firstPayment(pay(nine, "CASH", "50.00", null)));

        ResultActions call = act(chairman, nine, first, today()).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(call.andReturn().getResponse().getHeader("Content-Disposition")).contains(
                "reconciliation_act_plot-9_" + first + "_" + today() + ".pdf");
        String text = flat(text(call.andReturn().getResponse().getContentAsByteArray()));

        assertThat(text).contains("Садівниче товариство «Документи»", "Код ЄДРПОУ: 12345678",
                "IBAN: UA850000000000000000000123456", "Акт звірки взаєморозрахунків",
                "за період з " + day(first) + " по " + day(today()), "Ділянка № 9, Садова",
                "Власники: " + MEMBER_NAME, "Дата", "Документ, зміст операції", "Нараховано", "Сплачено",
                "Сальдо на початок періоду", "Нарахування: Членський внесок, " + month(first), "300,00 ₴",
                "Оплата готівкою, квитанція № 1", "120,00 ₴", "Оплата готівкою, квитанція № 2", "50,00 ₴",
                "Сторно оплати готівкою, квитанція № 2", "-50,00 ₴", "Обороти за період",
                "Сальдо на кінець періоду",
                "Сальдо на початок періоду (станом на " + day(first.minusDays(1))
                        + "): заборгованість 0,00 ₴, аванс (передоплата) 0,00 ₴",
                "Сальдо на кінець періоду (станом на " + day(today())
                        + "): заборгованість 180,00 ₴, аванс (передоплата) 0,00 ₴",
                "За даними кооперативу заборгованість станом на " + day(today()) + " становить 180,00 ₴",
                "Від кооперативу (скарбник): " + TREASURER_NAME, "Член кооперативу:", "(підпис)");
        // the lines come by date: the charge of the previous month before today's payments
        assertThat(text.indexOf("Нарахування: Членський внесок")).isLessThan(text.indexOf("квитанція № 1"));
        assertThat(text.indexOf("квитанція № 1")).isLessThan(text.indexOf("Сторно оплати"));
        assertThat(text).doesNotContain("переплата", "відсутня");
    }

    @Test
    void anOverpaymentAndASettledPlotGiveTheirSentencesAndALaterPeriodStartsWithTheBalanceBeforeIt()
            throws Exception {
        LocalDate thisMonth = YearMonth.from(today()).atDay(1);
        String later = text(nine, thisMonth, today());
        assertThat(later).contains("Сальдо на початок періоду (станом на " + day(thisMonth.minusDays(1))
                + "): заборгованість 300,00 ₴", "За даними кооперативу заборгованість станом на " + day(today())
                + " становить 300,00 ₴").doesNotContain("Нарахування:");

        pay(nine, "CASH", "300.00", null);
        assertThat(text(nine, first, today())).contains("За даними кооперативу заборгованість станом на "
                + day(today()) + " відсутня").doesNotContain("становить");

        pay(nine, "BANK", "75.50", null);
        assertThat(text(nine, first, today())).contains("Оплата через банк", "аванс (передоплата) 75,50 ₴",
                "За даними кооперативу переплата станом на " + day(today()) + " становить 75,50 ₴");
    }

    @Test
    void theSamePeriodGivesTheSameBytesAndTheFacadeDrawsTheSameFile() throws Exception {
        pay(nine, "CASH", "120.00", null);

        byte[] once = pdf(nine, first, today());
        byte[] again = pdf(nine, first, today());
        byte[] fromFacade = TenantContext.runAs(tenant, () -> tx.execute(status ->
                facade.reconciliationActPdf(nine, first, today())));

        assertThat(again).isEqualTo(once);
        assertThat(fromFacade).isEqualTo(once);
        assertThat(pdf(nine, first.plusDays(1), today())).isNotEqualTo(once);
    }

    @Test
    void withoutATreasurerTheCooperativesLineIsEmpty() throws Exception {
        owner().update("delete from member_role where tenant_id = ? and role_id in (select id from role "
                + "where tenant_id = ? and base_code = 'TREASURER')", tenant, tenant);

        assertThat(text(nine, first, today())).contains("Від кооперативу (скарбник): (підпис)")
                .doesNotContain(TREASURER_NAME);
    }

    @Test
    void aStartBeforeThePlotsFirstLedgerDayGivesTheActFromThatDay() throws Exception {
        LocalDate newYear = LocalDate.of(first.getYear(), 1, 1).minusYears(1);
        ResultActions call = act(chairman, nine, newYear, today()).andExpect(status().isOk());
        assertThat(call.andReturn().getResponse().getHeader("Content-Disposition")).contains(
                "reconciliation_act_plot-9_" + first + "_" + today() + ".pdf");
        byte[] early = call.andReturn().getResponse().getContentAsByteArray();

        assertThat(flat(text(early))).contains("за період з " + day(first) + " по " + day(today()),
                "Сальдо на початок періоду (станом на " + day(first.minusDays(1)) + "): заборгованість 0,00 ₴",
                "Нарахування: Членський внесок, " + month(first)).doesNotContain(day(newYear));
        // the same act as asked from the first day, and the same bytes for the same request
        assertThat(early).isEqualTo(pdf(nine, first, today())).isEqualTo(pdf(nine, newYear, today()));
        byte[] fromFacade = TenantContext.runAs(tenant, () -> tx.execute(status ->
                facade.reconciliationActPdf(nine, newYear, today())));
        assertThat(fromFacade).isEqualTo(early);
    }

    @Test
    void thePeriodMustNotEndAfterTodayNorStartAfterItsEndAndThePlotNeedsALedger() throws Exception {
        invalid(act(chairman, nine, first, today().plusDays(1)), "to");
        invalid(act(chairman, nine, today(), today().minusDays(1)), "from");
        // a period that ends before the plot's first ledger day covers nothing
        invalid(act(chairman, nine, first.minusDays(10), first.minusDays(1)), "from");
        invalid(act(chairman, plot("10", "Садова"), today(), today()), "from");
        act(chairman, nine, first, first).andExpect(status().isOk());
        send(chairman, get(ACT.formatted(nine)).param("from", first.toString()), null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void commonLandIsRefusedAnArchivedPlotGetsItsActAndTheRequisitesAreRequired() throws Exception {
        UUID road = plot("Дорога", "Садова");
        owner().update("update plot set kind = 'COMMON' where id = ?", road);
        act(chairman, road, first, today()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_COMMON_LAND"));

        UUID archived = plot("11", "Садова");
        charge(archived, "MEMBERSHIP", "40.00", previousMonth());
        owner().update("update plot set archived_at = now() where id = ?", archived);
        assertThat(text(archived, first, today())).contains("Ділянка № 11",
                "заборгованість станом на " + day(today()) + " становить 40,00 ₴");

        owner().update("update cooperative_settings set iban = null where tenant_id = ?", tenant);
        act(chairman, nine, first, today()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUISITES_MISSING"))
                .andExpect(jsonPath("$.missing[0]").value("IBAN"));
    }

    private static void invalid(ResultActions call, String field) throws Exception {
        call.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
    }
}
