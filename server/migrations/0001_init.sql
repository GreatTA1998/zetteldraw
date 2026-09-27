-- zetteldraw shared schema, version 1.
--
-- This migration set is the sync contract. The Android Room schema
-- (app/schemas/com.zetteldraw.penpoc.data.db.ZettelDatabase/<N>.json) mirrors
-- `notebooks` and `boards` column for column; SchemaContractTest fails the
-- Android build when they drift. schema_version == highest migration number.
--
-- Times are epoch milliseconds (bigint) on both sides. `rev` is assigned by
-- the server from one sequence shared by both tables, so a single cursor
-- orders every change. `position` sorts by byte order ("C" collation), which
-- is what SQLite's BINARY collation and the client's key generator assume.

CREATE SEQUENCE sync_rev;

CREATE TABLE notebooks (
    id          uuid PRIMARY KEY,
    title       text NOT NULL,
    position    text COLLATE "C" NOT NULL,
    created_at  bigint NOT NULL,
    updated_at  bigint NOT NULL,
    rev         bigint NOT NULL,
    deleted_at  bigint
);

CREATE INDEX notebooks_rev ON notebooks (rev);

-- notebook_id NULL = scratchpad. conflict_of NOT NULL = hidden conflict copy
-- of that board (the LWW loser, kept so ink is never silently dropped).
-- Ink bytes live in object storage at ink/<ink_hash>.
CREATE TABLE boards (
    id          uuid PRIMARY KEY,
    notebook_id uuid,
    position    text COLLATE "C" NOT NULL,
    ink_hash    text,
    ink_bytes   bigint NOT NULL DEFAULT 0,
    thumb_hash  text,
    conflict_of uuid,
    created_at  bigint NOT NULL,
    updated_at  bigint NOT NULL,
    rev         bigint NOT NULL,
    deleted_at  bigint
);

CREATE INDEX boards_rev ON boards (rev);
CREATE INDEX boards_live_order ON boards (notebook_id, position, id)
    WHERE deleted_at IS NULL AND conflict_of IS NULL;
