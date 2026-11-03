package app.nadel.documents.internal;

import app.nadel.members.MemberRef;
import app.nadel.members.RoleHolders;
import app.nadel.problem.ApiProblemException;
import app.nadel.settings.CooperativeSettings;
import app.nadel.settings.Requisites;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import app.nadel.treasury.CashCountAct;
import app.nadel.treasury.CashCountActs;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The act of a cash count ({@code GET /cash-counts/{id}/act.pdf}, feature 018 §2.6, technical considerations 2.8):
 * reads the count through {@link CashCountActs} (the treasury module's public facade), the cooperative's name and
 * requisites ({@link CooperativeSettings}, {@link TenantDirectory}), and the signers' names ({@link RoleHolders}: the
 * first active holder of the treasurer and of the chairman base role, each in name order; without one the line is
 * left empty), and draws it with {@link CashCountActLayout}. Made on request, never stored, not audited (the 016
 * downloads rule). The caller holds {@code CASHBOOK_VIEW} (checked on {@code DocumentsController}); an unknown count
 * or one of another cooperative is 404 {@code NOT_FOUND}. Names are personal data: never logged.
 */
@Service
class CashCountActDocuments {

    /** An act as a file. */
    record ActFile(String fileName, byte[] content) {
    }

    private final CashCountActs acts;
    private final CooperativeSettings settings;
    private final TenantDirectory tenants;
    private final RoleHolders roleHolders;
    private final Clock clock;

    CashCountActDocuments(CashCountActs acts, CooperativeSettings settings, TenantDirectory tenants,
            RoleHolders roleHolders, Clock clock) {
        this.acts = acts;
        this.settings = settings;
        this.tenants = tenants;
        this.roleHolders = roleHolders;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    ActFile act(UUID countId) {
        CashCountAct act = acts.act(countId).orElseThrow(ApiProblemException::notFound);
        Instant now = clock.instant();
        CashCountActLayout.Content content = new CashCountActLayout.Content(requisites(), act,
                firstHolder("TREASURER"), firstHolder("CHAIRMAN"));
        return new ActFile("cash_count_act_" + act.countedOn() + ".pdf", CashCountActLayout.render(content, now));
    }

    private ReceiptContent.Requisites requisites() {
        Requisites requisites = settings.requisites();
        TenantSummary tenant = tenants.current().orElseThrow(() -> new IllegalStateException("No cooperative"));
        String name = requisites.officialName() == null || requisites.officialName().isBlank() ? tenant.name()
                : requisites.officialName();
        return new ReceiptContent.Requisites(name, tenant.edrpou(), requisites.legalAddress(), requisites.iban(),
                requisites.bankName());
    }

    private @Nullable String firstHolder(String baseRole) {
        List<MemberRef> holders = roleHolders.activeHolders(baseRole);
        return holders.isEmpty() ? null : InvoiceBuilder.fullName(holders.getFirst());
    }
}
