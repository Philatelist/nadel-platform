package app.nadel.meters.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import app.nadel.problem.ApiProblemException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The photos of meter readings (feature 021 §2.3): stored with the reading in the reading's transaction, asked for by
 * id only (whether a reading has one) and downloaded. A reading keeps its photo whatever its status; a photo is never
 * changed or deleted (only the cascade of a deleted reading removes it, V38).
 * <p>
 * Who may download it: a caller with {@code READINGS_ENTER} or {@code READINGS_APPROVE}, or a current owner of the plot
 * of the reading's meter. Everyone else, an unknown reading, a reading without a photo and one of another cooperative
 * (RLS) get the same 404 {@code NOT_FOUND}; the platform operator gets 403. The access is decided before the bytes are
 * read.
 */
@Service
class ReadingPhotos {

    private final ReadingPhotoRepository photos;
    private final MeterReadingRepository readings;
    private final MeterRepository meters;
    private final MeterAccess access;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    ReadingPhotos(ReadingPhotoRepository photos, MeterReadingRepository readings, MeterRepository meters,
            MeterAccess access, Clock clock) {
        this.photos = photos;
        this.readings = readings;
        this.meters = meters;
        this.access = access;
        this.clock = clock;
    }

    /** A stored photo to download. */
    record Photo(String contentType, byte[] content) {
    }

    /**
     * Stores the photo of a reading saved in the caller's transaction and flushes, so a violated constraint rolls the
     * reading back with it.
     */
    void save(MeterRecord meter, MeterReadingRecord reading, ReadingPhotoUploads.Prepared photo,
            AccessProfile actor) {
        entityManager.persist(ReadingPhotoRecord.create(meter.getTenantId(), reading.getId(), photo,
                actor.memberId(), clock.instant()));
        entityManager.flush();
    }

    /** Whether the reading has a photo. */
    boolean has(UUID readingId) {
        return !photos.readingIdsWithPhoto(List.of(readingId)).isEmpty();
    }

    /** The readings among {@code readingIds} that have a photo; one query whatever their number. */
    Set<UUID> withPhoto(Collection<UUID> readingIds) {
        return readingIds.isEmpty() ? Set.of() : new HashSet<>(photos.readingIdsWithPhoto(readingIds));
    }

    /** The photo of the reading for the viewer (see the class comment for who may see it). */
    @Transactional(readOnly = true)
    Photo download(UUID readingId, AccessProfile viewer) {
        access.requireCooperativeData(viewer);
        MeterReadingRecord reading = readings.findById(readingId).orElseThrow(ApiProblemException::notFound);
        if (!maySee(viewer, reading)) {
            throw ApiProblemException.notFound();
        }
        ReadingPhotoRecord photo = photos.findByReading(readingId).orElseThrow(ApiProblemException::notFound);
        return new Photo(photo.contentType(), photo.content());
    }

    private boolean maySee(AccessProfile viewer, MeterReadingRecord reading) {
        if (viewer.can(Permission.READINGS_ENTER) || viewer.can(Permission.READINGS_APPROVE)) {
            return true;
        }
        return meters.findById(reading.getMeterId()).map(MeterRecord::getPlotId)
                .filter(plotId -> access.ownsNow(viewer, plotId)).isPresent();
    }
}
