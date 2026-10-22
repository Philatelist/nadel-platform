-- Spec 011 (slice 2): closing billing months and the settings of the plot status, losses and common consumers.
-- billing_month already has every column it needs (V13: status, run_at, closed_at, closed_by); this migration adds the
-- trigger that keeps a closed month closed. cooperative_settings gains five columns with defaults, so a missing row and
-- an existing row both mean the defaults.
-- Compatibility with the previous release (expand/contract): new columns with defaults that the previous release never
-- reads or writes (its settings upsert names only member_plot_status_visibility, so the new columns keep their values);
-- the trigger refuses only what no release does (reopening a closed month, changing a closed month, deleting a month).

-- A closed month never changes and is never reopened; a month is never deleted (DELETE and TRUNCATE are also revoked
-- from nadel_app in V13). An open month may change (run_at, closing); a closed one may not, and the month of a row
-- never moves. The only way past the
-- trigger is nadel_owner with SET LOCAL app.ledger_maintenance = 'on' (demo data and test helpers), as for the ledger
-- tables; nadel_app is refused even with the setting. Not SECURITY DEFINER: current_user is the caller.
CREATE FUNCTION billing_month_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF current_user = 'nadel_owner' AND current_setting('app.ledger_maintenance', true) = 'on' THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        ELSIF TG_OP = 'UPDATE' THEN
            RETURN NEW;
        END IF;
        RETURN NULL;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.status = 'CLOSED' THEN
            RAISE EXCEPTION 'billing month % is closed and never changes', OLD.month
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        -- the cooperative of a row is kept by RLS (WITH CHECK); the month itself never moves
        IF NEW.month <> OLD.month THEN
            RAISE EXCEPTION 'the month of a billing month never changes'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'billing months are never removed: % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER billing_month_guard BEFORE UPDATE OR DELETE ON billing_month
    FOR EACH ROW EXECUTE FUNCTION billing_month_guard();
CREATE TRIGGER billing_month_no_truncate BEFORE TRUNCATE ON billing_month
    FOR EACH STATEMENT EXECUTE FUNCTION billing_month_guard();

-- The settings of the ledger (feature 011): how losses are charged, the default split of common consumers and the red
-- threshold of the plot status (docs/domain/money-rules.md, section 9).
ALTER TABLE cooperative_settings
    ADD COLUMN losses_mode                   text          NOT NULL DEFAULT 'FIXED_PERCENT',
    ADD COLUMN losses_percent                numeric(5,2)  NOT NULL DEFAULT 0,
    ADD COLUMN common_consumer_default_split text          NOT NULL DEFAULT 'CONNECTED_PLOTS',
    ADD COLUMN red_amount                    numeric(14,2) NOT NULL DEFAULT 1000.00,
    ADD COLUMN red_days                      integer       NOT NULL DEFAULT 90,
    ADD CONSTRAINT cooperative_settings_losses_mode_ck CHECK (losses_mode IN ('FIXED_PERCENT', 'PROPORTIONAL')),
    ADD CONSTRAINT cooperative_settings_losses_percent_ck CHECK (losses_percent BETWEEN 0 AND 100),
    ADD CONSTRAINT cooperative_settings_split_ck CHECK (common_consumer_default_split IN
        ('CONNECTED_PLOTS', 'EQUAL_PER_PLOT', 'SERVICE_USERS', 'NOT_CHARGED')),
    ADD CONSTRAINT cooperative_settings_red_amount_ck CHECK (red_amount >= 0),
    ADD CONSTRAINT cooperative_settings_red_days_ck CHECK (red_days BETWEEN 0 AND 36500);
