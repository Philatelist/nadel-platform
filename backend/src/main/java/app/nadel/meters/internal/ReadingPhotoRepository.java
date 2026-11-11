package app.nadel.meters.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The photos of readings ({@link ReadingPhotoRecord}): which readings have one (ids only, never the bytes) and the
 * download of one photo. Deliberately no {@code findAll}, update or delete. Under RLS like every query of the
 * application.
 */
interface ReadingPhotoRepository extends Repository<ReadingPhotoRecord, UUID> {

    /** The readings among {@code readingIds} that have a photo. */
    @Query("select p.readingId from ReadingPhotoRecord p where p.readingId in :readingIds")
    List<UUID> readingIdsWithPhoto(@Param("readingIds") Collection<UUID> readingIds);

    /** The photo of the reading with its content; empty when it has none or the reading is not visible. */
    @Query("select p from ReadingPhotoRecord p where p.readingId = :readingId")
    Optional<ReadingPhotoRecord> findByReading(@Param("readingId") UUID readingId);
}
