-- Feature 017: the send lanes that have deliveries due, for the delivery sweep. The sweep runs every minute without a
-- bound cooperative (it wakes the JobRunr job of each lane, so nothing waits after a restart or a lost job), where RLS
-- shows no delivery row; like notifications_bot_tenants() (V29) the list comes from a SECURITY DEFINER function owned
-- by the migration role (the owner of the table, which RLS does not apply to as the table is not forced). It returns a
-- cooperative id and a channel code per lane only, never a row, an address or a text.
-- New function only; the previous release never calls it.

SET LOCAL lock_timeout = '10s';

CREATE FUNCTION notifications_due_lanes(p_now timestamptz) RETURNS TABLE (tenant_id uuid, channel text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT DISTINCT d.tenant_id, d.channel
    FROM public.delivery d
    WHERE d.status = 'QUEUED'
      AND d.channel IN ('EMAIL', 'TELEGRAM')
      AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= p_now)
$$;

REVOKE ALL ON FUNCTION notifications_due_lanes(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notifications_due_lanes(timestamptz) TO nadel_app;
