package app.nadel.meters.internal;

import app.nadel.meters.ConsumptionCalculator;
import app.nadel.meters.ConsumptionCalculator.RangeConsumption;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import nadel.contract.model.MeterConsumption;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The consumption of a meter over a date range, added up over its chain of replacements (spec 010, section 2.5). Only
 * counted readings are used; the calculation is {@link ConsumptionCalculator}, exact until the one display rounding of
 * a calculated value (ADR 0014).
 */
@Service
class ConsumptionQueries {

    private final MeterRepository meters;
    private final MeterChains chains;

    ConsumptionQueries(MeterRepository meters, MeterChains chains) {
        this.meters = meters;
        this.chains = chains;
    }

    /**
     * The consumption from {@code from} to {@code to} of the meter's chain; 400 when {@code to} is before {@code from},
     * 404 for an unknown meter or one of another cooperative (RLS).
     */
    @Transactional(readOnly = true)
    MeterConsumption consumption(UUID meterId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("to", ProblemCodes.INVALID)));
        }
        MeterRecord meter = meters.findById(meterId).orElseThrow(ApiProblemException::notFound);
        List<MeterRecord> chain = chains.chainOf(meter);
        RangeConsumption range = ConsumptionCalculator.rangeConsumption(chains.histories(chain), from, to);
        MeterConsumption dto = new MeterConsumption(meterId, from, to,
                chain.stream().map(MeterRecord::getId).toList(), false, false);
        switch (range) {
            case RangeConsumption.Known known -> {
                dto.setKnown(true);
                dto.setCalculated(known.calculated());
                dto.setConsumption(known.consumption().toMeterValue().toString());
            }
            case RangeConsumption.Unknown unknown -> dto.setLastReadingOn(unknown.lastReadingOn());
        }
        return dto;
    }
}
