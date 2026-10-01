-- Flow marketplace: published copies of flows (and starter templates), versioned, searchable and rated.
CREATE TABLE published_flows (
    id              UUID PRIMARY KEY,
    owner_id        UUID        REFERENCES users (id) ON DELETE CASCADE, -- NULL for built-in templates
    source_flow_id  UUID        REFERENCES flows (id) ON DELETE SET NULL,
    name            TEXT        NOT NULL,
    description     TEXT        NOT NULL DEFAULT '',
    app_package     TEXT        NOT NULL,
    category        TEXT        NOT NULL,
    tags            TEXT[]      NOT NULL DEFAULT '{}',
    is_template     BOOLEAN     NOT NULL DEFAULT FALSE,
    match_keywords  JSONB       NOT NULL DEFAULT '{}',
    latest_version  INT         NOT NULL,
    install_count   INT         NOT NULL DEFAULT 0,
    rating_sum      INT         NOT NULL DEFAULT 0,
    rating_count    INT         NOT NULL DEFAULT 0,
    search          TSVECTOR    NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    unpublished_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX published_flows_source ON published_flows (owner_id, source_flow_id) WHERE source_flow_id IS NOT NULL;
CREATE INDEX published_flows_search ON published_flows USING GIN (search);
CREATE INDEX published_flows_app ON published_flows (app_package) WHERE unpublished_at IS NULL;
CREATE INDEX published_flows_popular ON published_flows (install_count DESC) WHERE unpublished_at IS NULL;

CREATE TABLE published_flow_versions (
    published_id     UUID        NOT NULL REFERENCES published_flows (id) ON DELETE CASCADE,
    version          INT         NOT NULL,
    steps            JSONB       NOT NULL,
    screen_signature TEXT        NOT NULL,
    changelog        TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (published_id, version)
);

CREATE TABLE flow_ratings (
    published_id UUID        NOT NULL REFERENCES published_flows (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    stars        SMALLINT    NOT NULL CHECK (stars BETWEEN 1 AND 5),
    review       TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (published_id, user_id)
);

-- Imported flows remember where they came from, so the owner can update from newer versions.
ALTER TABLE flows ADD COLUMN source_published_id UUID REFERENCES published_flows (id) ON DELETE SET NULL;
ALTER TABLE flows ADD COLUMN source_version INT;
