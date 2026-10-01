-- Self-hosted crash reports from the Android app (opt-in). Messages are scrubbed on the phone and again here.
CREATE TABLE crash_reports (
    id             UUID PRIMARY KEY,
    user_id        UUID        REFERENCES users (id) ON DELETE CASCADE,
    install_id     TEXT,
    fingerprint    TEXT        NOT NULL,
    exception      TEXT        NOT NULL,
    message        TEXT,
    stacktrace     TEXT        NOT NULL,
    thread         TEXT,
    app_version    TEXT,
    android_sdk    INT,
    device_model   TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX crash_reports_fingerprint ON crash_reports (fingerprint, occurred_at DESC);
CREATE INDEX crash_reports_user ON crash_reports (user_id, occurred_at DESC);
