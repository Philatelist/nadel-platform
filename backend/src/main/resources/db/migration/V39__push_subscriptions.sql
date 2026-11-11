-- Web push (spec 021): the devices of a member that receive push messages, and the throttle of the approvals push.
--
-- push_subscription: the endpoint and the two keys of a browser subscription are sealed by PersonalDataCipher
-- (ADR 0016); endpoint_index is the blind index of the endpoint, so one endpoint is stored once per cooperative.
-- server_key_id is the first 8 bytes of SHA-256 of the VAPID public key the subscription was made with.
-- push_throttle: when a topic was last pushed to a member (one conditional upsert claims the next send).
-- Members are archived, not deleted; the links to member are ON DELETE CASCADE as in member_phone_index (V22) because
-- a device row and a throttle row are derived data that carry nothing worth keeping, and the application deletes the
-- devices itself when access is revoked. Both tables are expand-only: the previous release ignores them.

SET LOCAL lock_timeout = '10s';

-- ---- push_subscription

CREATE TABLE push_subscription (
    id                   uuid        PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenant (id),
    member_id            uuid        NOT NULL,
    endpoint_enc         bytea       NOT NULL,
    p256dh_enc           bytea       NOT NULL,
    auth_enc             bytea       NOT NULL,
    endpoint_index       bytea       NOT NULL,
    server_key_id        bytea       NOT NULL,
    label                text,
    language             text        NOT NULL,
    consecutive_failures smallint    NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    last_sent_at         timestamptz,
    CONSTRAINT push_subscription_endpoint_uq UNIQUE (tenant_id, endpoint_index),
    CONSTRAINT push_subscription_server_key_id_ck CHECK (octet_length(server_key_id) = 8),
    CONSTRAINT push_subscription_label_ck CHECK (label IS NULL OR char_length(label) <= 80),
    CONSTRAINT push_subscription_language_ck CHECK (language IN ('uk', 'en')),
    CONSTRAINT push_subscription_failures_ck CHECK (consecutive_failures >= 0),
    CONSTRAINT push_subscription_member_fk FOREIGN KEY (tenant_id, member_id)
        REFERENCES member (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX push_subscription_tenant_idx ON push_subscription (tenant_id);
CREATE INDEX push_subscription_member_idx ON push_subscription (tenant_id, member_id);

ALTER TABLE push_subscription ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON push_subscription
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The default privileges (V1) give nadel_app SELECT, INSERT, UPDATE and DELETE; TRUNCATE is revoked defensively.
REVOKE TRUNCATE ON push_subscription FROM nadel_app;

-- ---- push_throttle

CREATE TABLE push_throttle (
    tenant_id    uuid        NOT NULL REFERENCES tenant (id),
    member_id    uuid        NOT NULL,
    topic        text        NOT NULL,
    last_sent_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, member_id, topic),
    CONSTRAINT push_throttle_topic_ck CHECK (topic IN ('READINGS_TO_APPROVE')),
    CONSTRAINT push_throttle_member_fk FOREIGN KEY (tenant_id, member_id)
        REFERENCES member (tenant_id, id) ON DELETE CASCADE
);

-- The leading column of the primary key serves as the tenant_id index.
ALTER TABLE push_throttle ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON push_throttle
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

REVOKE TRUNCATE ON push_throttle FROM nadel_app;
