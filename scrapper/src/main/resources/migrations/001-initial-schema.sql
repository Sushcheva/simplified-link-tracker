-- Based on the original link-tracker links table and subscriptions.tags (JSONB).
-- The simplified application has one shared list; Telegram chats are not needed.
CREATE TABLE links (
    id BIGSERIAL PRIMARY KEY,
    url TEXT NOT NULL UNIQUE,
    title VARCHAR(120) NOT NULL,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(tags) = 'array'),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_check_time TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ,
    next_check_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error TEXT
);
CREATE INDEX links_due_idx ON links (next_check_at, id) WHERE enabled;

CREATE TABLE link_updates (
    id BIGSERIAL PRIMARY KEY,
    link_id BIGINT NOT NULL REFERENCES links(id) ON DELETE CASCADE,
    description TEXT NOT NULL,
    remote_updated_at TIMESTAMPTZ NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (link_id, remote_updated_at)
);
CREATE INDEX link_updates_recent_idx ON link_updates (detected_at DESC, id DESC);
