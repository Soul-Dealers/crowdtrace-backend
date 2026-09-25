-- The nightly purge scans otp by expiry, and consumeOtp compares against it.
-- revoked_tokens already has the equivalent index (idx_revoked_tokens_expires_at).
CREATE INDEX idx_otp_expired_at ON otp (expired_at);
