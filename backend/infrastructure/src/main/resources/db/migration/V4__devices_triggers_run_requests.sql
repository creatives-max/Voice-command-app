-- Phones signed in to an account; they long-poll for run requests.
CREATE TABLE devices (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         TEXT        NOT NULL,
    platform     TEXT        NOT NULL DEFAULT 'android',
    app_version  TEXT,
    remote_runs  BOOLEAN     NOT NULL DEFAULT TRUE,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX devices_user_seen ON devices (user_id, last_seen_at DESC);

-- When a flow runs by itself: when its app opens (evaluated on the phone) or on a schedule (backend).
CREATE TABLE flow_triggers (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    flow_id     UUID        NOT NULL REFERENCES flows (id) ON DELETE CASCADE,
    type        TEXT        NOT NULL CHECK (type IN ('APP_OPEN', 'SCHEDULE')),
    enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    cron        TEXT,
    timezone    TEXT,
    device_id   UUID        REFERENCES devices (id) ON DELETE SET NULL,
    next_run_at TIMESTAMPTZ,
    last_run_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (type <> 'SCHEDULE' OR (cron IS NOT NULL AND timezone IS NOT NULL))
);
CREATE INDEX flow_triggers_flow ON flow_triggers (flow_id);
CREATE INDEX flow_triggers_due ON flow_triggers (next_run_at) WHERE enabled AND type = 'SCHEDULE';

-- A request for a phone to run a flow (manual "run now", schedule, or app-open), with its live log.
CREATE TABLE run_requests (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    flow_id      UUID        REFERENCES flows (id) ON DELETE SET NULL,
    flow_name    TEXT        NOT NULL,
    app_package  TEXT        NOT NULL,
    device_id    UUID        REFERENCES devices (id) ON DELETE SET NULL,
    trigger_id   UUID        REFERENCES flow_triggers (id) ON DELETE SET NULL,
    source       TEXT        NOT NULL CHECK (source IN ('MANUAL', 'SCHEDULE', 'APP_OPEN')),
    status       TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX run_requests_user_created ON run_requests (user_id, created_at DESC);
CREATE INDEX run_requests_device_pending ON run_requests (device_id, status) WHERE status IN ('PENDING', 'CANCEL_REQUESTED');

CREATE TABLE run_request_events (
    id         BIGSERIAL PRIMARY KEY,
    request_id UUID        NOT NULL REFERENCES run_requests (id) ON DELETE CASCADE,
    at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    kind       TEXT        NOT NULL,
    message    TEXT        NOT NULL
);
CREATE INDEX run_request_events_request ON run_request_events (request_id, id);
