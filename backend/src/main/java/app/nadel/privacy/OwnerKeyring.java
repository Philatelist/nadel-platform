package app.nadel.privacy;

import java.sql.Connection;
import java.util.UUID;

/**
 * Personal data keys for code that runs as the schema owner, on Flyway's connection: Java migrations that encrypt
 * existing rows of every cooperative and callbacks such as the demo contacts. The application role sees one
 * cooperative at a time, so this work cannot go through {@link PersonalDataCipher}.
 * <p>
 * It depends only on the master keys, never on JPA or the application's data source, so Flyway can use it before the
 * persistence layer exists. Formats and keys are the same as {@link PersonalDataCipher}'s: a value sealed here opens
 * there and the other way round.
 */
public interface OwnerKeyring {

    /** A session on the given connection; it neither commits nor closes it. Not thread-safe. */
    Session open(Connection connection);

    /** Keys of the cooperatives seen through one owner connection, cached for the session. */
    interface Session {

        /**
         * The cipher of a cooperative. Its keys are read on the session's connection, or created there (inside the
         * caller's transaction) when the cooperative has none yet.
         */
        TenantCipher forTenant(UUID tenantId);
    }

    /** Seals, opens and indexes values of one cooperative. */
    interface TenantCipher {

        /** As {@link PersonalDataCipher#encrypt}. */
        byte[] encrypt(FieldRef field, String plaintext);

        /** As {@link PersonalDataCipher#decrypt}. */
        String decrypt(FieldRef field, byte[] sealed);

        /** As {@link PersonalDataCipher#blindIndex}. */
        byte[] blindIndex(IndexKind kind, String normalised);
    }
}
