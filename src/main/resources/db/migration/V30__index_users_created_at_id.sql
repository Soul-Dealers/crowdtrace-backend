-- Seek index for the Super Admin user list (newest first, keyset on created_at, id).
CREATE INDEX idx_users_created_at_id ON users (created_at DESC, id DESC);
