-- Feature 017: delivery of invoices, payment notices and announcements by e-mail and Telegram. New tables only (the
-- previous release never reads them): per-cooperative SMTP and bot settings (secrets sealed by the application,
-- ADR 0016), Telegram links and one-time link codes, announcements (append-only), the delivery queue/register, the
-- per-channel send lanes and the per-bot poll state. Also the function that lists the cooperatives that have a bot
-- (the poller runs without a bound cooperative) and the permission ANNOUNCEMENTS_SEND.
-- RLS is enabled but not forced: the owner (the migration role, owner of the tables) reads past the policies, which
-- is what makes notifications_bot_tenants() work.

SET LOCAL lock_timeout = '10s';

-- ---- delivery_settings

-- One row per cooperative. The SMTP password and the bot token are sealed (bytea); only the last four characters of
-- the token are kept in the clear to show "••••1234". settings_version grows with every change of the bot token.
CREATE TABLE delivery_settings (
    tenant_id          uuid        PRIMARY KEY REFERENCES tenant (id),
    smtp_host          text,
    smtp_port          int,
    smtp_security      text,
    smtp_user          text,
    smtp_from_address  text,
    smtp_from_name     text,
    smtp_password_enc  bytea,
    bot_token_enc      bytea,
    bot_token_last4    text,
    bot_username       text,
    email_rate_per_second int NOT NULL DEFAULT 1,
    notify_payments    boolean     NOT NULL DEFAULT true,
    settings_version   int         NOT NULL DEFAULT 1,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT delivery_settings_port_ck CHECK (smtp_port IS NULL OR smtp_port BETWEEN 1 AND 65535),
    CONSTRAINT delivery_settings_security_ck CHECK (smtp_security IS NULL OR smtp_security IN ('STARTTLS', 'SSL')),
    CONSTRAINT delivery_settings_rate_ck CHECK (email_rate_per_second BETWEEN 1 AND 10),
    CONSTRAINT delivery_settings_version_ck CHECK (settings_version >= 1),
    CONSTRAINT delivery_settings_bot_token_ck CHECK ((bot_token_enc IS NULL) = (bot_token_last4 IS NULL))
);

CREATE INDEX delivery_settings_tenant_idx ON delivery_settings (tenant_id);

ALTER TABLE delivery_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON delivery_settings
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- ---- telegram_link

-- A member's Telegram chat. chat_id_enc is sealed, chat_id_bidx its blind index (the same chat may serve two
-- cooperatives, so the key is per cooperative). A link is never edited except to end it, once.
CREATE TABLE telegram_link (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    member_id     uuid        NOT NULL,
    chat_id_enc   bytea       NOT NULL,
    chat_id_bidx  bytea       NOT NULL,
    linked_at     timestamptz NOT NULL DEFAULT now(),
    ended_at      timestamptz,
    end_reason    text,
    CONSTRAINT telegram_link_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT telegram_link_member_fk FOREIGN KEY (tenant_id, member_id)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT telegram_link_end_reason_ck CHECK (end_reason IS NULL OR end_reason IN ('UNLINKED', 'BLOCKED', 'MOVED')),
    CONSTRAINT telegram_link_end_ck CHECK ((ended_at IS NULL) = (end_reason IS NULL))
);

CREATE INDEX telegram_link_tenant_idx ON telegram_link (tenant_id);
-- One active link per member and one active link per chat (SQLSTATE 23505).
CREATE UNIQUE INDEX telegram_link_member_active_uq ON telegram_link (tenant_id, member_id) WHERE ended_at IS NULL;
CREATE UNIQUE INDEX telegram_link_chat_active_uq ON telegram_link (tenant_id, chat_id_bidx) WHERE ended_at IS NULL;

ALTER TABLE telegram_link ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON telegram_link
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The only change a row gets: ended_at and end_reason go from null to set, once. SQLSTATE 23514. The owner's
-- maintenance path (demo wipe) is allowed as for the ledger tables.
CREATE FUNCTION telegram_link_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF current_user = 'nadel_owner' AND current_setting('app.ledger_maintenance', true) = 'on' THEN
        RETURN NEW;
    END IF;
    IF OLD.ended_at IS NOT NULL
       OR (NEW.id, NEW.tenant_id, NEW.member_id, NEW.chat_id_enc, NEW.chat_id_bidx, NEW.linked_at)
          IS DISTINCT FROM
          (OLD.id, OLD.tenant_id, OLD.member_id, OLD.chat_id_enc, OLD.chat_id_bidx, OLD.linked_at) THEN
        RAISE EXCEPTION 'telegram_link_guard: a link can only be ended, once'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'telegram_link_guard';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER telegram_link_guard BEFORE UPDATE ON telegram_link
    FOR EACH ROW EXECUTE FUNCTION telegram_link_guard();

-- ---- telegram_link_code

-- A one-time code in a deep link; only its hash is stored. ACCOUNT codes come from the member's account, PAPER codes
-- from a printed invoice.
CREATE TABLE telegram_link_code (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    code_hash  bytea       NOT NULL,
    member_id  uuid        NOT NULL,
    purpose    text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    CONSTRAINT telegram_link_code_hash_uq UNIQUE (tenant_id, code_hash),
    CONSTRAINT telegram_link_code_member_fk FOREIGN KEY (tenant_id, member_id)
        REFERENCES member (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT telegram_link_code_purpose_ck CHECK (purpose IN ('ACCOUNT', 'PAPER')),
    CONSTRAINT telegram_link_code_expiry_ck CHECK (expires_at > created_at)
);

CREATE INDEX telegram_link_code_tenant_idx ON telegram_link_code (tenant_id);
CREATE INDEX telegram_link_code_member_idx ON telegram_link_code (tenant_id, member_id);

ALTER TABLE telegram_link_code ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON telegram_link_code
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- ---- announcement

-- An announcement of the board. text is user content (not sealed). audience is {"streets": [...]}, empty means all
-- plots; channels are those the board allowed for the send.
CREATE TABLE announcement (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    text       text        NOT NULL,
    audience   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    channels   text[]      NOT NULL,
    created_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT announcement_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT announcement_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT announcement_text_ck CHECK (char_length(text) BETWEEN 1 AND 2000 AND btrim(text) <> ''),
    CONSTRAINT announcement_audience_ck CHECK (jsonb_typeof(audience) = 'object'),
    CONSTRAINT announcement_channels_ck CHECK (
        cardinality(channels) >= 1 AND channels <@ ARRAY['EMAIL', 'TELEGRAM', 'PAPER']::text[])
);

CREATE INDEX announcement_tenant_idx ON announcement (tenant_id);
CREATE INDEX announcement_created_idx ON announcement (tenant_id, created_at DESC);

ALTER TABLE announcement ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON announcement
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Append-only; the owner's maintenance path (demo wipe) is allowed by ledger_append_only() (V13).
CREATE TRIGGER announcement_append_only BEFORE UPDATE OR DELETE ON announcement
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER announcement_no_truncate BEFORE TRUNCATE ON announcement
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();

-- ---- delivery

-- One message to one member over one channel. The register and the queue in one table: QUEUED rows are picked by the
-- sender, PAPER rows wait as TO_PRINT until the board marks them PRINTED. Exactly one reference per kind. The unique
-- keys make a repeated enqueue a no-op (INSERT ... ON CONFLICT DO NOTHING).
CREATE TABLE delivery (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenant (id),
    kind             text        NOT NULL,
    invoice_id       uuid,
    invoice_version  int,
    payment_id       uuid,
    announcement_id  uuid,
    billing_month    date,
    member_id        uuid        NOT NULL,
    channel          text        NOT NULL,
    note             text,
    status           text        NOT NULL,
    failure_code     text,
    attempts         int         NOT NULL DEFAULT 0,
    resends          int         NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz,
    sent_at          timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT delivery_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT delivery_member_fk FOREIGN KEY (tenant_id, member_id)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT delivery_invoice_fk FOREIGN KEY (tenant_id, invoice_id)
        REFERENCES invoice (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT delivery_payment_fk FOREIGN KEY (tenant_id, payment_id)
        REFERENCES payment (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT delivery_announcement_fk FOREIGN KEY (tenant_id, announcement_id)
        REFERENCES announcement (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT delivery_kind_ck CHECK (kind IN ('INVOICE', 'PAYMENT', 'REVERSAL', 'ANNOUNCEMENT')),
    CONSTRAINT delivery_channel_ck CHECK (channel IN ('EMAIL', 'TELEGRAM', 'PAPER')),
    CONSTRAINT delivery_note_ck CHECK (note IS NULL OR note = 'VIBER_UNSUPPORTED'),
    CONSTRAINT delivery_status_ck CHECK (status IN ('QUEUED', 'SENT', 'FAILED', 'TO_PRINT', 'PRINTED')),
    CONSTRAINT delivery_failure_code_ck CHECK (failure_code IS NULL OR failure_code IN (
        'SMTP_REJECTED', 'SMTP_AUTH_FAILED', 'CONNECTION_FAILED', 'TELEGRAM_BLOCKED', 'CHANNEL_NOT_CONFIGURED',
        'NO_CONTACT')),
    -- A failed delivery says why.
    CONSTRAINT delivery_failed_code_ck CHECK (status <> 'FAILED' OR failure_code IS NOT NULL),
    -- Only paper copies are printed.
    CONSTRAINT delivery_print_ck CHECK (status NOT IN ('TO_PRINT', 'PRINTED') OR channel = 'PAPER'),
    -- Exactly one reference per kind.
    CONSTRAINT delivery_reference_ck CHECK (
        (kind = 'INVOICE' AND invoice_id IS NOT NULL AND invoice_version IS NOT NULL
            AND payment_id IS NULL AND announcement_id IS NULL)
        OR (kind IN ('PAYMENT', 'REVERSAL') AND payment_id IS NOT NULL
            AND invoice_id IS NULL AND invoice_version IS NULL AND announcement_id IS NULL)
        OR (kind = 'ANNOUNCEMENT' AND announcement_id IS NOT NULL
            AND invoice_id IS NULL AND invoice_version IS NULL AND payment_id IS NULL)),
    CONSTRAINT delivery_billing_month_ck CHECK (billing_month IS NULL OR billing_month = date_trunc('month', billing_month)::date),
    CONSTRAINT delivery_attempts_ck CHECK (attempts >= 0 AND resends >= 0)
);

CREATE INDEX delivery_tenant_idx ON delivery (tenant_id);
CREATE INDEX delivery_queue_idx ON delivery (tenant_id, status, next_attempt_at) WHERE status = 'QUEUED';
CREATE INDEX delivery_month_kind_idx ON delivery (tenant_id, billing_month, kind);
CREATE INDEX delivery_member_idx ON delivery (tenant_id, member_id);
CREATE INDEX delivery_invoice_idx ON delivery (tenant_id, invoice_id) WHERE invoice_id IS NOT NULL;
CREATE INDEX delivery_announcement_idx ON delivery (tenant_id, announcement_id) WHERE announcement_id IS NOT NULL;

-- A message is created once per recipient and channel (SQLSTATE 23505, or no row with ON CONFLICT DO NOTHING).
CREATE UNIQUE INDEX delivery_invoice_uq ON delivery (tenant_id, invoice_id, member_id, channel)
    WHERE kind = 'INVOICE';
CREATE UNIQUE INDEX delivery_payment_uq ON delivery (tenant_id, payment_id, kind, member_id, channel)
    WHERE kind IN ('PAYMENT', 'REVERSAL');
CREATE UNIQUE INDEX delivery_announcement_uq ON delivery (tenant_id, announcement_id, member_id, channel)
    WHERE kind = 'ANNOUNCEMENT';

ALTER TABLE delivery ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON delivery
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Only the state of the sending changes: status, failure_code, attempts, resends, next_attempt_at, sent_at.
-- SQLSTATE 23514. The owner's maintenance path (demo wipe) is allowed as for the ledger tables.
CREATE FUNCTION delivery_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF current_user = 'nadel_owner' AND current_setting('app.ledger_maintenance', true) = 'on' THEN
        RETURN NEW;
    END IF;
    IF (NEW.id, NEW.tenant_id, NEW.kind, NEW.invoice_id, NEW.invoice_version, NEW.payment_id, NEW.announcement_id,
        NEW.billing_month, NEW.member_id, NEW.channel, NEW.note, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.kind, OLD.invoice_id, OLD.invoice_version, OLD.payment_id, OLD.announcement_id,
        OLD.billing_month, OLD.member_id, OLD.channel, OLD.note, OLD.created_at) THEN
        RAISE EXCEPTION 'delivery_guard: only the sending state of a delivery can change'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'delivery_guard';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER delivery_guard BEFORE UPDATE ON delivery
    FOR EACH ROW EXECUTE FUNCTION delivery_guard();

-- ---- delivery_lane

-- The lease of a send lane (one sender per cooperative and channel at a time).
CREATE TABLE delivery_lane (
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    channel     text        NOT NULL,
    lease_owner text,
    lease_until timestamptz,
    PRIMARY KEY (tenant_id, channel),
    CONSTRAINT delivery_lane_channel_ck CHECK (channel IN ('EMAIL', 'TELEGRAM'))
);

CREATE INDEX delivery_lane_tenant_idx ON delivery_lane (tenant_id);

ALTER TABLE delivery_lane ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON delivery_lane
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- ---- bot_poll_state

-- Where the long poll of a cooperative's bot stands, and who holds it. settings_version is the version of the bot
-- settings the state belongs to (a new token starts from scratch).
CREATE TABLE bot_poll_state (
    tenant_id        uuid   PRIMARY KEY REFERENCES tenant (id),
    last_update_id   bigint NOT NULL DEFAULT 0,
    lease_owner      text,
    lease_until      timestamptz,
    settings_version int    NOT NULL DEFAULT 1
);

CREATE INDEX bot_poll_state_tenant_idx ON bot_poll_state (tenant_id);

ALTER TABLE bot_poll_state ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bot_poll_state
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- ---- grants

-- The default privileges (V1) give nadel_app SELECT, INSERT, UPDATE and DELETE. Nothing here is deleted by the
-- application except the spent link codes; announcements are never updated.
REVOKE DELETE, TRUNCATE ON delivery_settings, telegram_link, announcement, delivery, delivery_lane,
    bot_poll_state FROM nadel_app;
REVOKE UPDATE, TRUNCATE ON announcement FROM nadel_app;
REVOKE TRUNCATE ON telegram_link_code FROM nadel_app;

-- ---- notifications_bot_tenants

-- The cooperatives that have a bot token. The poller runs without a bound cooperative, where RLS shows nothing, so the
-- list comes from this function; it returns identifiers only. It runs as its owner (the migration role, owner of the
-- tables, which RLS does not apply to as the tables are not forced).
CREATE FUNCTION notifications_bot_tenants() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT s.tenant_id FROM public.delivery_settings s WHERE s.bot_token_enc IS NOT NULL
$$;

REVOKE ALL ON FUNCTION notifications_bot_tenants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notifications_bot_tenants() TO nadel_app;

-- ---- ANNOUNCEMENTS_SEND

-- V28 body plus (CHAIRMAN, ANNOUNCEMENTS_SEND).
CREATE OR REPLACE FUNCTION seed_base_roles(p_tenant uuid) RETURNS void
    LANGUAGE sql
AS $$
    INSERT INTO role (id, tenant_id, base_code, name, description)
    VALUES (md5(p_tenant::text || ':CHAIRMAN')::uuid, p_tenant, 'CHAIRMAN', 'Chairman', 'Full access'),
           (md5(p_tenant::text || ':TREASURER')::uuid, p_tenant, 'TREASURER', 'Treasurer', 'Read access to members, plots and audit'),
           (md5(p_tenant::text || ':MEMBER')::uuid, p_tenant, 'MEMBER', 'Member', 'Own data only')
    ON CONFLICT DO NOTHING;

    INSERT INTO role_permission (tenant_id, role_id, permission)
    SELECT p_tenant, md5(p_tenant::text || ':' || m.code)::uuid, m.permission
    FROM (VALUES
        ('CHAIRMAN', 'MEMBERS_VIEW'), ('CHAIRMAN', 'MEMBERS_EDIT'), ('CHAIRMAN', 'ROLES_MANAGE'),
        ('CHAIRMAN', 'AUDIT_VIEW'), ('CHAIRMAN', 'PLOTS_VIEW'), ('CHAIRMAN', 'PLOTS_EDIT'),
        ('CHAIRMAN', 'OWNERSHIP_EDIT'), ('CHAIRMAN', 'IMPORT_RUN'), ('CHAIRMAN', 'SETTINGS_MANAGE'),
        ('CHAIRMAN', 'TARIFFS_VIEW'), ('CHAIRMAN', 'TARIFFS_MANAGE'),
        ('CHAIRMAN', 'METERS_VIEW'), ('CHAIRMAN', 'METERS_MANAGE'), ('CHAIRMAN', 'READINGS_ENTER'),
        ('CHAIRMAN', 'READINGS_APPROVE'),
        ('CHAIRMAN', 'CHARGES_VIEW'), ('CHAIRMAN', 'CHARGES_RUN'), ('CHAIRMAN', 'CHARGES_REVERSE'),
        ('CHAIRMAN', 'PERIODS_CLOSE'), ('CHAIRMAN', 'RECALCULATION_RUN'),
        ('CHAIRMAN', 'REPORTS_VIEW'), ('CHAIRMAN', 'REPORT_TEMPLATES_MANAGE'),
        ('CHAIRMAN', 'PAYMENTS_VIEW'), ('CHAIRMAN', 'PAYMENTS_ENTER'), ('CHAIRMAN', 'PAYMENTS_REVERSE'),
        ('CHAIRMAN', 'INVOICES_ISSUE'), ('CHAIRMAN', 'CERTIFICATES_ISSUE'),
        ('CHAIRMAN', 'ANNOUNCEMENTS_SEND'),
        ('TREASURER', 'MEMBERS_VIEW'), ('TREASURER', 'PLOTS_VIEW'), ('TREASURER', 'AUDIT_VIEW'),
        ('TREASURER', 'TARIFFS_VIEW'), ('TREASURER', 'METERS_VIEW'), ('TREASURER', 'CHARGES_VIEW'),
        ('TREASURER', 'REPORTS_VIEW'),
        ('TREASURER', 'PAYMENTS_VIEW'), ('TREASURER', 'PAYMENTS_ENTER'),
        ('TREASURER', 'INVOICES_ISSUE')
    ) AS m(code, permission)
    ON CONFLICT DO NOTHING;

    SELECT seed_charge_types(p_tenant);
$$;

GRANT EXECUTE ON FUNCTION seed_base_roles(uuid) TO nadel_app;

-- Existing cooperatives: targeted insert (idempotent), never a re-run of seed_base_roles (V13 explains why).
INSERT INTO role_permission (tenant_id, role_id, permission)
SELECT r.tenant_id, r.id, 'ANNOUNCEMENTS_SEND'
FROM role r
WHERE r.base_code = 'CHAIRMAN'
ON CONFLICT DO NOTHING;
