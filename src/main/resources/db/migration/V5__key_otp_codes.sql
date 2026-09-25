-- OTP codes are now stored as HMAC-SHA-256 digests keyed with a server-side
-- secret, with the email and purpose bound into the authenticated input.
-- Existing rows hold plaintext that can never match a digest, so they are
-- discarded; any in-flight code simply has to be re-requested. Safe here
-- because no production deployment exists.
TRUNCATE TABLE otp;

-- Codes are never looked up by value (only by email + type), and indexing a
-- credential digest serves no purpose.
DROP INDEX IF EXISTS idx_otp_code;
