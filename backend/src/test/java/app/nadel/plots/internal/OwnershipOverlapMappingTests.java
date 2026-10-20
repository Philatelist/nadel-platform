package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.nadel.identity.AccessProfile;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.OwnershipChange;
import nadel.contract.model.OwnershipChangeOwner;
import nadel.contract.model.OwnershipCorrection;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/**
 * The exclusion constraint {@code ownership_no_overlap_ex} (SQLSTATE 23P01) is the backstop of the in-memory timeline
 * check, which already catches every overlap a request can produce, so the database cannot be made to fail through the
 * API. These tests make the repository fail the way the database does and check the translation to 409
 * {@code OWNERSHIP_OVERLAP}, for the change and for the correction, and that any other violation is not swallowed.
 */
class OwnershipOverlapMappingTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PLOT_ID = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final MemberRef MEMBER_REF = new MemberRef(MEMBER, "Last", "First", null, false);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T09:00:00Z"), ZoneOffset.UTC);

    private final PlotRepository plots = mock(PlotRepository.class);
    private final OwnershipRepository ownerships = mock(OwnershipRepository.class);
    private final MemberDirectory members = mock(MemberDirectory.class);
    private final AccessProfile actor = new AccessProfile(TENANT, UUID.randomUUID(), "sub", "Actor", Set.of(),
            List.of(), false);

    private OwnershipCommands commands;
    private Ownership current;

    @BeforeEach
    void fixture() {
        commands = new OwnershipCommands(plots, mock(PlotStatusRepository.class), ownerships,
                mock(OwnershipQueries.class), members, mock(ApplicationEventPublisher.class),
                new OwnershipRecorder(mock(ApplicationEventPublisher.class)), CLOCK);
        Plot plot = Plot.create(TENANT, new PlotData("T-1", null, PlotKind.PLOT, new BigDecimal("600.00"), null, false,
                null), CLOCK.instant());
        when(plots.findByIdForUpdate(PLOT_ID)).thenReturn(Optional.of(plot));
        current = Ownership.start(TENANT, PLOT_ID, MEMBER, Share.parse("1/1"), LocalDate.parse("2020-01-01"), null,
                CLOCK.instant());
        when(ownerships.findAllByPlotId(PLOT_ID)).thenReturn(List.of(current));
        when(ownerships.findPlotIdById(current.getId())).thenReturn(Optional.of(PLOT_ID));
        when(members.lockActive(any())).thenReturn(Map.of(MEMBER, MEMBER_REF));
        when(members.find(any())).thenReturn(Map.of(MEMBER, MEMBER_REF));
    }

    @Test
    void anOverlapRaisedByTheDatabaseDuringAChangeIsAnOwnershipOverlapConflict() {
        doThrow(violation("ownership_no_overlap_ex")).when(ownerships).saveAllAndFlush(any());

        assertThatThrownBy(() -> commands.change(PLOT_ID, change(), actor))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                    assertThat(problem.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(problem.code()).isEqualTo(ProblemCodes.OWNERSHIP_OVERLAP);
                    assertThat(problem.errors()).singleElement().satisfies(error ->
                            assertThat(error.field()).isEqualTo("owners"));
                });
    }

    @Test
    void anOverlapRaisedByTheDatabaseDuringACorrectionIsAnOwnershipOverlapConflictOnTheStartDate() {
        doThrow(violation("ownership_no_overlap_ex")).when(ownerships).saveAllAndFlush(any());

        assertThatThrownBy(() -> commands.correct(current.getId(), correction(), actor))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                    assertThat(problem.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(problem.code()).isEqualTo(ProblemCodes.OWNERSHIP_OVERLAP);
                    assertThat(problem.errors()).singleElement().satisfies(error ->
                            assertThat(error.field()).isEqualTo("validFrom"));
                });
    }

    @Test
    void theConstraintNameInTheMessageOfAWrappedErrorIsRecognisedToo() {
        SQLException sql = new SQLException("conflicting key value violates exclusion constraint "
                + "\"ownership_no_overlap_ex\"", "23P01");
        doThrow(new DataIntegrityViolationException("could not execute statement", sql))
                .when(ownerships).saveAllAndFlush(any());

        assertThatThrownBy(() -> commands.change(PLOT_ID, change(), actor))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo(ProblemCodes.OWNERSHIP_OVERLAP));
    }

    @Test
    void anotherIntegrityViolationIsNotMistakenForAnOverlap() {
        DataIntegrityViolationException other = violation("ownership_plot_fk");
        doThrow(other).when(ownerships).saveAllAndFlush(any());

        assertThatThrownBy(() -> commands.change(PLOT_ID, change(), actor)).isSameAs(other);
        assertThatThrownBy(() -> commands.correct(current.getId(), correction(), actor)).isSameAs(other);
    }

    private static DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("violates " + constraint, new SQLException("x", "23P01"), constraint));
    }

    private static OwnershipChange change() {
        return new OwnershipChange(LocalDate.parse("2026-03-01"),
                List.of(new OwnershipChangeOwner(MEMBER).share("1/1")));
    }

    /** Moves the start of the only record to 2021: a real change, so the rows are written. */
    private static OwnershipCorrection correction() {
        return new OwnershipCorrection(MEMBER, "1/1", LocalDate.parse("2021-01-01"), "typo in the date");
    }
}
