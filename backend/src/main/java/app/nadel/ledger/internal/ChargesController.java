package app.nadel.ledger.internal;

import app.nadel.identity.CurrentUser;
import java.time.LocalDate;
import java.util.UUID;
import nadel.contract.api.ChargesApi;
import nadel.contract.model.Charge;
import nadel.contract.model.ChargeReversal;
import nadel.contract.model.OpeningBalanceInput;
import nadel.contract.model.PlotAccount;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code charges}. A reversal needs {@code CHARGES_REVERSE}, an opening balance
 * {@code CHARGES_RUN}; the platform operator has no permissions and gets 403. The account of a plot is open to every
 * member for the plots they own, so its check is in {@link AccountQueries}.
 */
@RestController
@RequestMapping("/api/v1")
class ChargesController implements ChargesApi {

    private final CurrentUser currentUser;
    private final AccountQueries accounts;
    private final Reversals reversals;
    private final OpeningBalances openingBalances;

    ChargesController(CurrentUser currentUser, AccountQueries accounts, Reversals reversals,
            OpeningBalances openingBalances) {
        this.currentUser = currentUser;
        this.accounts = accounts;
        this.reversals = reversals;
        this.openingBalances = openingBalances;
    }

    /** No {@code @PreAuthorize}: {@code CHARGES_VIEW} sees any plot, anyone else the plots they own now. */
    @Override
    public ResponseEntity<PlotAccount> getPlotAccount(UUID id, @Nullable LocalDate from, @Nullable LocalDate to) {
        return ResponseEntity.ok(accounts.account(id, from, to, currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_RUN')")
    public ResponseEntity<Charge> enterOpeningBalance(UUID id, OpeningBalanceInput openingBalanceInput) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(openingBalances.enter(id, openingBalanceInput, currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_REVERSE')")
    public ResponseEntity<Charge> reverseCharge(UUID id, ChargeReversal chargeReversal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reversals.reverse(id, chargeReversal, currentUser.profile()));
    }
}
