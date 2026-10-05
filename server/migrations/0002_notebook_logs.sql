-- zetteldraw shared schema, version 2.
--
-- One append-only ink log per notebook, beside the per-page board blobs.
-- Migration does not delete boards. conflict_of is a hidden copy of a whole
-- log (last-write-wins shelves the other device's log; it does not tear a stroke).

CREATE TABLE notebook_logs (
    id           uuid PRIMARY KEY,
    notebook_id  uuid,
    ink_hash     text,
    ink_bytes    bigint NOT NULL DEFAULT 0,
    slice_height integer NOT NULL,
    conflict_of  uuid,
    created_at   bigint NOT NULL,
    updated_at   bigint NOT NULL,
    rev          bigint NOT NULL,
    deleted_at   bigint
);

CREATE INDEX notebook_logs_rev ON notebook_logs (rev);

-- Supabase grants new public tables to anon/authenticated. The sync server
-- connects as the table owner, which bypasses row security. Local Postgres
-- used by tests has neither role, so this is a no-op there.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
    EXECUTE 'ALTER TABLE notebook_logs ENABLE ROW LEVEL SECURITY';
    EXECUTE 'REVOKE ALL ON TABLE notebook_logs FROM anon, authenticated';
  END IF;
END $$;
