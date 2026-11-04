package app.nadel.treasury.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.DemoDay;
import app.nadel.DemoLedgerData;
import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.members.RoleHolders;
import app.nadel.money.Money;
import app.nadel.tenancy.TenantContext;
import app.nadel.treasury.IncomeExpense;
import app.nadel.treasury.TreasuryReadModel;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import nadel.contract.model.BankReconciliation;
import nadel.contract.model.BankMonthStatus;
import nadel.contract.model.CashCountStatus;
import nadel.contract.model.MoneyAccount;
import nadel.contract.model.MoneyAccountKind;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The demo cash book (feature 018 §2.15, §2.16, example 12; technical considerations 2.12): with the demo ledger
 * loaded, every demo payment row has exactly one journal line (the demo block writes them as the V32 backfill does) and
 * a demo payment can be reversed through the payments API; {@link DemoTreasury} then enters the opening balances,
 * expenses in four categories of the current month (one with the bundled receipt photo), two handovers, a count with a
 * booked shortage of 20,00, the previous month «Звірено» and the current month with a difference; a second run writes
 * nothing; the demo block runs again over all of it (the R__ re-run) and the runner enters it again; and income −
 * expenses + differences equals the change of cash plus bank, and the sum of the journal, in the previous and the
 * current month (example 12 identity on the demo data). The runner is not registered in the tests (the demo ledger
 * placeholder is off), so it is built here from the module's beans. Like {@code DemoDocumentsTests}, every test runs
 * once per {@link DemoDay#days() demo day}, with the demo loaded as on that day and the application's clock at its
 * noon.
 */
@ParameterizedClass(name = "on {0}")
@MethodSource("app.nadel.DemoDay#days")
// its own clock makes its own context: closed after the class, so its connections do not stay open for the rest
@DirtiesContext
@Import(DemoDay.Config.class)
class DemoTreasuryTests extends IntegrationTest {

    private static final String CHAIRMAN = "chairman@demo.local";

    private final JdbcTemplate owner = ownerJdbc();

    /** The day the demo is loaded on. */
    @Parameter
    private LocalDate day;

    @AfterAll
    static void putTheDemoMetersBack() {
        DemoDay.reset(ownerJdbc());
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MoneyAccounts accounts;

    @Autowired
    private Expenses expenses;

    @Autowired
    private Handovers handovers;

    @Autowired
    private Categories categories;

    @Autowired
    private CashCounts cashCounts;

    @Autowired
    private BankReconciliations reconciliations;

    @Autowired
    private LedgerReadModel ledger;

    @Autowired
    private RoleHolders roleHolders;

    @Autowired
    private TreasuryReadModel readModel;

    @Autowired
    private Clock clock;

    @Autowired
    private MockMvc mvc;

    private DemoTreasury demo() {
        return new DemoTreasury(transactionManager, accounts, expenses, handovers, categories, cashCounts,
                reconciliations, ledger, roleHolders, clock);
    }

    @BeforeEach
    void loadTheDemoLedger() {
        // the meters once per day (the context, and with it the schema, is not there before the first test)
        DemoDay.loadOnce(owner, day);
        DemoLedgerData.restore(owner, day);
    }

    @AfterEach
    void removeTheDemoCashBookAndLedger() {
        SecurityContextHolder.clearContext();
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> owner.queryForObject(
                    "select count(*) from event_publication where completion_date is null", Integer.class) == 0);
        } catch (ConditionTimeoutException e) {
            // a listener that failed keeps its publication incomplete; the clean-up goes on
        }
        DemoLedgerData.asLedgerOwner(owner, sql -> sql.update("delete from audit_entry where tenant_id = ? and "
                + "(entity_type in ('MONEY_ACCOUNT', 'EXPENSE', 'HANDOVER', 'CASH_COUNT', 'BANK_RECONCILIATION') "
                + "or action = 'PAYMENT_REVERSED')", DEMO_TENANT));
        DemoLedgerData.clear(owner);
        unlinkByEmail(DEMO_TENANT, CHAIRMAN);
    }

    @Test
    void theRunnerIsNotRegisteredInTheTests() {
        assertThat(context.getBeansOfType(DemoTreasury.class)).isEmpty();
    }

    /** FS 2.15: each demo payment row, the reversal of plot 3's payment included, has exactly one journal line. */
    @Test
    void everyDemoPaymentRowHasItsJournalLine() {
        assertThat(owner.queryForObject("select count(*) from payment where tenant_id = ?", Integer.class,
                DEMO_TENANT)).isPositive();
        assertThat(owner.queryForObject("select count(*) from payment p where p.tenant_id = ? and (select count(*) "
                + "from account_movement m where m.tenant_id = p.tenant_id and m.payment_row_id = p.id) <> 1",
                Integer.class, DEMO_TENANT)).isZero();
        Map<String, Object> reversal = owner.queryForMap("select m.amount, m.moved_on, "
                + "(p.created_at at time zone 'Europe/Kyiv')::date as made_on, m.reverses_movement_id, o.payment_row_id "
                + "as original_payment, p.reverses_payment_id from payment p join account_movement m "
                + "on m.payment_row_id = p.id join account_movement o on o.id = m.reverses_movement_id "
                + "where p.tenant_id = ? and p.kind = 'PAYMENT_REVERSAL'", DEMO_TENANT);
        assertThat((BigDecimal) reversal.get("amount")).isNegative();
        assertThat(reversal.get("moved_on")).isEqualTo(reversal.get("made_on"));
        assertThat(reversal.get("original_payment")).isEqualTo(reversal.get("reverses_payment_id"));
        assertThat(owner.queryForObject("select count(*) from account_movement where tenant_id = ? "
                + "and moved_on > ?", Integer.class, DEMO_TENANT, day)).isZero();
    }

    /** The reversal of a demo cash payment (plot 10) is saved with its journal line dated today. */
    @Test
    void aDemoPaymentCanBeReversed() throws Exception {
        demo().seed();
        UUID payment = owner.queryForObject("select y.id from payment y join plot p on p.id = y.plot_id "
                + "where y.tenant_id = ? and p.number = '10' and y.kind = 'PAYMENT'", UUID.class, DEMO_TENANT);

        mvc.perform(post("/api/v1/payments/" + payment + "/reverse")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(DEMO_TENANT, "sub-" + CHAIRMAN, CHAIRMAN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Платіж внесено двічі\"}"))
                .andExpect(status().is2xxSuccessful());

        Map<String, Object> line = owner.queryForMap("select m.amount, m.moved_on, o.amount as original "
                + "from payment r join account_movement m on m.payment_row_id = r.id "
                + "join account_movement o on o.id = m.reverses_movement_id "
                + "where r.tenant_id = ? and r.reverses_payment_id = ?", DEMO_TENANT, payment);
        assertThat((BigDecimal) line.get("amount")).isEqualByComparingTo(((BigDecimal) line.get("original")).negate());
        assertThat(line.get("moved_on")).isEqualTo(Date.valueOf(day));
    }

    /** FS 2.16: what the demo chairman sees on «Каса і банк», entered once. */
    @Test
    void theRunnerEntersTheDemoCashBookOnce() {
        YearMonth current = YearMonth.from(day);
        YearMonth previous = current.minusMonths(1);
        LocalDate firstMonth = owner.queryForObject("select min(month) from billing_month where tenant_id = ?",
                LocalDate.class, DEMO_TENANT);
        LocalDate opening = firstMonth.isBefore(previous.atDay(1)) ? firstMonth : previous.atDay(1);

        DemoTreasury.Seeded first = demo().seed();

        assertThat(first).isEqualTo(new DemoTreasury.Seeded(opening, 4, 2, 2));
        assertThat(owner.queryForList("select kind, opening_amount::text as amount, opening_date from money_account "
                + "where tenant_id = ? order by kind desc", DEMO_TENANT))
                .extracting(row -> row.get("kind"), row -> row.get("amount"), row -> row.get("opening_date"))
                .containsExactly(
                        tuple("CASH", "9000.00", Date.valueOf(opening)),
                        tuple("BANK", "48200.00", Date.valueOf(opening)));
        assertThat(owner.queryForObject("select count(distinct category_id) || '|' || count(*) from expense "
                + "where tenant_id = ? and reverses_expense_id is null and expense_date between ? and ?",
                String.class, DEMO_TENANT, current.atDay(1), day)).isEqualTo("4|4");
        List<Map<String, Object>> files = owner.queryForList("select a.content_type, a.content, e.account_id "
                + "from expense_attachment a join expense e on e.id = a.expense_id where a.tenant_id = ?", DEMO_TENANT);
        assertThat(files).singleElement().satisfies(file -> {
            assertThat(file.get("content_type")).isEqualTo("image/jpeg");
            assertThat(new String((byte[]) file.get("content"), StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
        });
        assertThat(owner.queryForObject("select count(*) from expense where tenant_id = ? and counterparty_enc is null",
                Integer.class, DEMO_TENANT)).isZero();
        UUID withPhoto = owner.queryForObject("select expense_id from expense_attachment where tenant_id = ?",
                UUID.class, DEMO_TENANT);
        assertThat(inDemo(() -> expenses.get(DEMO_TENANT, withPhoto)).getCounterparty())
                .isEqualTo("Будмаркет «Демо»");
        assertThat(owner.queryForList("select handed_on from handover where tenant_id = ? "
                + "and reverses_handover_id is null order by handed_on", LocalDate.class, DEMO_TENANT))
                .containsExactly(previous.atDay(25), current.atDay(4).isAfter(day) ? day : current.atDay(4));
        assertThat(owner.queryForObject("select count(*) from account_movement where tenant_id = ? and moved_on > ?",
                Integer.class, DEMO_TENANT, day)).isZero();

        List<MoneyAccount> cards = inDemo(() -> accounts.list(day));
        MoneyAccount cash = cards.getFirst();
        MoneyAccount bank = cards.get(1);
        assertThat(cash.getKind()).isEqualTo(MoneyAccountKind.CASH);
        assertThat(cash.getLastCount()).isNotNull();
        assertThat(cash.getLastCount().getDifference()).isEqualTo("-20.00");
        assertThat(cash.getLastCount().getStatus()).isEqualTo(CashCountStatus.BOOKED);
        assertThat(cash.getUnresolvedDifference()).isNull();
        assertThat(cash.getNegative()).isFalse();
        assertThat(owner.queryForObject("select m.amount::text from cash_count c join account_movement m "
                + "on m.id = c.booked_movement_id where c.tenant_id = ?", String.class, DEMO_TENANT)).isEqualTo("-20.00");
        assertThat(bank.getMonths()).extracting(month -> month.getMonth(), month -> month.getStatus())
                .startsWith(tuple(current.toString(), BankMonthStatus.NOT_RECONCILED),
                        tuple(previous.toString(), BankMonthStatus.RECONCILED));
        BankReconciliation open = inDemo(() -> reconciliations.get(current.toString()));
        assertThat(open.getBalanced()).isFalse();
        assertThat(open.getDifferences()).isNotNull();
        assertThat(open.getDifferences().getOutgoing()).isEqualTo("35.00");
        assertThat(open.getDifferences().getClosing()).isEqualTo("-35.00");

        String rows = "select (select count(*) from account_movement where tenant_id = ?) || '|' "
                + "|| (select count(*) from expense where tenant_id = ?) || '|' "
                + "|| (select count(*) from cash_count where tenant_id = ?) || '|' "
                + "|| (select count(*) from bank_reconciliation where tenant_id = ?)";
        String before = owner.queryForObject(rows, String.class, DEMO_TENANT, DEMO_TENANT, DEMO_TENANT, DEMO_TENANT);

        assertThat(demo().seed()).isEqualTo(DemoTreasury.Seeded.SKIPPED);
        // the start-up entry point: the same skip, logged, never thrown
        demo().run(null);
        assertThat(owner.queryForObject(rows, String.class, DEMO_TENANT, DEMO_TENANT, DEMO_TENANT, DEMO_TENANT))
                .isEqualTo(before);
    }

    /**
     * The R__ re-run: the demo block runs again over a full cash book (expenses with a file, handovers, a booked count
     * and a reconciled month) and leaves only the accounts without opening balances and
     * the payment lines; the runner then enters the cash book again.
     */
    @Test
    void theDemoBlockRunsAgainOverTheCashBookAndTheRunnerEntersItAgain() {
        demo().seed();

        DemoLedgerData.restore(owner, day);

        assertThat(owner.queryForObject("select count(*) from money_account where tenant_id = ? "
                + "and opening_amount is null", Integer.class, DEMO_TENANT)).isEqualTo(2);
        for (String table : new String[] {"expense", "expense_attachment", "handover", "cash_count",
                "bank_reconciliation", "bank_difference", "other_income"}) {
            assertThat(owner.queryForObject("select count(*) from " + table + " where tenant_id = ?", Integer.class,
                    DEMO_TENANT)).as(table).isZero();
        }
        assertThat(owner.queryForObject("select count(*) from account_movement where tenant_id = ?", Integer.class,
                DEMO_TENANT)).isEqualTo(owner.queryForObject("select count(*) from payment where tenant_id = ?",
                        Integer.class, DEMO_TENANT));

        assertThat(demo().seed().expenses()).isEqualTo(4);
    }

    /**
     * Example 12 on the demo data: for the previous and the current month, income − expenses + differences equals the
     * change of cash plus bank, and that change is the sum of the journal lines dated in the period.
     */
    @Test
    void incomeMinusExpensesPlusDifferencesIsTheChangeOfCashAndBank() {
        demo().seed();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("chairman", null,
                "PERM_CASHBOOK_VIEW"));
        YearMonth current = YearMonth.from(day);

        for (YearMonth month : new YearMonth[] {current.minusMonths(1), current}) {
            LocalDate from = month.atDay(1);
            LocalDate to = month.equals(current) ? day : month.atEndOfMonth();

            IncomeExpense figures = inDemo(() -> readModel.incomeExpense(from, to));

            Money change = figures.cashEnd().plus(figures.bankEnd()).minus(figures.cashStart())
                    .minus(figures.bankStart());
            assertThat(figures.result()).as(month.toString()).isEqualTo(change);
            BigDecimal journal = owner.queryForObject("select coalesce(sum(amount), 0) from account_movement "
                    + "where tenant_id = ? and moved_on between ? and ?", BigDecimal.class, DEMO_TENANT, from, to);
            assertThat(change.value()).as(month.toString()).isEqualByComparingTo(journal);
        }
        IncomeExpense now = inDemo(() -> readModel.incomeExpense(current.atDay(1), day));
        assertThat(now.expenses().value()).isEqualByComparingTo("14685.50");
        assertThat(now.expensesByCategory()).hasSize(4);
        assertThat(now.countDifferences().value()).isEqualByComparingTo("-20.00");
    }

    private <T> T inDemo(Supplier<T> read) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return TenantContext.runAs(DEMO_TENANT, () -> tx.execute(status -> read.get()));
    }
}
