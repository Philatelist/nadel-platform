package app.nadel.meters.internal;

import app.nadel.identity.CurrentUser;
import app.nadel.meters.MeterResource;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import nadel.contract.api.ReadingsApi;
import nadel.contract.model.Meter;
import nadel.contract.model.MeterReading;
import nadel.contract.model.MeterReadingHistory;
import nadel.contract.model.PendingReading;
import nadel.contract.model.ReadingBatch;
import nadel.contract.model.ReadingBatchResult;
import nadel.contract.model.ReadingCheck;
import nadel.contract.model.ReadingCorrection;
import nadel.contract.model.ReadingEntry;
import nadel.contract.model.ReadingRejection;
import nadel.contract.model.ReadingSaved;
import nadel.contract.model.ReadingStatus;
import nadel.contract.model.ReadingSubmission;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The endpoints of tag {@code readings}. The round, entry (also one reading with a photo), correction and deletion
 * need {@code READINGS_ENTER}; the approvals list, approval and rejection need {@code READINGS_APPROVE}; the platform
 * operator has no permissions and gets 403. {@code GET /meters/{id}/readings}, {@code POST /plots/{id}/readings} (deprecated),
 * {@code POST /plots/{id}/reading-submissions} and {@code POST /plots/{id}/reading-submissions/check} are open to every member for the meters of the plots they own, so their
 * checks are in {@link ReadingQueries} and {@link ReadingCommands}; {@code GET /readings/{id}/photo} is open to the
 * owners of the plot and to the holders of {@code READINGS_ENTER} or {@code READINGS_APPROVE} ({@link ReadingPhotos}).
 */
@RestController
@RequestMapping("/api/v1")
class ReadingsController implements ReadingsApi {

    private final CurrentUser currentUser;
    private final ReadingCommands commands;
    private final ReadingQueries queries;
    private final RoundQueries round;
    private final ApprovalQueries approvals;
    private final ReadingPhotos photos;

    ReadingsController(CurrentUser currentUser, ReadingCommands commands, ReadingQueries queries, RoundQueries round,
            ApprovalQueries approvals, ReadingPhotos photos) {
        this.currentUser = currentUser;
        this.commands = commands;
        this.queries = queries;
        this.round = round;
        this.approvals = approvals;
        this.photos = photos;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_READINGS_ENTER')")
    public ResponseEntity<List<Meter>> getReadingRound(nadel.contract.model.@Nullable MeterResource resource,
            @Nullable String street, @Nullable LocalDate noReadingSince) {
        return ResponseEntity.ok(round.round(resource == null ? null : MeterResource.valueOf(resource.getValue()),
                street, noReadingSince, currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_READINGS_ENTER')")
    public ResponseEntity<ReadingBatchResult> enterReadings(ReadingBatch readingBatch) {
        return ResponseEntity.ok(commands.enter(readingBatch, currentUser.profile()));
    }

    /** One reading with a photo of the meter, saved together (see {@link ReadingCommands#enterWithPhoto}). */
    @Override
    @PreAuthorize("hasAuthority('PERM_READINGS_ENTER')")
    public ResponseEntity<ReadingSaved> enterReadingWithPhoto(ReadingEntry reading, MultipartFile photo) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commands.enterWithPhoto(reading, photo, currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_READINGS_ENTER')")
    public ResponseEntity<ReadingSaved> correctReading(UUID id, ReadingCorrection readingCorrection) {
        return ResponseEntity.ok(commands.correct(id, readingCorrection, currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_READINGS_ENTER')")
    public ResponseEntity<Void> deleteReading(UUID id) {
        commands.delete(id, currentUser.profile());
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_READINGS_APPROVE')")
    public ResponseEntity<List<PendingReading>> listReadings(ReadingStatus status) {
        return ResponseEntity.ok(approvals.list(status, currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_READINGS_APPROVE')")
    public ResponseEntity<ReadingSaved> approveReading(UUID id) {
        return ResponseEntity.ok(commands.approve(id, currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_READINGS_APPROVE')")
    public ResponseEntity<MeterReading> rejectReading(UUID id, ReadingRejection readingRejection) {
        return ResponseEntity.ok(commands.reject(id, readingRejection, currentUser.profile()));
    }

    /**
     * Deprecated in the contract, still served for installed apps that have not updated: the submission of
     * {@link #submitPlotReadingWithPhoto} without a photo. No {@code @PreAuthorize}: a current owner of the plot
     * submits a reading of its meter, whatever their permissions (see {@link ReadingCommands#submit}); anyone else gets
     * 404, the platform operator 403.
     */
    @Override
    @SuppressWarnings("deprecation")
    public ResponseEntity<ReadingSaved> submitPlotReading(UUID id, ReadingSubmission readingSubmission) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commands.submit(id, readingSubmission, null, currentUser.profile()));
    }

    /**
     * No {@code @PreAuthorize}: a current owner of the plot submits a reading of its meter with an optional photo,
     * whatever their permissions (see {@link ReadingCommands#submit}); anyone else gets 404, the platform operator 403.
     */
    @Override
    public ResponseEntity<ReadingSaved> submitPlotReadingWithPhoto(UUID id, ReadingSubmission reading,
            @Nullable MultipartFile photo) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commands.submit(id, reading, photo, currentUser.profile()));
    }

    /**
     * No {@code @PreAuthorize}: a current owner of the plot checks a reading of its meter before submitting it, with the
     * submission's access (see {@link ReadingCommands#check}); nothing is saved.
     */
    @Override
    public ResponseEntity<ReadingCheck> checkPlotReading(UUID id, ReadingSubmission readingSubmission) {
        return ResponseEntity.ok(commands.check(id, readingSubmission, currentUser.profile()));
    }

    /**
     * No {@code @PreAuthorize}: an owner of the plot may open the photo too (see {@link ReadingPhotos#download}). The
     * photo is shown in the page ({@code inline}) with its stored type, {@code nosniff} (a browser never runs it as
     * something else) and {@code no-store} (it is not cached).
     */
    @Override
    public ResponseEntity<Resource> getReadingPhoto(UUID id) {
        ReadingPhotos.Photo photo = photos.download(id, currentUser.profile());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .contentLength(photo.content().length)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
                .body(new ByteArrayResource(photo.content()));
    }

    /** No {@code @PreAuthorize}: a member may ask for the meters of the plots they own (see {@link ReadingQueries}). */
    @Override
    public ResponseEntity<MeterReadingHistory> listMeterReadings(UUID id) {
        return ResponseEntity.ok(queries.history(id, currentUser.profile()));
    }
}
