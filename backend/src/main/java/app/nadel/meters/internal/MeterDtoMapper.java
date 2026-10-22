package app.nadel.meters.internal;

import app.nadel.meters.ConsumptionCalculator.Reading;
import app.nadel.meters.internal.MeterChains.Neighbours;
import app.nadel.plots.PlotCatalog.PlotFacts;
import nadel.contract.model.Meter;
import nadel.contract.model.MeterKind;
import nadel.contract.model.MeterPlot;
import nadel.contract.model.MeterPoint;
import nadel.contract.model.MeterPredecessor;
import nadel.contract.model.MeterResource;
import nadel.contract.model.MeterSuccessor;
import org.jspecify.annotations.Nullable;

/** Maps a meter to the API model. The one place that decides what a viewer sees of a meter. */
final class MeterDtoMapper {

    private MeterDtoMapper() {
    }

    /**
     * @param plot        the facts of the meter's plot; null for a meter that is not a plot meter
     * @param latest      the latest approved reading of the meter, if any
     * @param hasReadings whether the meter has a reading of any status (then it can only be removed)
     * @param withNote    whether the viewer may see the board's note ({@code METERS_VIEW})
     * @param neighbours  the meter it replaced and the meter that replaced it, from
     *                    {@link MeterChains#neighboursOf(java.util.Collection)} (same plot only)
     */
    static Meter toDto(MeterRecord meter, @Nullable PlotFacts plot, @Nullable Reading latest, boolean hasReadings,
            boolean withNote, Neighbours neighbours) {
        Meter dto = new Meter(meter.getId(), MeterKind.fromValue(meter.getKind().name()),
                MeterResource.fromValue(meter.getResource().name()), meter.getSerial(), meter.getInstalledOn(),
                meter.getInitialValue().toString(), meter.isActive(), meter.isActive() && !hasReadings,
                lastPoint(meter, latest));
        dto.setName(meter.getName());
        if (plot != null) {
            MeterPlot ref = new MeterPlot(plot.id(), plot.number());
            ref.setStreet(plot.street());
            dto.setPlot(ref);
        }
        if (meter.getRemovedOn() != null && meter.getFinalValue() != null) {
            dto.setRemovedOn(meter.getRemovedOn());
            dto.setFinalReading(meter.getFinalValue().toString());
        }
        if (withNote) {
            dto.setNote(meter.getNote());
        }
        dto.setReplacesMeterId(meter.getReplacesMeterId());
        MeterRecord predecessor = neighbours.predecessor();
        if (predecessor != null && predecessor.getRemovedOn() != null && predecessor.getFinalValue() != null) {
            dto.setReplaces(new MeterPredecessor(predecessor.getId(), predecessor.getSerial(),
                    predecessor.getRemovedOn(), predecessor.getFinalValue().toString()));
        }
        MeterRecord successor = neighbours.successor();
        if (successor != null) {
            dto.setReplacedBy(new MeterSuccessor(successor.getId(), successor.getSerial()));
        }
        return dto;
    }

    /**
     * The latest point of the meter: the final reading of a removed meter, else the latest approved reading, else the
     * initial reading.
     */
    static Reading lastReading(MeterRecord meter, @Nullable Reading latest) {
        if (meter.getRemovedOn() != null && meter.getFinalValue() != null) {
            return new Reading(meter.getRemovedOn(), meter.getFinalValue());
        }
        return latest != null ? latest : new Reading(meter.getInstalledOn(), meter.getInitialValue());
    }

    private static MeterPoint lastPoint(MeterRecord meter, @Nullable Reading latest) {
        Reading last = lastReading(meter, latest);
        return new MeterPoint(last.takenOn(), last.value().toString());
    }
}
