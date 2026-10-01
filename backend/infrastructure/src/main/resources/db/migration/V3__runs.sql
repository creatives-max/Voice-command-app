-- Voice session history uploaded by the phone (no typed values are ever stored).
CREATE TABLE runs (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    app_package  TEXT        NOT NULL,
    started_at   TIMESTAMPTZ NOT NULL,
    ended_at     TIMESTAMPTZ NOT NULL,
    status       TEXT        NOT NULL,
    language     TEXT        NOT NULL,
    filled_count INT         NOT NULL,
    step_count   INT         NOT NULL,
    screens      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX runs_user_started ON runs (user_id, started_at DESC);
CREATE INDEX runs_user_app ON runs (user_id, app_package);
