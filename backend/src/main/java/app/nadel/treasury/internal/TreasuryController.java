package app.nadel.treasury.internal;

import app.nadel.identity.CurrentUser;
import app.nadel.treasury.MoneyAccountKind;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import nadel.contract.api.TreasuryApi;
import nadel.contract.model.AccountMovementPage;
import nadel.contract.model.EntryReversal;
import nadel.contract.model.Expense;
import nadel.contract.model.ExpenseCategory;
import nadel.contract.model.ExpenseCategoryCreate;
import nadel.contract.model.ExpenseCategoryKind;
import nadel.contract.model.ExpenseCategoryOrder;
import nadel.contract.model.ExpenseCategoryUpdate;
import nadel.contract.model.ExpenseInput;
import nadel.contract.model.ExpensePage;
import nadel.contract.model.MoneyAccount;
import nadel.contract.model.OpeningBalanceRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The endpoints of tag {@code treasury} (feature 018 §2.13, technical considerations 2.10): reads (accounts, journals,
 * expenses and their files, categories) need {@code CASHBOOK_VIEW}; entries (an expense) {@code CASHBOOK_ENTER}; the
 * opening balance, reversals and the categories' changes {@code CASHBOOK_APPROVE}. Permissions, never role names; a
 * member and the platform operator (who hold none of them) are 403. The work is done by {@link MoneyAccounts},
 * {@link Expenses} and {@link Categories}.
 */
@RestController
@RequestMapping("/api/v1")
class TreasuryController implements TreasuryApi {

    private final CurrentUser currentUser;
    private final MoneyAccounts accounts;
    private final Expenses expenses;
    private final Categories categories;

    TreasuryController(CurrentUser currentUser, MoneyAccounts accounts, Expenses expenses, Categories categories) {
        this.currentUser = currentUser;
        this.accounts = accounts;
        this.expenses = expenses;
        this.categories = categories;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_VIEW')")
    public ResponseEntity<List<MoneyAccount>> listMoneyAccounts(@Nullable LocalDate asOf) {
        return ResponseEntity.ok(accounts.list(asOf));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_APPROVE')")
    public ResponseEntity<MoneyAccount> setOpeningBalance(UUID id, OpeningBalanceRequest request) {
        return ResponseEntity.ok(accounts.setOpening(id, request.getAmount(), request.getDate(),
                currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_VIEW')")
    public ResponseEntity<AccountMovementPage> listAccountMovements(UUID id, @Nullable LocalDate from,
            @Nullable LocalDate to, Integer page, Integer size) {
        return ResponseEntity.ok(accounts.movements(id, from, to, page, size));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_ENTER')")
    public ResponseEntity<Expense> createExpense(ExpenseInput expense, @Nullable List<MultipartFile> files) {
        Expense created = expenses.create(expense, files, currentUser.profile());
        return ResponseEntity.created(URI.create("/api/v1/expenses/" + created.getId())).body(created);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_VIEW')")
    public ResponseEntity<ExpensePage> listExpenses(@Nullable LocalDate from, @Nullable LocalDate to,
            nadel.contract.model.@Nullable MoneyAccountKind account, @Nullable UUID categoryId, Integer page,
            Integer size) {
        return ResponseEntity.ok(expenses.list(tenantId(), from, to,
                account == null ? null : MoneyAccountKind.valueOf(account.getValue()), categoryId, page, size));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_VIEW')")
    public ResponseEntity<Expense> getExpense(UUID id) {
        return ResponseEntity.ok(expenses.get(tenantId(), id));
    }

    /**
     * The file as an attachment with its stored type, {@code nosniff} (a browser never runs it as something else) and
     * {@code no-store} (personal data is not cached).
     */
    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_VIEW')")
    public ResponseEntity<Resource> downloadExpenseAttachment(UUID id, Integer n) {
        Expenses.AttachmentFile file = expenses.download(id, n);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.content().length)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(file.fileName()))
                .body(new ByteArrayResource(file.content()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_APPROVE')")
    public ResponseEntity<Expense> reverseExpense(UUID id, EntryReversal request) {
        return ResponseEntity.ok(expenses.reverse(id, request.getReason(), currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_VIEW')")
    public ResponseEntity<List<ExpenseCategory>> listExpenseCategories(@Nullable ExpenseCategoryKind kind,
            Boolean includeArchived) {
        return ResponseEntity.ok(categories.list(kind, Boolean.TRUE.equals(includeArchived)));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_APPROVE')")
    public ResponseEntity<ExpenseCategory> createExpenseCategory(ExpenseCategoryCreate request) {
        ExpenseCategory created = categories.create(request, currentUser.profile());
        return ResponseEntity.created(URI.create("/api/v1/expense-categories/" + created.getId())).body(created);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_APPROVE')")
    public ResponseEntity<Void> reorderExpenseCategories(ExpenseCategoryOrder request) {
        categories.reorder(request, currentUser.profile());
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_APPROVE')")
    public ResponseEntity<ExpenseCategory> updateExpenseCategory(UUID id, ExpenseCategoryUpdate request) {
        return ResponseEntity.ok(categories.update(id, request, currentUser.profile()));
    }

    private UUID tenantId() {
        return Objects.requireNonNull(currentUser.profile().tenantId(), "tenantId");
    }

    /**
     * {@code attachment} with an ASCII {@code filename} (every other character, quotes and backslashes as {@code _})
     * and the exact name as {@code filename*} in UTF-8 (RFC 6266, RFC 8187), so no stored name can break the header.
     */
    static String contentDisposition(String fileName) {
        StringBuilder ascii = new StringBuilder();
        fileName.codePoints().forEach(codePoint -> ascii.append(
                codePoint >= 0x20 && codePoint < 0x7f && codePoint != '"' && codePoint != '\\'
                        ? (char) codePoint : '_'));
        StringBuilder encoded = new StringBuilder();
        for (byte b : fileName.getBytes(StandardCharsets.UTF_8)) {
            int value = b & 0xff;
            if ((value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') || (value >= '0' && value <= '9')
                    || "!#$&+-.^_`|~".indexOf(value) >= 0) {
                encoded.append((char) value);
            } else {
                encoded.append('%').append(Character.toUpperCase(Character.forDigit(value >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(value & 0xf, 16)));
            }
        }
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
