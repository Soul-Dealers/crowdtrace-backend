-- The verified badge a user holds, stored on the user row.
--
-- A user holds at most one badge (V10 allows one active request per user), so the badge
-- is a single nullable column rather than something recomputed from the request history.
-- It exists so that phase-4 discovery can read and filter by badge type without touching
-- verification_requests. It is written in the same transaction as the decision that
-- changes it (approve and grant set it, revoke clears it) and is a trust signal only,
-- never a permission.
ALTER TABLE users ADD COLUMN badge_type VARCHAR(32);

ALTER TABLE users ADD CONSTRAINT users_badge_type_check
    CHECK (badge_type IN ('POLICE', 'NGO', 'SUBJECT_MATTER_EXPERT'));

CREATE INDEX idx_users_badge_type ON users (badge_type);

-- Backfill from the decisions already recorded. Under V10 a user has at most one
-- APPROVED request, so this join matches at most one row per user and is deterministic.
UPDATE users
   SET badge_type = request.verification_type
  FROM verification_requests request
 WHERE request.user_id = users.id
   AND request.status = 'APPROVED';
