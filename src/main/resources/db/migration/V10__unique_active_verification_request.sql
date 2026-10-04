-- One active verification request per user, enforced by the database.
--
-- A user holds at most one badge, so a user may have at most one active request across
-- all types. The service checks for an existing PENDING or APPROVED request before
-- inserting, but that is check-then-act: two concurrent submits, two concurrent grants,
-- or a submit racing a grant can all observe "nothing exists" and both insert (even for
-- different types). @Transactional does not help — the two transactions genuinely do not
-- see each other's uncommitted row — and neither does a single instance, since the
-- window exists per request thread.
--
-- "Active" is PENDING or APPROVED. A rejection or a revocation releases the slot, so a
-- user may apply again after being refused or having the badge withdrawn, which is the
-- behaviour the workflow already promises.
--
-- CREATE UNIQUE INDEX fails if the table already holds a user with two active requests.
-- That is the safe outcome: the migration stops and the duplicates must be resolved
-- deliberately rather than silently losing one of two conflicting decisions.
CREATE UNIQUE INDEX uq_verification_requests_active_user
    ON verification_requests (user_id)
    WHERE status IN ('PENDING', 'APPROVED');
