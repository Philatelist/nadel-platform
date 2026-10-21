package app.nadel.tariffs.internal;

import java.util.List;
import nadel.contract.model.ChargeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads charge types. The cooperative is the one of the transaction (RLS); nothing is taken from the request. */
@Service
class ChargeTypeQueries {

    private final ChargeTypeRepository types;

    ChargeTypeQueries(ChargeTypeRepository types) {
        this.types = types;
    }

    /** The non-archived charge types ordered by position, then id. */
    @Transactional(readOnly = true)
    List<ChargeType> list() {
        return types.findActiveInOrder().stream().map(ChargeTypeDtoMapper::toDto).toList();
    }
}
