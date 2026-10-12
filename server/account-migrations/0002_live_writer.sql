-- Single live writer (source of truth) per Google user.
-- Server-only; does not bump sync schema_version.

CREATE TABLE IF NOT EXISTS live_writers (
    user_id        text PRIMARY KEY REFERENCES users (id),
    device_id      text NOT NULL,
    device_label   text NOT NULL DEFAULT '',
    claimed_at     bigint NOT NULL,
    last_seen_at   bigint NOT NULL,
    -- When set and > now, the previous device has handed off; a new device may claim.
    handoff_until  bigint
);

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
    EXECUTE 'ALTER TABLE live_writers ENABLE ROW LEVEL SECURITY';
    EXECUTE 'REVOKE ALL ON TABLE live_writers FROM anon, authenticated';
  END IF;
END $$;
