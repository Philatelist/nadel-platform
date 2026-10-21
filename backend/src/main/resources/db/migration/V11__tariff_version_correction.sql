-- A tariff version can be corrected or deleted until a charge uses it (feature 009, amended 2026-10-21): V10 made the
-- table append-only, which cannot express that rule. The application role may UPDATE and DELETE versions again; what
-- stops a version that a charge was produced from is the use check of the tariffs module (the TariffVersionUsage port,
-- implemented by the ledger in feature 011), run in the transaction that changes the row. After that a correction goes
-- through recalculation (feature 012). Row level security still limits both statements to the caller's cooperative.
-- TRUNCATE stays revoked: it would bypass the row filter and the use check alike.
GRANT UPDATE, DELETE ON tariff_version TO nadel_app;
REVOKE TRUNCATE ON tariff_version FROM nadel_app;
