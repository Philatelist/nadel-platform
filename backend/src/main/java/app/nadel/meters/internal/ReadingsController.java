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
import nadel.contract.model.ReadingCorrection;
import nadel.contract.model.ReadingRejection;
import nadel.contract.model.ReadingSaved;
import nadel.contract.model.ReadingStatus;
import nadel.contract.model.ReadingSubmission;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code readings}. The round, entry, correction and deletion need {@code READINGS_ENTER}; the
 * approvals list, approval and rejection need {@code READINGS_APPROVE}; the platform operator has no permissions and
 * gets 403. {@code GET /meters/{id}/readings} and {@code POST /plots/{id}/readings} are open to every member for the
 * meters of the plots they own, so their checks are in {@link ReadingQueries} and {@link ReadingCommands}.
 */
@RestController
@RequestMapping("/api/v1")
class ReadingsController implements ReadingsApi {

    private final CurrentUser currentUser;
    private final ReadingCommands commands;
    private final ReadingQueries queries;
    private final RoundQueries round;
    private final ApprovalQueries approvals;

    ReadingsController(CurrentUser currentUser, ReadingCommands commands, ReadingQueries queries, RoundQueries round,
            ApprovalQueries approvals) {
        this.currentUser = currentUser;
        this.commands = commands;
        this.queries = queries;
        this.round = round;
        this.approvals = approvals;
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
     * No {@code @PreAuthorize}: a current owner of the plot submits a reading of its meter, whatever their permissions
     * (see {@link ReadingCommands#submit}); anyone else gets 404, the platform operator 403.
     */
    @Override
    public ResponseEntity<ReadingSaved> submitPlotReading(UUID id, ReadingSubmission readingSubmission) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commands.submit(id, readingSubmission, currentUser.profile()));
    }

    /** No {@code @PreAuthorize}: a member may ask for the meters of the plots they own (see {@link ReadingQueries}). */
    @Override
    public ResponseEntity<MeterReadingHistory> listMeterReadings(UUID id) {
        return ResponseEntity.ok(queries.history(id, currentUser.profile()));
    }
}
