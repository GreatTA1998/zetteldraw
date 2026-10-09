-- A link connects two pages. It stores the two page ids and copies no ink.
-- Deleting either page tombstones the link. There is no leftover row for a
-- missing page. Same last-write-wins rule as a notebook, and no conflict copy.

CREATE TABLE page_links (
    id          uuid PRIMARY KEY,
    source_id   uuid NOT NULL,
    target_id   uuid NOT NULL,
    created_at  bigint NOT NULL,
    updated_at  bigint NOT NULL,
    rev         bigint NOT NULL,
    deleted_at  bigint
);

CREATE INDEX page_links_rev ON page_links (rev);
