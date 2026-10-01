-- Users, reusable profiles and the versioned flow library.

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         TEXT        NOT NULL,
    name          TEXT,
    password_hash TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email_unique ON users (lower(email));

CREATE TABLE profiles (
    user_id       UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    full_name     TEXT,
    email         TEXT,
    phone         TEXT,
    address_line  TEXT,
    city          TEXT,
    state         TEXT,
    pincode       TEXT,
    date_of_birth TEXT,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE flows (
    id               UUID PRIMARY KEY,
    user_id          UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    app_package      TEXT        NOT NULL,
    name             TEXT        NOT NULL,
    screen_signature TEXT        NOT NULL,
    current_version  INT         NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ
);
CREATE INDEX flows_user_package ON flows (user_id, app_package) WHERE deleted_at IS NULL;
CREATE INDEX flows_user_signature ON flows (user_id, app_package, md5(screen_signature)) WHERE deleted_at IS NULL;

-- Immutable history: every edit appends a row; flows.current_version points at the live one.
CREATE TABLE flow_versions (
    flow_id          UUID        NOT NULL REFERENCES flows (id) ON DELETE CASCADE,
    version          INT         NOT NULL,
    steps            JSONB       NOT NULL,
    screen_signature TEXT        NOT NULL,
    source           TEXT        NOT NULL,
    change_note      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (flow_id, version)
);
