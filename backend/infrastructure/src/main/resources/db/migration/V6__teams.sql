-- Organizations (teams) with role-based membership; flows can belong to an organization.
CREATE TABLE organizations (
    id         UUID PRIMARY KEY,
    name       TEXT        NOT NULL,
    created_by UUID        REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE memberships (
    org_id     UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role       TEXT        NOT NULL CHECK (role IN ('ADMIN', 'EDITOR', 'VIEWER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, user_id)
);
CREATE INDEX memberships_user ON memberships (user_id);

CREATE TABLE invitations (
    id          UUID PRIMARY KEY,
    org_id      UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    email       TEXT        NOT NULL,
    role        TEXT        NOT NULL CHECK (role IN ('ADMIN', 'EDITOR', 'VIEWER')),
    token_hash  TEXT        NOT NULL UNIQUE,
    invited_by  UUID        REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX invitations_org ON invitations (org_id);

ALTER TABLE flows ADD COLUMN org_id UUID REFERENCES organizations (id) ON DELETE CASCADE;
CREATE INDEX flows_org_package ON flows (org_id, app_package) WHERE deleted_at IS NULL AND org_id IS NOT NULL;

-- Who did what in an organization.
CREATE TABLE audit_logs (
    id               BIGSERIAL PRIMARY KEY,
    org_id           UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    actor_user_id    UUID        REFERENCES users (id) ON DELETE SET NULL,
    actor_api_key_id UUID,
    action           TEXT        NOT NULL,
    target_type      TEXT        NOT NULL,
    target_id        TEXT,
    details          JSONB       NOT NULL DEFAULT '{}',
    at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_logs_org_at ON audit_logs (org_id, at DESC);

-- API keys for third parties; only a SHA-256 hash of the secret is stored.
CREATE TABLE api_keys (
    id                    UUID PRIMARY KEY,
    org_id                UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    name                  TEXT        NOT NULL,
    prefix                TEXT        NOT NULL,
    key_hash              TEXT        NOT NULL UNIQUE,
    scopes                TEXT[]      NOT NULL,
    rate_limit_per_minute INT         NOT NULL,
    created_by            UUID        REFERENCES users (id) ON DELETE SET NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at          TIMESTAMPTZ,
    revoked_at            TIMESTAMPTZ
);
CREATE INDEX api_keys_org ON api_keys (org_id);

-- Outgoing webhooks on flow and run events, signed with HMAC-SHA256, retried with backoff.
CREATE TABLE webhooks (
    id         UUID PRIMARY KEY,
    org_id     UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    url        TEXT        NOT NULL,
    secret     TEXT        NOT NULL,
    events     TEXT[]      NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by UUID        REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX webhooks_org ON webhooks (org_id);

CREATE TABLE webhook_deliveries (
    id               UUID PRIMARY KEY,
    webhook_id       UUID        NOT NULL REFERENCES webhooks (id) ON DELETE CASCADE,
    event_id         TEXT        NOT NULL,
    event_type       TEXT        NOT NULL,
    payload          JSONB       NOT NULL,
    status           TEXT        NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    attempts         INT         NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_status_code INT,
    last_error       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at     TIMESTAMPTZ,
    UNIQUE (webhook_id, event_id)
);
CREATE INDEX webhook_deliveries_due ON webhook_deliveries (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX webhook_deliveries_hook ON webhook_deliveries (webhook_id, created_at DESC);
