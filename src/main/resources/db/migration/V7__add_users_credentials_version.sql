-- Incremented whenever credentials change. The value is signed into each JWT
-- and compared for exact equality on every request, so a password reset makes
-- every previously issued token invalid immediately.
ALTER TABLE users ADD COLUMN credentials_version INTEGER NOT NULL DEFAULT 0;
