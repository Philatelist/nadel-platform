package app.nadel.meters.internal;

import app.nadel.identity.CurrentUser;
import app.nadel.meters.MeterResource;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import nadel.contract.api.ReadingsApi;
import nadel.contract.model.Meter;
import nadel.contract.model.MeterReadingHistory;
import nadel.contract.model.ReadingBatch;
import nadel.contract.model.ReadingBatchResult;
import nadel.contract.model.ReadingCorrection;
import nadel.contract.model.ReadingSaved;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code readings}. The round, entry, correction and deletion need {@code READINGS_ENTER}; the
 * platform operator has no permissions and gets 403. {@code GET /meters/{id}/readings} is open to every member for the
 * meters of the plots they own, so its check is in {@link ReadingQueries}.
 */
@RestController
@RequestMapping("/api/v1")
class ReadingsController implements ReadingsApi {

    private final CurrentUser currentUser;
    private final ReadingCommands commands;
    private final ReadingQueries queries;
    private final RoundQueries round;

    ReadingsController(CurrentUser currentUser, ReadingCommands commands, ReadingQueries queries, RoundQueries round) {
        this.currentUser = currentUser;
        this.commands = commands;
        this.queries = queries;
        this.round = round;
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

    /** No {@code @PreAuthorize}: a member may ask for the meters of the plots they own (see {@link ReadingQueries}). */
    @Override
    public ResponseEntity<MeterReadingHistory> listMeterReadings(UUID id) {
        return ResponseEntity.ok(queries.history(id, currentUser.profile()));
    }
}
