-- Device must Prepare transfer (or web lost-device ack) before Hand off.
-- Server-only; does not bump sync schema_version.

ALTER TABLE live_writers
  ADD COLUMN IF NOT EXISTS transfer_prepared_at bigint,
  ADD COLUMN IF NOT EXISTS lost_device_ack_at bigint;
