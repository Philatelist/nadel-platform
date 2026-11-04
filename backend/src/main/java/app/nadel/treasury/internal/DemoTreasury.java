package app.nadel.treasury.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.members.MemberRef;
import app.nadel.members.RoleHolders;
import app.nadel.problem.ApiProblemException;
import app.nadel.tenancy.TenantContext;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.BankFigures;
import nadel.contract.model.BankStatementInput;
import nadel.contract.model.CashCount;
import nadel.contract.model.CashCountInput;
import nadel.contract.model.Expense;
import nadel.contract.model.ExpenseCategory;
import nadel.contract.model.ExpenseCategoryKind;
import nadel.contract.model.ExpenseInput;
import nadel.contract.model.HandoverInput;
import nadel.contract.model.MoneyAccount;
import nadel.contract.model.MoneyAccountKind;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Enters the demo cooperative's cash book (feature 018 §2.16, technical considerations 2.12) once the application has
 * started. {@code R__demo_data.sql} cannot write it, because an expense seals its counterparty with the Java cipher
 * (ADR 0016); its demo ledger block wipes the cash book of the demo cooperative (accounts and opening balances
 * included) whenever it re-runs and writes only the journal lines of its payments, and this runner enters the rest
 * again on the next start.
 * <p>
 * <b>When.</b> Registered only when the {@code db/demo} location is active and the demo ledger placeholder
 * {@code demoledger} is {@code on}: the {@code dev} profile of {@code make up}, as {@code DemoDocuments}. Production
 * has neither; the integration tests turn the placeholder off ({@code DemoTreasuryTests} calls {@link #seed}).
 * <p>
 * <b>What</b>, under {@code TenantContext.runAs} for the demo cooperative, in one transaction, through the module's own
 * services (so the guards, the sealing and the audit are the real ones), in the name of the demo chairman (the first
 * active holder of the chairman's base role). With {@code M} the current month (Kyiv), {@code P} the month before it
 * and every day capped at today:
 * <ul>
 *   <li>opening balances of the cash ({@value #CASH_OPENING}) and the bank ({@value #BANK_OPENING}) on the 1st of the
 *       demo ledger's closed (first) month, or of {@code P} when that is earlier; the cash is large enough that no
 *       entry below is refused by the no-negative-cash rule on any day (the demo payments only add to it);</li>
 *   <li>a handover in {@code P} (the 25th) and one in {@code M} (the 4th);</li>
 *   <li>expenses in four categories in {@code M}: electricity from the bank, materials from the cash with a bundled
 *       receipt photo ({@value #RECEIPT}, a small JPEG without metadata), waste removal and office supplies from the
 *       cash;</li>
 *   <li>a cash count now with a shortage of {@value #SHORTAGE} and its explanation, booked by the chairman;</li>
 *   <li>{@code P}'s bank statement equal to the books, marked «Звірено»; {@code M}'s statement with a bank fee of
 *       {@value #UNENTERED_FEE} nobody entered, so its «out» and closing differ and the month waits for its
 *       reconciliation.</li>
 * </ul>
 * Skipped when the cash account already has an opening balance (a second run writes nothing) or the cooperative has no
 * chairman. A refusal or any other failure rolls everything back, is logged with its problem code or class only and
 * never stops the application. The log has counts only. Runs after {@code DemoDocuments} ({@link Order}).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnExpression("'${spring.flyway.locations:}'.contains('db/demo') "
        + "and '${spring.flyway.placeholders.demoledger:off}' == 'on'")
class DemoTreasury implements ApplicationRunner {

    /** The demo cooperative of {@code R__demo_data.sql}. */
    static final UUID DEMO_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    static final String CASH_OPENING = "9000.00";
    static final String BANK_OPENING = "48200.00";
    static final String SHORTAGE = "20.00";
    static final String UNENTERED_FEE = "35.00";

    /** The bundled receipt photo (classpath). */
    static final String RECEIPT = "demo/treasury-receipt.jpg";

    private static final Logger log = LoggerFactory.getLogger(DemoTreasury.class);

    /**
     * What one run entered: the opening date (null when skipped), the expenses, the handovers and the bank statements
     * (0 when skipped).
     */
    record Seeded(@Nullable LocalDate openingDate, int expenses, int handovers, int statements) {

        static final Seeded SKIPPED = new Seeded(null, 0, 0, 0);
    }

    private final TransactionTemplate tx;
    private final MoneyAccounts accounts;
    private final Expenses expenses;
    private final Handovers handovers;
    private final Categories categories;
    private final CashCounts cashCounts;
    private final BankReconciliations reconciliations;
    private final LedgerReadModel ledger;
    private final RoleHolders roleHolders;
    private final Clock clock;

    DemoTreasury(PlatformTransactionManager transactionManager, MoneyAccounts accounts, Expenses expenses,
            Handovers handovers, Categories categories, CashCounts cashCounts, BankReconciliations reconciliations,
            LedgerReadModel ledger, RoleHolders roleHolders, Clock clock) {
        this.tx = new TransactionTemplate(transactionManager);
        this.accounts = accounts;
        this.expenses = expenses;
        this.handovers = handovers;
        this.categories = categories;
        this.cashCounts = cashCounts;
        this.reconciliations = reconciliations;
        this.ledger = ledger;
        this.roleHolders = roleHolders;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Seeded seeded = seed();
            if (seeded.openingDate() == null) {
                log.info("Demo cash book: already entered (or no chairman), nothing written");
                return;
            }
            log.info("Demo cash book: opening {}, {} expenses, {} handovers, {} bank statements entered",
                    seeded.openingDate(), seeded.expenses(), seeded.handovers(), seeded.statements());
        } catch (ApiProblemException e) {
            log.warn("Demo cash book not entered: {}", e.code());
        } catch (RuntimeException e) {
            log.warn("Demo cash book not entered: {}", e.getClass().getName());
        }
    }

    /** Enters the demo cash book unless it is there (see the class comment). */
    Seeded seed() {
        return TenantContext.runAs(DEMO_TENANT, () -> Objects.requireNonNull(tx.execute(status -> seedDemo())));
    }

    private Seeded seedDemo() {
        LocalDate today = LocalDate.now(clock);
        List<MoneyAccount> both = accounts.list(today);
        MoneyAccount cash = account(both, MoneyAccountKind.CASH);
        MoneyAccount bank = account(both, MoneyAccountKind.BANK);
        UUID chairmanId = roleHolders.activeHolders("CHAIRMAN").stream().findFirst().map(MemberRef::id)
                .orElse(null);
        if (cash.getOpening() != null || chairmanId == null) {
            return Seeded.SKIPPED;
        }
        AccessProfile chairman = new AccessProfile(DEMO_TENANT, chairmanId, TreasuryAuditPublisher.SYSTEM, "",
                Set.of(), List.of(), false);
        YearMonth current = YearMonth.from(today);
        YearMonth previous = current.minusMonths(1);
        LocalDate opening = ledger.firstBillingMonth().map(month -> month.atDay(1))
                .filter(day -> day.isBefore(previous.atDay(1))).orElse(previous.atDay(1));

        accounts.setOpening(cash.getId(), CASH_OPENING, opening, chairman);
        accounts.setOpening(bank.getId(), BANK_OPENING, opening, chairman);

        handovers.create(new HandoverInput(previous.atDay(25), "3000.00", "КВ-0101"), chairman);
        handovers.create(new HandoverInput(day(current, 4, today), "2000.00", "КВ-0102"), chairman);

        expense(MoneyAccountKind.BANK, day(current, 3, today), "12480.00", "ELECTRICITY_SUPPLIER",
                "ТОВ «Енергозбут-Демо»", "Електроенергія за попередній місяць", "РФ-0412", null, chairman);
        expense(MoneyAccountKind.CASH, day(current, 5, today), "860.00", "MATERIALS", "Будмаркет «Демо»",
                "Кабель і клеми для ремонту ліхтаря", "ЧК-1187", receipt(), chairman);
        expense(MoneyAccountKind.CASH, day(current, 7, today), "1200.00", "WASTE", "ТОВ «Демо-Екосервіс»",
                "Вивезення сміття з контейнерного майданчика", "АВР-77", null, chairman);
        expense(MoneyAccountKind.CASH, day(current, 8, today), "145.50", "OFFICE_COMMS", "Канцтовари «Демо»",
                "Папір і конверти для квитанцій", null, null, chairman);

        Instant countedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        CashCountInput preview = new CashCountInput(countedAt.atOffset(ZoneOffset.UTC), "0.00");
        BigDecimal book = new BigDecimal(cashCounts.preview(preview).getBookBalance());
        CashCountInput count = new CashCountInput(countedAt.atOffset(ZoneOffset.UTC),
                book.subtract(new BigDecimal(SHORTAGE)).toPlainString());
        count.setExplanation("Не вистачає 20,00 ₴: імовірно, помилка при видачі решти");
        CashCount saved = cashCounts.save(count, chairman);
        cashCounts.bookDifference(Objects.requireNonNull(saved.getId()), "Нестачу визнано за актом перерахунку",
                chairman);

        BankFigures previousBooks = reconciliations.get(previous.toString()).getBooks();
        reconciliations.enterStatement(previous.toString(), new BankStatementInput(previousBooks.getOpening(),
                previousBooks.getIncoming(), previousBooks.getOutgoing(), previousBooks.getClosing()), chairman);
        reconciliations.confirm(previous.toString(), chairman);
        BankFigures currentBooks = reconciliations.get(current.toString()).getBooks();
        BigDecimal fee = new BigDecimal(UNENTERED_FEE);
        reconciliations.enterStatement(current.toString(), new BankStatementInput(currentBooks.getOpening(),
                currentBooks.getIncoming(), new BigDecimal(currentBooks.getOutgoing()).add(fee).toPlainString(),
                new BigDecimal(currentBooks.getClosing()).subtract(fee).toPlainString()), chairman);
        return new Seeded(opening, 4, 2, 2);
    }

    private void expense(MoneyAccountKind account, LocalDate date, String amount, String categoryCode,
            String counterparty, String description, @Nullable String documentNumber, @Nullable MultipartFile file,
            AccessProfile chairman) {
        ExpenseInput input = new ExpenseInput(account, date, amount, category(categoryCode));
        input.setCounterparty(counterparty);
        input.setDescription(description);
        input.setDocumentNumber(documentNumber);
        input.setDocumentDate(documentNumber == null ? null : date);
        Expense created = expenses.create(input, file == null ? List.of() : List.of(file), chairman);
        Objects.requireNonNull(created.getId(), "id");
    }

    /** The seeded expense category of the code; the first active one when the chairman archived it. */
    private UUID category(String systemCode) {
        List<ExpenseCategory> active = categories.list(ExpenseCategoryKind.EXPENSE, false);
        return active.stream().filter(category -> systemCode.equals(category.getSystemCode())).findFirst()
                .or(() -> active.stream().findFirst()).map(ExpenseCategory::getId)
                .orElseThrow(() -> new IllegalStateException("The cooperative has no active expense category"));
    }

    private static MoneyAccount account(List<MoneyAccount> accounts, MoneyAccountKind kind) {
        return accounts.stream().filter(account -> account.getKind() == kind).findFirst()
                .orElseThrow(() -> new IllegalStateException("The cooperative has no " + kind + " account"));
    }

    /** The day of the month, never later than today. */
    private static LocalDate day(YearMonth month, int dayOfMonth, LocalDate today) {
        LocalDate day = month.atDay(dayOfMonth);
        return day.isAfter(today) ? today : day;
    }

    private static MultipartFile receipt() {
        try (InputStream in = DemoTreasury.class.getClassLoader().getResourceAsStream(RECEIPT)) {
            if (in == null) {
                throw new IllegalStateException("The demo receipt is missing");
            }
            return new BundledFile("chek-budmarket.jpg", in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("The demo receipt cannot be read", e);
        }
    }

    /** A bundled file handed to the expense service as an upload. */
    private record BundledFile(String fileName, byte[] content) implements MultipartFile {

        @Override
        public String getName() {
            return "files";
        }

        @Override
        public String getOriginalFilename() {
            return fileName;
        }

        @Override
        public String getContentType() {
            return "image/jpeg";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) {
            throw new UnsupportedOperationException("A bundled file is never stored on disk");
        }
    }
}
