package app.nadel.meters.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * The photo of a meter reading (table {@code reading_photo}, V38): inserted once with the reading it belongs to (the
 * bytes already checked and stripped of metadata, {@link ReadingPhotoUploads}) and read only by the download. Whether a
 * reading has a photo is asked with an id-only query ({@link ReadingPhotoRepository#readingIdsWithPhoto}), so the lists
 * never load the bytes (up to 5 MiB each). Write-once (no {@code UPDATE}/{@code DELETE} for {@code nadel_app}, a guard
 * trigger, removed only by the cascade of a deleted reading), so {@link Immutable}. {@code size_bytes} is the length of
 * the stored content and {@code sha256} its SHA-256 (the CHECKs of V38).
 */
@Entity
@Immutable
@Table(name = "reading_photo")
class ReadingPhotoRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "reading_id", nullable = false, updatable = false)
    private UUID readingId;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private int sizeBytes;

    @Column(nullable = false, updatable = false)
    private byte[] sha256;

    @Column(nullable = false, updatable = false)
    private byte[] content;

    @Column(name = "created_by", updatable = false)
    private @Nullable UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReadingPhotoRecord() {
    }

    /** The photo of the reading; size and digest are those of the stored bytes. */
    static ReadingPhotoRecord create(UUID tenantId, UUID readingId, ReadingPhotoUploads.Prepared photo,
            @Nullable UUID createdBy, Instant now) {
        ReadingPhotoRecord record = new ReadingPhotoRecord();
        record.id = UUID.randomUUID();
        record.tenantId = tenantId;
        record.readingId = readingId;
        record.contentType = photo.kind().mediaType();
        record.content = photo.content();
        record.sizeBytes = photo.content().length;
        record.sha256 = photo.sha256();
        record.createdBy = createdBy;
        record.createdAt = now;
        return record;
    }

    String contentType() {
        return contentType;
    }

    byte[] content() {
        return content;
    }
}
