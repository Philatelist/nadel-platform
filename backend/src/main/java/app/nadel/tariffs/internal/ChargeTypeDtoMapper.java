package app.nadel.tariffs.internal;

import nadel.contract.model.ChargeType;
import nadel.contract.model.ChargeTypeCode;

/** Turns a charge type into API output. */
final class ChargeTypeDtoMapper {

    private ChargeTypeDtoMapper() {
    }

    static ChargeType toDto(ChargeTypeRecord type) {
        var dto = new ChargeType(type.getId(), type.getName(), type.isNameCustomized(), type.isActive(),
                type.getPosition());
        dto.setDescription(type.getDescription());
        if (type.getCode() != null) {
            dto.setCode(ChargeTypeCode.fromValue(type.getCode()));
        }
        return dto;
    }
}
