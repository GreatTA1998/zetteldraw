-- Server-only account tables. These do NOT bump the shared sync schema_version
-- (Room stays at 4). Applied from account-migrations/ on startup.

CREATE TABLE IF NOT EXISTS users (
    id          text PRIMARY KEY,   -- Google `sub`
    email       text,
    created_at  bigint NOT NULL
);

-- Singleton: first Google sign-in claims every unowned library row.
CREATE TABLE IF NOT EXISTS library_claim (
    id          integer PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    user_id     text NOT NULL REFERENCES users (id),
    claimed_at  bigint NOT NULL
);

-- Rows without an owner are the legacy shared (unowned) library.
CREATE TABLE IF NOT EXISTS row_owners (
    table_name  text NOT NULL,
    row_id      uuid NOT NULL,
    user_id     text NOT NULL REFERENCES users (id),
    PRIMARY KEY (table_name, row_id)
);

CREATE INDEX IF NOT EXISTS row_owners_user ON row_owners (user_id, table_name);

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
    EXECUTE 'ALTER TABLE users ENABLE ROW LEVEL SECURITY';
    EXECUTE 'ALTER TABLE library_claim ENABLE ROW LEVEL SECURITY';
    EXECUTE 'ALTER TABLE row_owners ENABLE ROW LEVEL SECURITY';
    EXECUTE 'REVOKE ALL ON TABLE users, library_claim, row_owners FROM anon, authenticated';
  END IF;
END $$;
