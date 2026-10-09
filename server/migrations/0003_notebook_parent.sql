-- A notebook may have one parent, or none. parent_id is metadata on this
-- row. It does not merge ink logs: each notebook keeps its own log.
-- Null is top-level. A client that lists notebooks flat can ignore it.
-- A push that omits parent_id leaves the stored parent in place.

ALTER TABLE notebooks ADD COLUMN parent_id uuid;
