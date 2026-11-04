-- Feature 018, slice 4: the reason the chairman gives when booking the difference of a cash count («Оформити
-- різницю»). The booked line (account_movement, kind COUNT_DIFFERENCE) cannot carry it: account_movement.reason is
-- reserved for reversals (account_movement_reason_ck), so the count keeps it next to booked_movement_id.
-- Compatibility with the previous release (expand/contract): one new nullable column the previous release never reads.
--
-- booking_reason is written in the same UPDATE as booked_movement_id. The guard cash_count_guard (V32) needs no
-- change: it lets any column it does not list change, but only in the one update that takes booked_movement_id from
-- null to a value, so the reason is written once, with the booking, and never again.

ALTER TABLE cash_count ADD COLUMN booking_reason text;

ALTER TABLE cash_count ADD CONSTRAINT cash_count_booking_reason_ck CHECK (
    booking_reason IS NULL
    OR (booked_movement_id IS NOT NULL AND btrim(booking_reason) <> '' AND char_length(booking_reason) <= 500));

GRANT UPDATE (booking_reason) ON cash_count TO nadel_app;
