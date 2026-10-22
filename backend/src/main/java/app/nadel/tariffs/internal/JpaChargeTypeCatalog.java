package app.nadel.tariffs.internal;

import app.nadel.tariffs.ChargeTypeCatalog;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@link ChargeTypeCatalog} over the charge type repository; RLS limits it to the caller's cooperative. */
@Component
class JpaChargeTypeCatalog implements ChargeTypeCatalog {

    private final ChargeTypeRepository types;

    JpaChargeTypeCatalog(ChargeTypeRepository types) {
        this.types = types;
    }

    @Override
    public Optional<ChargeTypeFacts> find(UUID id) {
        return types.findById(id).map(JpaChargeTypeCatalog::facts);
    }

    @Override
    public Optional<ChargeTypeFacts> findAndLockShared(UUID id) {
        return types.findByIdForShare(id).map(JpaChargeTypeCatalog::facts);
    }

    @Override
    public List<ChargeTypeFacts> all() {
        return types.findAll().stream().map(JpaChargeTypeCatalog::facts)
                .sorted(Comparator.comparingInt(ChargeTypeFacts::position).thenComparing(ChargeTypeFacts::id))
                .toList();
    }

    private static ChargeTypeFacts facts(ChargeTypeRecord type) {
        return new ChargeTypeFacts(type.getId(), type.getCode(), type.getName(), type.isNameCustomized(),
                type.isActive(), type.getArchivedAt() != null, type.getPosition());
    }
}
