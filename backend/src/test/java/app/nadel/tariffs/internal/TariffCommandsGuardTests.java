package app.nadel.tariffs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.nadel.identity.AccessProfile;
import app.nadel.plots.PlotCatalog;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.Basis;
import nadel.contract.model.Frequency;
import nadel.contract.model.TariffCreate;
import nadel.contract.model.TariffVersionCreate;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The last guards of the tariff commands (features 012 and 020) without a database, for what the HTTP tests cannot
 * reach: a charge that lands between the use check and the delete (the foreign key refuses the delete), an integrity
 * failure that is not one the commands translate, and a rate the contract's pattern would have stopped at the
 * boundary. Nothing is published to the audit log when a command is refused.
 */
class TariffCommandsGuardTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final AccessProfile CHAIRMAN = new AccessProfile(TENANT, UUID.randomUUID(), "chairman-subject",
            "Голова", Set.of(), List.of(), false);

    private final TariffRepository tariffs = mock(TariffRepository.class);
    private final TariffVersionRepository versions = mock(TariffVersionRepository.class);
    private final TariffScopePlotRepository scopePlots = mock(TariffScopePlotRepository.class);
    private final ChargeTypeRepository chargeTypes = mock(ChargeTypeRepository.class);
    private final TariffAuditPublisher audit = mock(TariffAuditPublisher.class);
    private final TariffUsages tariffUsages = mock(TariffUsages.class);
    private final TariffCommands commands = new TariffCommands(tariffs, versions, scopePlots, chargeTypes,
            mock(PlotCatalog.class), mock(TariffAssembler.class), audit, mock(EntityManager.class),
            Clock.systemUTC(), List.of(), tariffUsages);

    private TariffRecord storedTariff() {
        TariffRecord tariff = TariffRecord.create(TENANT, UUID.randomUUID(), "Членський внесок", Instant.now());
        when(tariffs.findByIdForUpdate(tariff.getId())).thenReturn(Optional.of(tariff));
        when(tariffUsages.everCharged(TENANT, tariff.getId())).thenReturn(false);
        return tariff;
    }

    private static TariffVersionCreate version(String rate) {
        return new TariffVersionCreate(LocalDate.of(2026, 1, 1), Basis.PER_PLOT, Frequency.YEARLY, rate);
    }

    @Test
    void aChargeMadeBetweenTheUseCheckAndTheDeleteStillRefusesTheDelete() {
        TariffRecord tariff = storedTariff();
        doThrow(new DataIntegrityViolationException("delete failed",
                new SQLException("violates foreign key constraint", "23503"))).when(tariffs).flush();

        assertThatThrownBy(() -> commands.deleteTariff(tariff.getId(), CHAIRMAN))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("TARIFF_IN_USE");
                });
        verifyNoInteractions(audit);
    }

    @Test
    void anotherIntegrityFailureOfTheDeleteIsNotTakenForAUse() {
        TariffRecord tariff = storedTariff();
        DataIntegrityViolationException failure = new DataIntegrityViolationException("delete failed",
                new SQLException("check constraint", "23514"));
        doThrow(failure).when(tariffs).flush();

        assertThatThrownBy(() -> commands.deleteTariff(tariff.getId(), CHAIRMAN)).isSameAs(failure);
        verifyNoInteractions(audit);
    }

    @Test
    void aRateWithMoreThanFourDecimalsIsRefusedEvenPastTheBoundary() {
        TariffCreate input = new TariffCreate(UUID.randomUUID(), "Електроенергія", version("1.23456"));

        assertThatThrownBy(() -> commands.create(input, CHAIRMAN))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(e.errors()).containsExactly(new FieldProblem("firstVersion.rate", "RATE_INVALID"));
                });
        verifyNoInteractions(tariffs, versions, chargeTypes, audit);
    }

    @Test
    void anIntegrityFailureOfANewVersionThatIsNoDateClashIsNotAConflict() {
        ChargeTypeRecord chargeType = mock(ChargeTypeRecord.class);
        UUID chargeTypeId = UUID.randomUUID();
        when(chargeType.getId()).thenReturn(chargeTypeId);
        when(chargeType.getTenantId()).thenReturn(TENANT);
        when(chargeTypes.findByIdForShare(chargeTypeId)).thenReturn(Optional.of(chargeType));
        when(tariffs.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        DataIntegrityViolationException failure = new DataIntegrityViolationException("insert failed",
                new SQLException("violates check constraint \"tariff_version_rate_check\"", "23514"));
        when(versions.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> commands.create(new TariffCreate(chargeTypeId, "Вода", version("12.50")), CHAIRMAN))
                .isSameAs(failure);
        verifyNoInteractions(audit);
    }
}
