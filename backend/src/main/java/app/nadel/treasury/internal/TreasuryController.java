package app.nadel.treasury.internal;

import app.nadel.identity.CurrentUser;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import nadel.contract.api.TreasuryApi;
import nadel.contract.model.AccountMovementPage;
import nadel.contract.model.MoneyAccount;
import nadel.contract.model.OpeningBalanceRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code treasury} (feature 018, technical considerations 2.10): the accounts and their journals
 * need {@code CASHBOOK_VIEW}, the opening balance {@code CASHBOOK_APPROVE}; a member and the platform operator (who
 * hold neither) are 403. The work is done by {@link MoneyAccounts}.
 */
@RestController
@RequestMapping("/api/v1")
class TreasuryController implements TreasuryApi {

    private final CurrentUser currentUser;
    private final MoneyAccounts accounts;

    TreasuryController(CurrentUser currentUser, MoneyAccounts accounts) {
        this.currentUser = currentUser;
        this.accounts = accounts;
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
}
