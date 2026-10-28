package app.nadel.privacy.internal;

import java.util.UUID;

/**
 * One row of {@code tenant_data_key} as stored: both keys wrapped by the master key {@code masterKeyId}. The SQL is
 * shared by the runtime store (application role, RLS) and the owner keyring (Flyway's connection).
 */
record TenantKeyRow(UUID tenantId, int version, byte[] dataKeyWrapped, byte[] indexKeyWrapped, byte[] masterKeyId) {

    static final String SELECT = "select tenant_id, version, data_key_wrapped, index_key_wrapped, master_key_id "
            + "from tenant_data_key where tenant_id = ? and version = ?";
    static final String SELECT_ALL = "select tenant_id, version, data_key_wrapped, index_key_wrapped, master_key_id "
            + "from tenant_data_key order by tenant_id, version";
    static final String INSERT = "insert into tenant_data_key "
            + "(tenant_id, version, data_key_wrapped, index_key_wrapped, master_key_id) values (?, ?, ?, ?, ?) "
            + "on conflict (tenant_id, version) do nothing";

    /** Never prints key material. */
    @Override
    public String toString() {
        return "TenantKeyRow[tenantId=" + tenantId + ", version=" + version + "]";
    }
}
