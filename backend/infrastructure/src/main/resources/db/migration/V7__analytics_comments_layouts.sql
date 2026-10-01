-- Per-flow analytics read run history by the flows each screen used.
CREATE INDEX runs_screens_flows ON runs USING GIN (screens jsonb_path_ops);
CREATE INDEX runs_started ON runs (started_at);

-- Comments on a flow (optionally on one step), for collaboration on the dashboard.
CREATE TABLE flow_comments (
    id          UUID PRIMARY KEY,
    flow_id     UUID        NOT NULL REFERENCES flows (id) ON DELETE CASCADE,
    user_id     UUID        REFERENCES users (id) ON DELETE SET NULL,
    step_id     TEXT,
    body        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at   TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    resolved_by UUID        REFERENCES users (id) ON DELETE SET NULL
);
CREATE INDEX flow_comments_flow ON flow_comments (flow_id, created_at);

-- Node positions of the visual builder's canvas (shared by everyone who edits the flow).
CREATE TABLE flow_layouts (
    flow_id    UUID PRIMARY KEY REFERENCES flows (id) ON DELETE CASCADE,
    positions  JSONB       NOT NULL,
    updated_by UUID        REFERENCES users (id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
