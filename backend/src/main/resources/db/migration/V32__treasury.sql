-- Spec 018 (slice 1): the cash book. Nine tables of the treasury module: money_account (one cash and one bank account per
-- cooperative), account_movement (the append-only journal of every money move), handover (cash taken to the bank),
-- expense_category (expense and income categories), expense and expense_attachment (spending with receipt files),
-- other_income, cash_count (a counted cash balance) and bank_reconciliation (the monthly bank statement comparison).
-- Also: the CASHBOOK_VIEW, CASHBOOK_ENTER and CASHBOOK_APPROVE permissions (role_permission.permission is plain text, so
-- no DDL for them), seed_treasury (the two accounts and the seeded categories of a cooperative) and the backfill of one
-- journal movement for every payment row that already exists.
-- Compatibility with the previous release (expand/contract): only new tables, functions and rows the previous release
-- never reads; seed_base_roles keeps its signature and only writes more; payment is only read.
--
-- Every table has tenant_id, RLS with the tenant_isolation policy (USING and WITH CHECK), an index that leads with
-- tenant_id and a UNIQUE (tenant_id, id), so that composite foreign keys (tenant_id, x_id) keep every link inside one
-- cooperative. Text columns with a fixed set of values use named CHECK constraints (no enum types). Amounts are
-- numeric(14,2); nothing is rounded in the database. Balances are never stored: they are computed from the movements.
--
-- Representation notes for the application:
--   * account_movement.booking_month is the FIRST DAY of the billing month (date), the same value as billing_month.month,
--     so it maps to YearMonth with YearMonth.from(date) / ym.atDay(1).
--   * account_movement.amount is signed: money in is positive, money out is negative. A reversal is a new movement of the
--     opposite amount, of the same kind and account, with reverses_movement_id and a reason.
--   * account_movement.kind: MEMBER_PAYMENT, EXPENSE, OTHER_INCOME, HANDOVER_OUT, HANDOVER_IN, COUNT_DIFFERENCE,
--     BANK_DIFFERENCE. source_type/source_id name the row that caused the movement (PAYMENT, EXPENSE, OTHER_INCOME,
--     HANDOVER, CASH_COUNT, BANK_RECONCILIATION, MOVEMENT for a direct reversal of a difference). A MEMBER_PAYMENT
--     movement has source_type PAYMENT and payment_row_id = the ledger payment row (original or reversal) = source_id.

SET LOCAL lock_timeout = '10s';

-- ---- money_account

-- One CASH and one BANK account per cooperative (seed_treasury). The opening balance (a start amount at a date) is
-- written once: opening_* are all null or all set.
CREATE TABLE money_account (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES tenant (id),
    kind              text        NOT NULL,
    opening_amount    numeric(14,2),
    opening_date      date,
    opening_set_by    uuid,
    opening_set_at    timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    CONSTRAINT money_account_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT money_account_tenant_kind_uq UNIQUE (tenant_id, kind),
    CONSTRAINT money_account_kind_ck CHECK (kind IN ('CASH', 'BANK')),
    CONSTRAINT money_account_opening_ck CHECK (
        (opening_amount IS NULL) = (opening_date IS NULL) AND (opening_amount IS NULL) = (opening_set_at IS NULL)
        AND (opening_amount IS NULL OR opening_amount >= 0)),
    CONSTRAINT money_account_opening_set_by_fk FOREIGN KEY (tenant_id, opening_set_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT money_account_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX money_account_tenant_idx ON money_account (tenant_id);

ALTER TABLE money_account ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON money_account
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The opening balance is written once (null to a value); nothing else changes. SQLSTATE 23514.
CREATE FUNCTION money_account_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.kind <> OLD.kind
       OR NEW.created_at <> OLD.created_at OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'money_account_guard: only the opening balance can be written'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'money_account_guard';
    END IF;
    IF OLD.opening_amount IS NOT NULL OR OLD.opening_date IS NOT NULL THEN
        RAISE EXCEPTION 'money_account_guard: the opening balance is already set'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'money_account_guard';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER money_account_guard BEFORE UPDATE ON money_account
    FOR EACH ROW EXECUTE FUNCTION money_account_guard();

REVOKE UPDATE, DELETE, TRUNCATE ON money_account FROM nadel_app;
GRANT UPDATE (opening_amount, opening_date, opening_set_by, opening_set_at) ON money_account TO nadel_app;

-- ---- account_movement

-- The journal: one signed row per move of money on an account. Append-only (ledger_append_only from V13). payment_row_id
-- points at the ledger payment row (original or reversal) a MEMBER_PAYMENT movement was written for; it is unique, so a
-- payment row has at most one movement.
CREATE TABLE account_movement (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    account_id            uuid          NOT NULL,
    kind                  text          NOT NULL,
    amount                numeric(14,2) NOT NULL,
    moved_on              date          NOT NULL,
    booking_month         date          NOT NULL,
    source_type           text          NOT NULL,
    source_id             uuid          NOT NULL,
    payment_row_id        uuid,
    reverses_movement_id  uuid,
    reason                text,
    created_by            uuid,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT account_movement_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT account_movement_account_fk FOREIGN KEY (tenant_id, account_id)
        REFERENCES money_account (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT account_movement_payment_fk FOREIGN KEY (tenant_id, payment_row_id)
        REFERENCES payment (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT account_movement_reverses_fk FOREIGN KEY (tenant_id, reverses_movement_id)
        REFERENCES account_movement (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT account_movement_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT account_movement_kind_ck CHECK (kind IN ('MEMBER_PAYMENT', 'EXPENSE', 'OTHER_INCOME', 'HANDOVER_OUT',
        'HANDOVER_IN', 'COUNT_DIFFERENCE', 'BANK_DIFFERENCE')),
    CONSTRAINT account_movement_source_type_ck CHECK (source_type IN ('PAYMENT', 'EXPENSE', 'OTHER_INCOME', 'HANDOVER',
        'CASH_COUNT', 'BANK_RECONCILIATION', 'MOVEMENT')),
    CONSTRAINT account_movement_amount_ck CHECK (amount <> 0),
    CONSTRAINT account_movement_month_ck CHECK (booking_month = date_trunc('month', booking_month)::date),
    -- Exactly the member payment movements point at a payment row, and they are sourced from it.
    CONSTRAINT account_movement_payment_row_ck CHECK (
        (kind = 'MEMBER_PAYMENT') = (payment_row_id IS NOT NULL)
        AND (payment_row_id IS NULL OR (source_type = 'PAYMENT' AND source_id = payment_row_id))),
    CONSTRAINT account_movement_not_self_reversal_ck CHECK (reverses_movement_id IS NULL OR reverses_movement_id <> id),
    -- A reversal carries a reason (1-500 characters); an original movement does not.
    CONSTRAINT account_movement_reason_ck CHECK (
        (reverses_movement_id IS NOT NULL AND reason IS NOT NULL AND btrim(reason) <> '' AND char_length(reason) <= 500)
        OR (reverses_movement_id IS NULL AND reason IS NULL))
);

CREATE INDEX account_movement_tenant_idx ON account_movement (tenant_id);
-- Balances and the journal of an account by day.
CREATE INDEX account_movement_account_day_idx ON account_movement (tenant_id, account_id, moved_on) INCLUDE (amount);
CREATE INDEX account_movement_month_idx ON account_movement (tenant_id, booking_month);
-- A ledger payment row has at most one movement. SQLSTATE 23505.
CREATE UNIQUE INDEX account_movement_payment_row_uq ON account_movement (tenant_id, payment_row_id)
    WHERE payment_row_id IS NOT NULL;
-- A movement is reversed at most once. SQLSTATE 23505.
CREATE UNIQUE INDEX account_movement_reversed_once_uq ON account_movement (tenant_id, reverses_movement_id)
    WHERE reverses_movement_id IS NOT NULL;

ALTER TABLE account_movement ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON account_movement
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- What a reversal may point at (a CHECK cannot read another row): a movement of the same account and kind with the
-- negated amount. SQLSTATE 23514. A target that is not visible is left to account_movement_reverses_fk. Not SECURITY
-- DEFINER: the lookup runs as the caller, under RLS, and also filters by the tenant of the new row.
CREATE FUNCTION account_movement_target_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    target account_movement%ROWTYPE;
BEGIN
    SELECT * INTO target FROM account_movement WHERE tenant_id = NEW.tenant_id AND id = NEW.reverses_movement_id;
    IF FOUND AND (target.account_id <> NEW.account_id OR target.kind <> NEW.kind OR target.amount <> -NEW.amount) THEN
        RAISE EXCEPTION 'account_movement_target_guard: a reversal has the negated amount and the same account and kind'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'account_movement_target_guard';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_movement_target_guard BEFORE INSERT ON account_movement
    FOR EACH ROW WHEN (NEW.reverses_movement_id IS NOT NULL)
    EXECUTE FUNCTION account_movement_target_guard();

CREATE TRIGGER account_movement_append_only BEFORE UPDATE OR DELETE ON account_movement
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER account_movement_no_truncate BEFORE TRUNCATE ON account_movement
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();

REVOKE UPDATE, DELETE, TRUNCATE ON account_movement FROM nadel_app;

-- ---- expense_category

-- Categories of expenses and of other income. A seeded category has a system_code and keeps name null until it is
-- renamed (its display name comes from the i18n catalogs); a category added by the cooperative has a name and no
-- system_code. Categories are never deleted, only archived.
CREATE TABLE expense_category (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    kind        text        NOT NULL,
    system_code text,
    name        text,
    sort_order  integer     NOT NULL DEFAULT 0,
    archived    boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    CONSTRAINT expense_category_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT expense_category_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT expense_category_kind_ck CHECK (kind IN ('EXPENSE', 'INCOME')),
    CONSTRAINT expense_category_system_code_ck CHECK (system_code IS NULL OR system_code ~ '^[A-Z][A-Z_]{0,63}$'),
    -- A category with no name is a seeded one.
    CONSTRAINT expense_category_name_ck CHECK (
        (name IS NULL AND system_code IS NOT NULL)
        OR (name IS NOT NULL AND btrim(name) <> '' AND char_length(name) <= 100))
);

CREATE INDEX expense_category_tenant_idx ON expense_category (tenant_id);
-- A seeded category exists once per kind and code (seed_treasury is idempotent through it).
CREATE UNIQUE INDEX expense_category_system_code_uq ON expense_category (tenant_id, kind, system_code)
    WHERE system_code IS NOT NULL;
-- A user's name is unique per kind, case-insensitive. SQLSTATE 23505.
CREATE UNIQUE INDEX expense_category_name_uq ON expense_category (tenant_id, kind, lower(name))
    WHERE name IS NOT NULL;

ALTER TABLE expense_category ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON expense_category
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Only name, sort_order and archived change; nothing is deleted.
REVOKE UPDATE, DELETE, TRUNCATE ON expense_category FROM nadel_app;
GRANT UPDATE (name, sort_order, archived) ON expense_category TO nadel_app;

-- ---- expense and expense_attachment

-- Money spent from an account. A reversal is a new row (reverses_expense_id, with a reason) of the same amount; its
-- movement reverses the original's movement. counterparty_enc and description_enc are encrypted personal data (ADR 0016).
CREATE TABLE expense (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid          NOT NULL REFERENCES tenant (id),
    account_id          uuid          NOT NULL,
    category_id         uuid          NOT NULL,
    amount              numeric(14,2) NOT NULL,
    expense_date        date          NOT NULL,
    counterparty_enc    bytea,
    description_enc     bytea,
    document_number     text,
    document_date       date,
    movement_id         uuid          NOT NULL,
    reverses_expense_id uuid,
    reason              text,
    created_by          uuid,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT expense_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT expense_account_fk FOREIGN KEY (tenant_id, account_id)
        REFERENCES money_account (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT expense_category_fk FOREIGN KEY (tenant_id, category_id)
        REFERENCES expense_category (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT expense_movement_fk FOREIGN KEY (tenant_id, movement_id)
        REFERENCES account_movement (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT expense_reverses_fk FOREIGN KEY (tenant_id, reverses_expense_id)
        REFERENCES expense (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT expense_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT expense_amount_ck CHECK (amount > 0),
    CONSTRAINT expense_not_self_reversal_ck CHECK (reverses_expense_id IS NULL OR reverses_expense_id <> id),
    CONSTRAINT expense_reason_ck CHECK (
        (reverses_expense_id IS NOT NULL AND reason IS NOT NULL AND btrim(reason) <> '' AND char_length(reason) <= 500)
        OR (reverses_expense_id IS NULL AND reason IS NULL)),
    CONSTRAINT expense_document_number_ck CHECK (document_number IS NULL OR char_length(document_number) <= 64),
    -- Ciphertext of the text plus the framing of the application (nonce, tag).
    CONSTRAINT expense_counterparty_ck CHECK (
        counterparty_enc IS NULL OR octet_length(counterparty_enc) BETWEEN 1 AND 4096),
    CONSTRAINT expense_description_ck CHECK (
        description_enc IS NULL OR octet_length(description_enc) BETWEEN 1 AND 8192)
);

CREATE INDEX expense_tenant_idx ON expense (tenant_id);
CREATE INDEX expense_date_idx ON expense (tenant_id, expense_date DESC, id);
CREATE INDEX expense_category_idx ON expense (tenant_id, category_id);
-- A movement belongs to one expense; an expense is reversed at most once. SQLSTATE 23505.
CREATE UNIQUE INDEX expense_movement_uq ON expense (tenant_id, movement_id);
CREATE UNIQUE INDEX expense_reversed_once_uq ON expense (tenant_id, reverses_expense_id)
    WHERE reverses_expense_id IS NOT NULL;

ALTER TABLE expense ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON expense
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE TRIGGER expense_append_only BEFORE UPDATE OR DELETE ON expense
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER expense_no_truncate BEFORE TRUNCATE ON expense
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();

REVOKE UPDATE, DELETE, TRUNCATE ON expense FROM nadel_app;

-- Up to five receipt files per expense (JPEG, PNG or PDF, at most 5 MiB each).
CREATE TABLE expense_attachment (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenant (id),
    expense_id   uuid        NOT NULL,
    position     smallint    NOT NULL,
    content_type text        NOT NULL,
    file_name    text        NOT NULL,
    size_bytes   integer     NOT NULL,
    sha256       bytea       NOT NULL,
    content      bytea       NOT NULL,
    created_by   uuid,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT expense_attachment_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT expense_attachment_position_uq UNIQUE (tenant_id, expense_id, position),
    CONSTRAINT expense_attachment_expense_fk FOREIGN KEY (tenant_id, expense_id)
        REFERENCES expense (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT expense_attachment_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT expense_attachment_position_ck CHECK (position BETWEEN 1 AND 5),
    CONSTRAINT expense_attachment_type_ck CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
    CONSTRAINT expense_attachment_name_ck CHECK (btrim(file_name) <> '' AND char_length(file_name) <= 255),
    CONSTRAINT expense_attachment_size_ck CHECK (size_bytes BETWEEN 1 AND 5242880 AND octet_length(content) = size_bytes),
    CONSTRAINT expense_attachment_sha256_ck CHECK (octet_length(sha256) = 32)
);

CREATE INDEX expense_attachment_tenant_idx ON expense_attachment (tenant_id);
CREATE INDEX expense_attachment_expense_idx ON expense_attachment (tenant_id, expense_id);

-- The file is already compressed (JPEG, PNG, PDF): store it out of line without compression.
ALTER TABLE expense_attachment ALTER COLUMN content SET STORAGE EXTERNAL;

ALTER TABLE expense_attachment ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON expense_attachment
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE TRIGGER expense_attachment_append_only BEFORE UPDATE OR DELETE ON expense_attachment
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER expense_attachment_no_truncate BEFORE TRUNCATE ON expense_attachment
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();

REVOKE UPDATE, DELETE, TRUNCATE ON expense_attachment FROM nadel_app;

-- ---- other_income

-- Money received that is not a member payment (bank interest, grants, sales). description_enc is encrypted (ADR 0016).
CREATE TABLE other_income (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                uuid          NOT NULL REFERENCES tenant (id),
    account_id               uuid          NOT NULL,
    category_id              uuid          NOT NULL,
    amount                   numeric(14,2) NOT NULL,
    received_on              date          NOT NULL,
    description_enc          bytea,
    movement_id              uuid          NOT NULL,
    reverses_other_income_id uuid,
    reason                   text,
    created_by               uuid,
    created_at               timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT other_income_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT other_income_account_fk FOREIGN KEY (tenant_id, account_id)
        REFERENCES money_account (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT other_income_category_fk FOREIGN KEY (tenant_id, category_id)
        REFERENCES expense_category (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT other_income_movement_fk FOREIGN KEY (tenant_id, movement_id)
        REFERENCES account_movement (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT other_income_reverses_fk FOREIGN KEY (tenant_id, reverses_other_income_id)
        REFERENCES other_income (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT other_income_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT other_income_amount_ck CHECK (amount > 0),
    CONSTRAINT other_income_not_self_reversal_ck CHECK (
        reverses_other_income_id IS NULL OR reverses_other_income_id <> id),
    CONSTRAINT other_income_reason_ck CHECK (
        (reverses_other_income_id IS NOT NULL AND reason IS NOT NULL AND btrim(reason) <> ''
            AND char_length(reason) <= 500)
        OR (reverses_other_income_id IS NULL AND reason IS NULL)),
    CONSTRAINT other_income_description_ck CHECK (
        description_enc IS NULL OR octet_length(description_enc) BETWEEN 1 AND 8192)
);

CREATE INDEX other_income_tenant_idx ON other_income (tenant_id);
CREATE INDEX other_income_received_idx ON other_income (tenant_id, received_on);
CREATE UNIQUE INDEX other_income_movement_uq ON other_income (tenant_id, movement_id);
CREATE UNIQUE INDEX other_income_reversed_once_uq ON other_income (tenant_id, reverses_other_income_id)
    WHERE reverses_other_income_id IS NOT NULL;

ALTER TABLE other_income ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON other_income
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE TRIGGER other_income_append_only BEFORE UPDATE OR DELETE ON other_income
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER other_income_no_truncate BEFORE TRUNCATE ON other_income
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();

REVOKE UPDATE, DELETE, TRUNCATE ON other_income FROM nadel_app;

-- ---- handover

-- Cash taken to the bank: an out movement on the cash account and an in movement on the bank account. A reversal is a
-- new row (reverses_handover_id, with a reason) pointing at the two reversing movements.
CREATE TABLE handover (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid          NOT NULL REFERENCES tenant (id),
    amount              numeric(14,2) NOT NULL,
    handed_on           date          NOT NULL,
    document_number     text          NOT NULL,
    out_movement_id     uuid          NOT NULL,
    in_movement_id      uuid          NOT NULL,
    reverses_handover_id uuid,
    reason              text,
    created_by          uuid,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT handover_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT handover_out_movement_fk FOREIGN KEY (tenant_id, out_movement_id)
        REFERENCES account_movement (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT handover_in_movement_fk FOREIGN KEY (tenant_id, in_movement_id)
        REFERENCES account_movement (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT handover_reverses_fk FOREIGN KEY (tenant_id, reverses_handover_id)
        REFERENCES handover (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT handover_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT handover_amount_ck CHECK (amount > 0),
    CONSTRAINT handover_movements_ck CHECK (out_movement_id <> in_movement_id),
    CONSTRAINT handover_document_number_ck CHECK (btrim(document_number) <> '' AND char_length(document_number) <= 64),
    CONSTRAINT handover_not_self_reversal_ck CHECK (reverses_handover_id IS NULL OR reverses_handover_id <> id),
    CONSTRAINT handover_reason_ck CHECK (
        (reverses_handover_id IS NOT NULL AND reason IS NOT NULL AND btrim(reason) <> '' AND char_length(reason) <= 500)
        OR (reverses_handover_id IS NULL AND reason IS NULL))
);

CREATE INDEX handover_tenant_idx ON handover (tenant_id);
CREATE INDEX handover_date_idx ON handover (tenant_id, handed_on DESC, id);
CREATE UNIQUE INDEX handover_out_movement_uq ON handover (tenant_id, out_movement_id);
CREATE UNIQUE INDEX handover_in_movement_uq ON handover (tenant_id, in_movement_id);
CREATE UNIQUE INDEX handover_reversed_once_uq ON handover (tenant_id, reverses_handover_id)
    WHERE reverses_handover_id IS NOT NULL;

ALTER TABLE handover ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON handover
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE TRIGGER handover_append_only BEFORE UPDATE OR DELETE ON handover
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER handover_no_truncate BEFORE TRUNCATE ON handover
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();

REVOKE UPDATE, DELETE, TRUNCATE ON handover FROM nadel_app;

-- ---- cash_count

-- A counted cash balance against the book balance at that moment (book_balance is stored when the count is saved;
-- difference = counted_amount - book_balance). A difference is booked as a movement once (booked_movement_id).
CREATE TABLE cash_count (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid          NOT NULL REFERENCES tenant (id),
    account_id          uuid          NOT NULL,
    counted_at          timestamptz   NOT NULL,
    counted_on          date          NOT NULL,
    counted_amount      numeric(14,2) NOT NULL,
    book_balance        numeric(14,2) NOT NULL,
    difference          numeric(14,2) NOT NULL,
    explanation         text,
    booked_movement_id  uuid,
    created_by          uuid,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT cash_count_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT cash_count_account_fk FOREIGN KEY (tenant_id, account_id)
        REFERENCES money_account (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT cash_count_booked_movement_fk FOREIGN KEY (tenant_id, booked_movement_id)
        REFERENCES account_movement (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT cash_count_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT cash_count_amount_ck CHECK (counted_amount >= 0),
    CONSTRAINT cash_count_difference_ck CHECK (difference = counted_amount - book_balance),
    -- A difference needs an explanation; a difference is the only thing that can be booked.
    CONSTRAINT cash_count_explanation_ck CHECK (
        (difference = 0 OR (explanation IS NOT NULL AND btrim(explanation) <> ''))
        AND (explanation IS NULL OR char_length(explanation) <= 1000)),
    CONSTRAINT cash_count_booked_ck CHECK (booked_movement_id IS NULL OR difference <> 0)
);

CREATE INDEX cash_count_tenant_idx ON cash_count (tenant_id);
CREATE INDEX cash_count_counted_idx ON cash_count (tenant_id, counted_at DESC);
CREATE UNIQUE INDEX cash_count_booked_movement_uq ON cash_count (tenant_id, booked_movement_id)
    WHERE booked_movement_id IS NOT NULL;

ALTER TABLE cash_count ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cash_count
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- booked_movement_id goes from null to a value once; nothing else changes. SQLSTATE 23514.
CREATE FUNCTION cash_count_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.account_id <> OLD.account_id
       OR NEW.counted_at <> OLD.counted_at OR NEW.counted_on <> OLD.counted_on
       OR NEW.counted_amount <> OLD.counted_amount OR NEW.book_balance <> OLD.book_balance
       OR NEW.difference <> OLD.difference OR NEW.explanation IS DISTINCT FROM OLD.explanation
       OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'cash_count_guard: only the booked movement can be written'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'cash_count_guard';
    END IF;
    IF OLD.booked_movement_id IS NOT NULL OR NEW.booked_movement_id IS NULL THEN
        RAISE EXCEPTION 'cash_count_guard: the difference is booked once'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'cash_count_guard';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER cash_count_guard BEFORE UPDATE ON cash_count
    FOR EACH ROW EXECUTE FUNCTION cash_count_guard();

REVOKE UPDATE, DELETE, TRUNCATE ON cash_count FROM nadel_app;
GRANT UPDATE (booked_movement_id) ON cash_count TO nadel_app;

-- ---- bank_reconciliation

-- One row per calendar month (its first day): the four figures of the bank statement and the state of the comparison.
-- NOT_RECONCILED -> RECONCILED (confirmed) -> NEEDS_RECHECK (a bank movement dated in the month was added) ->
-- RECONCILED. The row is created when the statement is entered.
CREATE TABLE bank_reconciliation (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    month                 date          NOT NULL,
    statement_opening     numeric(14,2) NOT NULL,
    statement_incoming    numeric(14,2) NOT NULL,
    statement_outgoing    numeric(14,2) NOT NULL,
    statement_closing     numeric(14,2) NOT NULL,
    status                text          NOT NULL DEFAULT 'NOT_RECONCILED',
    reconciled_by         uuid,
    reconciled_at         timestamptz,
    reopened_movement_id  uuid,
    reopened_at           timestamptz,
    created_by            uuid,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT bank_reconciliation_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT bank_reconciliation_tenant_month_uq UNIQUE (tenant_id, month),
    CONSTRAINT bank_reconciliation_reconciled_by_fk FOREIGN KEY (tenant_id, reconciled_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT bank_reconciliation_reopened_movement_fk FOREIGN KEY (tenant_id, reopened_movement_id)
        REFERENCES account_movement (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT bank_reconciliation_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT bank_reconciliation_month_ck CHECK (month = date_trunc('month', month)::date),
    CONSTRAINT bank_reconciliation_status_ck CHECK (status IN ('NOT_RECONCILED', 'RECONCILED', 'NEEDS_RECHECK')),
    CONSTRAINT bank_reconciliation_figures_ck CHECK (
        statement_incoming >= 0 AND statement_outgoing >= 0
        AND statement_opening + statement_incoming - statement_outgoing = statement_closing),
    CONSTRAINT bank_reconciliation_state_ck CHECK (
        CASE status
            WHEN 'NOT_RECONCILED' THEN reconciled_by IS NULL AND reconciled_at IS NULL
                AND reopened_movement_id IS NULL AND reopened_at IS NULL
            WHEN 'RECONCILED' THEN reconciled_by IS NOT NULL AND reconciled_at IS NOT NULL
            ELSE reopened_at IS NOT NULL
        END)
);

CREATE INDEX bank_reconciliation_tenant_idx ON bank_reconciliation (tenant_id);

ALTER TABLE bank_reconciliation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bank_reconciliation
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The status machine and what may change. SQLSTATE 23514. A RECONCILED month keeps its statement figures; the only way
-- out of it is NEEDS_RECHECK. NOT_RECONCILED is only the start state.
CREATE FUNCTION bank_reconciliation_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.month <> OLD.month
       OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'bank_reconciliation_guard: the month and the owner of a reconciliation do not change'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'bank_reconciliation_guard';
    END IF;
    IF NOT (NEW.status = OLD.status
            OR (OLD.status = 'NOT_RECONCILED' AND NEW.status = 'RECONCILED')
            OR (OLD.status = 'RECONCILED' AND NEW.status = 'NEEDS_RECHECK')
            OR (OLD.status = 'NEEDS_RECHECK' AND NEW.status = 'RECONCILED')) THEN
        RAISE EXCEPTION 'bank_reconciliation_guard: % to % is not a valid status change', OLD.status, NEW.status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'bank_reconciliation_guard';
    END IF;
    IF OLD.status = 'RECONCILED' AND (NEW.statement_opening <> OLD.statement_opening
            OR NEW.statement_incoming <> OLD.statement_incoming OR NEW.statement_outgoing <> OLD.statement_outgoing
            OR NEW.statement_closing <> OLD.statement_closing) THEN
        RAISE EXCEPTION 'bank_reconciliation_guard: a reconciled month keeps its statement'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'bank_reconciliation_guard';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bank_reconciliation_guard BEFORE UPDATE ON bank_reconciliation
    FOR EACH ROW EXECUTE FUNCTION bank_reconciliation_guard();

REVOKE UPDATE, DELETE, TRUNCATE ON bank_reconciliation FROM nadel_app;
GRANT UPDATE (statement_opening, statement_incoming, statement_outgoing, statement_closing, status, reconciled_by,
              reconciled_at, reopened_movement_id, reopened_at) ON bank_reconciliation TO nadel_app;

-- ---- seed_treasury

-- The two accounts and the seeded categories of a cooperative. Deterministic ids (md5 of the tenant and a label), so a
-- second run changes nothing; a renamed or archived category keeps its edits. Not SECURITY DEFINER (see seed_charge_types
-- in V10): the application calls it under its own tenant, the migration as the owner.
CREATE FUNCTION seed_treasury(p_tenant uuid) RETURNS void
    LANGUAGE sql
AS $$
    INSERT INTO money_account (id, tenant_id, kind)
    VALUES (md5(p_tenant::text || ':ACCOUNT:CASH')::uuid, p_tenant, 'CASH'),
           (md5(p_tenant::text || ':ACCOUNT:BANK')::uuid, p_tenant, 'BANK')
    ON CONFLICT DO NOTHING;

    INSERT INTO expense_category (id, tenant_id, kind, system_code, sort_order)
    SELECT md5(p_tenant::text || ':CATEGORY:' || c.kind || ':' || c.code)::uuid, p_tenant, c.kind, c.code, c.sort_order
    FROM (VALUES
        ('EXPENSE', 'ELECTRICITY_SUPPLIER', 1), ('EXPENSE', 'WATER', 2), ('EXPENSE', 'PAYROLL_TAXES', 3),
        ('EXPENSE', 'NETWORK_REPAIR', 4), ('EXPENSE', 'MATERIALS', 5), ('EXPENSE', 'WASTE', 6),
        ('EXPENSE', 'BANK_FEES', 7), ('EXPENSE', 'TAXES_FEES', 8), ('EXPENSE', 'OFFICE_COMMS', 9),
        ('EXPENSE', 'OTHER', 10),
        ('INCOME', 'BANK_INTEREST', 1), ('INCOME', 'GRANTS', 2), ('INCOME', 'SALES', 3), ('INCOME', 'OTHER', 4)
    ) AS c(kind, code, sort_order)
    ON CONFLICT DO NOTHING;
$$;

GRANT EXECUTE ON FUNCTION seed_treasury(uuid) TO nadel_app;

-- ---- CASHBOOK_VIEW, CASHBOOK_ENTER, CASHBOOK_APPROVE

-- V29 body plus (CHAIRMAN, CASHBOOK_VIEW/ENTER/APPROVE), (TREASURER, CASHBOOK_VIEW), (TREASURER, CASHBOOK_ENTER) and a
-- seed_treasury call. Member and custom roles get nothing.
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
        ('CHAIRMAN', 'CASHBOOK_VIEW'), ('CHAIRMAN', 'CASHBOOK_ENTER'), ('CHAIRMAN', 'CASHBOOK_APPROVE'),
        ('TREASURER', 'MEMBERS_VIEW'), ('TREASURER', 'PLOTS_VIEW'), ('TREASURER', 'AUDIT_VIEW'),
        ('TREASURER', 'TARIFFS_VIEW'), ('TREASURER', 'METERS_VIEW'), ('TREASURER', 'CHARGES_VIEW'),
        ('TREASURER', 'REPORTS_VIEW'),
        ('TREASURER', 'PAYMENTS_VIEW'), ('TREASURER', 'PAYMENTS_ENTER'),
        ('TREASURER', 'INVOICES_ISSUE'),
        ('TREASURER', 'CASHBOOK_VIEW'), ('TREASURER', 'CASHBOOK_ENTER')
    ) AS m(code, permission)
    ON CONFLICT DO NOTHING;

    SELECT seed_charge_types(p_tenant);
    SELECT seed_treasury(p_tenant);
$$;

GRANT EXECUTE ON FUNCTION seed_base_roles(uuid) TO nadel_app;

-- Existing cooperatives: targeted inserts (idempotent), never a re-run of seed_base_roles (V13 explains why), and the
-- accounts and categories.
INSERT INTO role_permission (tenant_id, role_id, permission)
SELECT r.tenant_id, r.id, p.permission
FROM role r
JOIN (VALUES
    ('CHAIRMAN', 'CASHBOOK_VIEW'), ('CHAIRMAN', 'CASHBOOK_ENTER'), ('CHAIRMAN', 'CASHBOOK_APPROVE'),
    ('TREASURER', 'CASHBOOK_VIEW'), ('TREASURER', 'CASHBOOK_ENTER')
) AS p(base_code, permission) ON p.base_code = r.base_code
ON CONFLICT DO NOTHING;

SELECT seed_treasury(id) FROM tenant;

-- ---- backfill of the payment movements

-- The one sanctioned migration-time read of the payment table by the treasury module (ADR 0019): every payment row that
-- exists when the feature is installed gets its journal movement. A PAYMENT row becomes a MEMBER_PAYMENT movement on
-- its received_on date in the row's billing month, on the cash or bank account of its source. A PAYMENT_REVERSAL row
-- becomes a movement of the negated amount on the day it was made (Kyiv time) in greatest(the row's billing month, the
-- month of that day) - the month openForBooking(that day) gave at the time -, on the account of the movement it reverses
-- (reverses_movement_id). Runs as the owner. ON CONFLICT DO NOTHING on the payment_row_id key keeps a re-run harmless;
-- ADR 0010 deploys with downtime, so no old instance writes payments after this migration.
-- BACKFILL BEGIN
INSERT INTO account_movement (tenant_id, account_id, kind, amount, moved_on, booking_month, source_type, source_id,
                              payment_row_id, created_by, created_at)
SELECT p.tenant_id, md5(p.tenant_id::text || ':ACCOUNT:' || p.source)::uuid, 'MEMBER_PAYMENT', p.amount, p.received_on,
       bm.month, 'PAYMENT', p.id, p.id, p.created_by, p.created_at
FROM payment p
JOIN billing_month bm ON bm.tenant_id = p.tenant_id AND bm.id = p.billing_month_id
WHERE p.kind = 'PAYMENT'
ON CONFLICT DO NOTHING;

INSERT INTO account_movement (tenant_id, account_id, kind, amount, moved_on, booking_month, source_type, source_id,
                              payment_row_id, reverses_movement_id, reason, created_by, created_at)
SELECT p.tenant_id, o.account_id, 'MEMBER_PAYMENT', p.amount, d.day,
       greatest(bm.month, date_trunc('month', d.day)::date), 'PAYMENT', p.id, p.id, o.id, p.reason, p.created_by,
       p.created_at
FROM payment p
JOIN billing_month bm ON bm.tenant_id = p.tenant_id AND bm.id = p.billing_month_id
JOIN account_movement o ON o.tenant_id = p.tenant_id AND o.payment_row_id = p.reverses_payment_id
CROSS JOIN LATERAL (SELECT (p.created_at AT TIME ZONE 'Europe/Kyiv')::date AS day) d
WHERE p.kind = 'PAYMENT_REVERSAL'
ON CONFLICT DO NOTHING;
-- BACKFILL END
