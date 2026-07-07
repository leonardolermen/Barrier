CREATE TABLE watchlist_entries (
    id           UUID PRIMARY KEY,
    source       VARCHAR(40)  NOT NULL,
    entry_type   VARCHAR(20)  NOT NULL,
    document     VARCHAR(20),
    name         VARCHAR(300) NOT NULL,
    detail       VARCHAR(400),
    list_version VARCHAR(40)  NOT NULL,
    imported_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_watchlist_entries_document ON watchlist_entries (document);
CREATE INDEX idx_watchlist_entries_source ON watchlist_entries (source);
