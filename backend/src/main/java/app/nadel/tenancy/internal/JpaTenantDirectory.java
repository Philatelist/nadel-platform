package app.nadel.tenancy.internal;

import app.nadel.tenancy.TenantContext;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JpaTenantDirectory implements TenantDirectory {

    private final TenantRepository tenants;

    JpaTenantDirectory(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantSummary> current() {
        return TenantContext.currentTenantId()
                .flatMap(tenants::findById)
                .map(t -> new TenantSummary(t.getId(), t.getName()));
    }
}
