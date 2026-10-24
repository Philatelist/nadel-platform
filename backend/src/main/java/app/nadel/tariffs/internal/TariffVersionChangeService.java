package app.nadel.tariffs.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.problem.ApiProblemException;
import app.nadel.tariffs.TariffVersionChanges;
import app.nadel.tariffs.VersionTerms;
import java.util.UUID;
import nadel.contract.model.TariffVersionCreate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TariffVersionChanges} over {@link TariffCommands}: the same order of checks, locks and writes as the version
 * endpoints, without the use check, a deletion soft. Runs in the caller's transaction (the ledger's recalculation);
 * the audit entry is returned, never published here.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class TariffVersionChangeService implements TariffVersionChanges {

    private final TariffCommands commands;
    private final TariffVersionRepository versions;

    TariffVersionChangeService(TariffCommands commands, TariffVersionRepository versions) {
        this.commands = commands;
        this.versions = versions;
    }

    @Override
    public Change add(UUID tariffId, TariffVersionCreate input, AccessProfile actor) {
        VersionTerms terms = TariffCommands.validTerms(input);
        TariffRecord tariff = commands.lockTariff(tariffId, actor);
        return change(commands.add(tariff, terms, input, actor));
    }

    @Override
    public Change update(UUID versionId, TariffVersionCreate input, AccessProfile actor) {
        VersionTerms terms = TariffCommands.validTerms(input);
        TariffRecord tariff = commands.lockTariff(tariffOf(versionId), actor);
        commands.lockChargeType(tariff.getChargeTypeId(), actor);
        TariffVersionRecord version = commands.version(tariff, versionId);
        return change(commands.update(tariff, version, terms, TariffCommands.blankToNull(input.getNote()), actor));
    }

    @Override
    public Change delete(UUID versionId, AccessProfile actor) {
        TariffRecord tariff = commands.lockTariff(tariffOf(versionId), actor);
        commands.lockChargeType(tariff.getChargeTypeId(), actor);
        TariffVersionRecord version = commands.version(tariff, versionId);
        return change(commands.delete(tariff, version, true, actor));
    }

    /** The tariff of a version that is not deleted; 404 otherwise (RLS hides other cooperatives' versions). */
    private UUID tariffOf(UUID versionId) {
        return versions.findById(versionId).filter(version -> !version.isDeleted())
                .map(TariffVersionRecord::getTariffId).orElseThrow(ApiProblemException::notFound);
    }

    private static Change change(TariffCommands.Applied applied) {
        TariffVersionRecord version = applied.version();
        boolean deleted = version.isDeleted();
        VersionTerms terms = deleted ? null : new VersionTerms(version.getValidFrom(), version.getBasis(),
                version.getFrequency(), version.getRate(), version.getDueDate());
        return new Change(applied.tariff().getId(), applied.tariff().getChargeTypeId(), version.getId(), terms,
                deleted ? null : version.getNote(), applied.from(), applied.to(), applied.event());
    }
}
