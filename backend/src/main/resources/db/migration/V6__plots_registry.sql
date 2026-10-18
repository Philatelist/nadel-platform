-- Spec 006: plots registry. Extends the phase-0 plot table; RLS, the tenant_isolation policy, the tenant_id index and
-- the grants of plot and plot_status are unchanged (existing tables, covered by the default privileges of V1).
-- Compatibility with the previous release (expand/contract): only columns with defaults or nullable columns are added,
-- the previous release never writes to plot, and it reads plot by column name, so the new columns do not affect it.

-- Natural sort key of a plot number: every run of digits is left-padded with zeros to 20 characters and every other
-- run is lower-cased, so "2" < "10" < "12" < "12a" < "13" < "R-1". A plot number has at most 20 characters, so a digit
-- run never exceeds the padding width. Compare the key with COLLATE "C" so the order does not depend on the locale.
CREATE FUNCTION natural_sort_key(value text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
AS $$
    SELECT coalesce(string_agg(
                        CASE WHEN part[1] ~ '^[0-9]+$'
                             THEN repeat('0', greatest(20 - length(part[1]), 0)) || part[1]
                             ELSE lower(part[1])
                        END,
                        '' ORDER BY position),
                    '')
    FROM regexp_matches(value, '([0-9]+|[^0-9]+)', 'g') WITH ORDINALITY AS t(part, position)
$$;

GRANT EXECUTE ON FUNCTION natural_sort_key(text) TO nadel_app;

ALTER TABLE plot
    ADD COLUMN has_building   boolean     NOT NULL DEFAULT false,
    ADD COLUMN notes          text,
    ADD COLUMN archived_at    timestamptz,
    ADD COLUMN updated_at     timestamptz NOT NULL DEFAULT now(),
    -- Sort key for the plots list; computed by the database, not mapped in JPA.
    ADD COLUMN number_sort_key text COLLATE "C" GENERATED ALWAYS AS (natural_sort_key(number)) STORED;

-- A number is unique per cooperative ignoring case, among plots that are not archived: an archived plot frees its
-- number, and restoring it re-checks the number.
ALTER TABLE plot DROP CONSTRAINT plot_tenant_id_number_key;

CREATE UNIQUE INDEX plot_tenant_number_uq ON plot (tenant_id, lower(number)) WHERE archived_at IS NULL;

-- A cadastral number is unique per cooperative, archived plots included.
CREATE UNIQUE INDEX plot_tenant_cadastral_uq ON plot (tenant_id, cadastral_number) WHERE cadastral_number IS NOT NULL;

-- A plot has an area; common land may have none. The old area_m2 >= 0 check stays.
ALTER TABLE plot
    ADD CONSTRAINT plot_area_by_kind_chk CHECK (kind <> 'PLOT' OR area_m2 > 0),
    ADD CONSTRAINT plot_number_chk CHECK (char_length(number) BETWEEN 1 AND 20 AND number = btrim(number)),
    ADD CONSTRAINT plot_cadastral_chk CHECK (cadastral_number IS NULL OR cadastral_number ~ '^\d{10}:\d{2}:\d{3}:\d{4}$');

-- Until ownership and the ledger exist (specs 007+), every plot is GREY with no debt. The status rows seeded in phase 0
-- carried demo colours; reset them and give every plot a row.
UPDATE plot_status
SET status = 'GREY', debt = 0, oldest_debt_date = NULL, updated_at = now();

INSERT INTO plot_status (plot_id, tenant_id, status, debt)
SELECT p.id, p.tenant_id, 'GREY', 0
FROM plot p
WHERE NOT EXISTS (SELECT 1 FROM plot_status s WHERE s.plot_id = p.id);
