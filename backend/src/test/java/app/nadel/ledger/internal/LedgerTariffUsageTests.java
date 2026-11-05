package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The ledger's answer to the tariffs port {@code TariffUsage} without a database (feature 020): a tariff is charged
 * when a charge names it or a charge part is priced by one of its versions, asked in two batched questions, the second
 * only for the versions of the tariffs the first did not find; the last charged day is the later of the two answers.
 */
class LedgerTariffUsageTests {

    private static final UUID TENANT = UUID.randomUUID();

    private final ChargeRepository charges = mock(ChargeRepository.class);
    private final ChargePartRepository parts = mock(ChargePartRepository.class);
    private final LedgerTariffUsage usage = new LedgerTariffUsage(charges, parts);

    @Test
    void nothingAskedMeansNoQuery() {
        assertThat(usage.everCharged(TENANT, Map.of())).isEmpty();
        verifyNoInteractions(charges, parts);
    }

    @Test
    void aTariffNamedByAChargeOrWithAPricedVersionIsCharged() {
        UUID named = UUID.randomUUID();
        UUID priced = UUID.randomUUID();
        UUID unused = UUID.randomUUID();
        UUID namedVersion = UUID.randomUUID();
        UUID pricedVersion = UUID.randomUUID();
        UUID softlyDeletedVersion = UUID.randomUUID();
        UUID unusedVersion = UUID.randomUUID();
        when(charges.tariffIdsWithCharges(anyCollection())).thenReturn(List.of(named));
        when(parts.versionIdsWithParts(anyCollection())).thenReturn(List.of(softlyDeletedVersion));

        Set<UUID> charged = usage.everCharged(TENANT, Map.of(
                named, Set.of(namedVersion),
                priced, Set.of(pricedVersion, softlyDeletedVersion),
                unused, Set.of(unusedVersion)));

        assertThat(charged).containsExactlyInAnyOrder(named, priced);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> versionsAsked = ArgumentCaptor.forClass(Collection.class);
        verify(parts).versionIdsWithParts(versionsAsked.capture());
        // the versions of a tariff a charge already names are not asked about again
        assertThat(versionsAsked.getValue()).containsExactlyInAnyOrder(pricedVersion, softlyDeletedVersion,
                unusedVersion);
    }

    @Test
    void whenChargesNameEveryTariffThePartsAreNotAsked() {
        UUID tariff = UUID.randomUUID();
        when(charges.tariffIdsWithCharges(anyCollection())).thenReturn(List.of(tariff));

        assertThat(usage.everCharged(TENANT, Map.of(tariff, Set.of(UUID.randomUUID())))).containsExactly(tariff);
        verify(parts, never()).versionIdsWithParts(anyCollection());
    }

    @Test
    void aTariffNoChargeEverUsedIsNotCharged() {
        UUID tariff = UUID.randomUUID();
        when(charges.tariffIdsWithCharges(anyCollection())).thenReturn(List.of());
        when(parts.versionIdsWithParts(anyCollection())).thenReturn(List.of());

        assertThat(usage.everCharged(TENANT, Map.of(tariff, Set.of(UUID.randomUUID())))).isEmpty();
    }

    @Test
    void theLastChargedDayIsTheLaterOfTheTariffsAndTheVersionsCharges() {
        UUID tariff = UUID.randomUUID();
        Set<UUID> versions = Set.of(UUID.randomUUID());
        when(charges.lastUnreversedServiceDayOfTariff(tariff, ChargeKind.REGULAR))
                .thenReturn(LocalDate.of(2026, 2, 28));
        when(parts.lastUnreversedServiceDayOfVersions(versions, ChargeKind.REGULAR))
                .thenReturn(LocalDate.of(2026, 3, 31));
        assertThat(usage.lastChargedDay(TENANT, tariff, versions)).contains(LocalDate.of(2026, 3, 31));

        when(parts.lastUnreversedServiceDayOfVersions(versions, ChargeKind.REGULAR))
                .thenReturn(LocalDate.of(2026, 1, 31));
        assertThat(usage.lastChargedDay(TENANT, tariff, versions)).contains(LocalDate.of(2026, 2, 28));

        when(charges.lastUnreversedServiceDayOfTariff(tariff, ChargeKind.REGULAR)).thenReturn(null);
        assertThat(usage.lastChargedDay(TENANT, tariff, versions)).contains(LocalDate.of(2026, 1, 31));
    }

    @Test
    void withoutUnreversedChargesThereIsNoLastChargedDay() {
        UUID tariff = UUID.randomUUID();

        assertThat(usage.lastChargedDay(TENANT, tariff, Set.of())).isEmpty();
        verify(charges).lastUnreversedServiceDayOfTariff(eq(tariff), eq(ChargeKind.REGULAR));
        // no versions: the parts are not asked (an empty IN list)
        verify(parts, never()).lastUnreversedServiceDayOfVersions(anyCollection(), eq(ChargeKind.REGULAR));
    }
}
