package app.nadel.tariffs.internal;

import app.nadel.identity.CurrentUser;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import nadel.contract.api.TariffsApi;
import nadel.contract.model.PlotTariffs;
import nadel.contract.model.Tariff;
import nadel.contract.model.TariffCreate;
import nadel.contract.model.TariffScopeUpdate;
import nadel.contract.model.TariffSummary;
import nadel.contract.model.TariffVersion;
import nadel.contract.model.TariffVersionCreate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tariff endpoints of tag {@code tariffs}. Reading needs {@code TARIFFS_VIEW}; every write needs
 * {@code TARIFFS_VIEW} and {@code TARIFFS_MANAGE}, so a manager can always see what they change. The platform operator
 * has no permissions and gets 403. {@code GET /plots/{id}/tariffs} is the exception: it is open to every member for the
 * plots they own.
 */
@RestController
@RequestMapping("/api/v1")
class TariffsController implements TariffsApi {

    private final CurrentUser currentUser;
    private final TariffQueries queries;
    private final TariffCommands commands;
    private final ExpectedAmountQueries expectedAmounts;

    TariffsController(CurrentUser currentUser, TariffQueries queries, TariffCommands commands,
            ExpectedAmountQueries expectedAmounts) {
        this.currentUser = currentUser;
        this.queries = queries;
        this.commands = commands;
        this.expectedAmounts = expectedAmounts;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_TARIFFS_VIEW')")
    public ResponseEntity<List<TariffSummary>> listTariffs() {
        return ResponseEntity.ok(queries.list());
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_TARIFFS_VIEW')")
    public ResponseEntity<Tariff> getTariff(UUID id) {
        return ResponseEntity.ok(queries.get(id));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_TARIFFS_VIEW') and hasAuthority('PERM_TARIFFS_MANAGE')")
    public ResponseEntity<Tariff> createTariff(TariffCreate tariffCreate) {
        Tariff created = commands.create(tariffCreate, currentUser.profile());
        return ResponseEntity.created(URI.create("/api/v1/tariffs/" + created.getId())).body(created);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_TARIFFS_VIEW') and hasAuthority('PERM_TARIFFS_MANAGE')")
    public ResponseEntity<TariffVersion> addTariffVersion(UUID id, TariffVersionCreate tariffVersionCreate) {
        TariffVersion created = commands.addVersion(id, tariffVersionCreate, currentUser.profile());
        return ResponseEntity.created(URI.create("/api/v1/tariffs/" + id)).body(created);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_TARIFFS_VIEW') and hasAuthority('PERM_TARIFFS_MANAGE')")
    public ResponseEntity<Tariff> updateTariffScope(UUID id, TariffScopeUpdate tariffScopeUpdate) {
        return ResponseEntity.ok(commands.updateScope(id, tariffScopeUpdate, currentUser.profile()));
    }

    /**
     * No {@code @PreAuthorize}: a member may ask for the plots they own, so the check is in
     * {@link ExpectedAmountQueries} (anyone else gets 404, the platform operator 403).
     */
    @Override
    public ResponseEntity<PlotTariffs> getPlotTariffs(UUID id, String month) {
        return ResponseEntity.ok(expectedAmounts.forPlot(id, month, currentUser.profile()));
    }
}
