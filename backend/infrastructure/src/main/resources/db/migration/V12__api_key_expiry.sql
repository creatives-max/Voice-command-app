-- API keys can expire; rotating a key replaces its secret (and prefix) in place.
ALTER TABLE api_keys ADD COLUMN expires_at TIMESTAMPTZ;
ALTER TABLE api_keys ADD COLUMN rotated_at TIMESTAMPTZ;
