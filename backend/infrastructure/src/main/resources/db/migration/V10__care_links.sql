-- Remote caregiver mode: the person helped creates an invite code with the permissions they agree to;
-- the caregiver enters it. Either side can end the link. Events record everything done through it.
CREATE TABLE care_links (
    id              UUID PRIMARY KEY,
    receiver_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    caregiver_id    UUID        REFERENCES users (id) ON DELETE CASCADE,
    permissions     TEXT[]      NOT NULL DEFAULT '{}',
    status          TEXT        NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED')),
    code_hash       TEXT        UNIQUE,
    code_expires_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at     TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    CHECK (caregiver_id IS NULL OR caregiver_id <> receiver_id),
    CHECK (status <> 'ACTIVE' OR caregiver_id IS NOT NULL)
);
CREATE INDEX care_links_receiver ON care_links (receiver_id) WHERE status <> 'REVOKED';
CREATE INDEX care_links_caregiver ON care_links (caregiver_id) WHERE status <> 'REVOKED';

CREATE TABLE care_events (
    id       BIGSERIAL PRIMARY KEY,
    link_id  UUID        NOT NULL REFERENCES care_links (id) ON DELETE CASCADE,
    actor_id UUID        REFERENCES users (id) ON DELETE SET NULL,
    action   TEXT        NOT NULL,
    details  JSONB       NOT NULL DEFAULT '{}',
    at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX care_events_link ON care_events (link_id, id DESC);
