-- When the password was last changed (shown on the dashboard's security page).
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMPTZ;
