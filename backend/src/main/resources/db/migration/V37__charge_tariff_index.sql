-- Index for the tariff usage checks: the tariff list "deletable" flag and the tariff delete guard look up charge rows by
-- tariff (TariffUsage.everCharged, lastChargedDay). The composite foreign key charge_tariff_fk has no index on its
-- referencing side, so these lookups scanned the tenant's whole ledger.
-- Leads with tenant_id like the other charge indexes (RLS adds tenant_id = current_tenant_id() to every query) and is
-- partial: most charges (meter, common-consumer, manual) name no tariff, and every lookup is tariff_id = / in (...).
-- Plain CREATE INDEX (not CONCURRENTLY): Flyway runs the script in a transaction, and a cooperative's ledger is small.
-- The ledger is append-only, so no data changes; the previous release runs fine with or without the index.
CREATE INDEX charge_tariff_idx ON charge (tenant_id, tariff_id) WHERE tariff_id IS NOT NULL;
