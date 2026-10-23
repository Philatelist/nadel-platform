package app.nadel.tariffs.internal;

import app.nadel.tariffs.ChargeTypeCatalog;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** {@link ChargeTypeCatalog} over the charge type repository; RLS limits it to the caller's cooperative. */
@Component
class JpaChargeTypeCatalog implements ChargeTypeCatalog {

    private static final Pattern SPACES = Pattern.compile("\\s+");

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

    @Override
    public Map<String, List<ChargeTypeFacts>> findByNames(Collection<String> names) {
        Map<String, List<ChargeTypeFacts>> byName = new HashMap<>();
        for (ChargeTypeFacts type : all()) {
            Set<String> own = new LinkedHashSet<>();
            own.add(normalise(type.name()));
            if (type.code() != null && !type.nameCustomized()) {
                DefaultChargeTypeNames.of(type.code()).forEach(name -> own.add(normalise(name)));
            }
            own.forEach(name -> byName.computeIfAbsent(name, key -> new ArrayList<>()).add(type));
        }
        Map<String, List<ChargeTypeFacts>> result = new LinkedHashMap<>();
        for (String name : names) {
            result.put(name, List.copyOf(byName.getOrDefault(normalise(name), List.of())));
        }
        return result;
    }

    /** Lower case, surrounding spaces dropped, runs of spaces collapsed. */
    private static String normalise(String name) {
        return SPACES.matcher(name.strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }

    private static ChargeTypeFacts facts(ChargeTypeRecord type) {
        return new ChargeTypeFacts(type.getId(), type.getCode(), type.getName(), type.isNameCustomized(),
                type.isActive(), type.getArchivedAt() != null, type.getPosition());
    }
}
