-- Reports on marketplace listings (broken, unsafe, spam…). A listing reported by enough different people
-- is hidden from search until its owner publishes a fixed version (which clears the reports).
CREATE TABLE listing_reports (
    published_id UUID        NOT NULL REFERENCES published_flows (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reason       TEXT        NOT NULL CHECK (reason IN ('BROKEN', 'UNSAFE', 'SPAM', 'OTHER')),
    note         TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (published_id, user_id)
);

ALTER TABLE published_flows ADD COLUMN hidden_at TIMESTAMPTZ;
