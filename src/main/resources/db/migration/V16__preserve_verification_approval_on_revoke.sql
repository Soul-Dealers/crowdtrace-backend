-- Revocation used to overwrite the approval (reviewer, notes, time). From here on it is
-- recorded separately. Rows revoked before V16 lost their approver; copy what remains.
-- A missing reviewed_at falls back to created_at. A missing reviewer is not fabricated,
-- so inconsistent legacy attribution fails the constraint below and needs investigation.
ALTER TABLE verification_requests
    ADD COLUMN revoked_by BIGINT,
    ADD COLUMN revoked_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN revocation_notes VARCHAR(2000);

UPDATE verification_requests
   SET revoked_by = reviewer_id,
       revoked_at = COALESCE(reviewed_at, created_at),
       revocation_notes = review_notes
 WHERE status = 'REVOKED';

ALTER TABLE verification_requests
    ADD CONSTRAINT fk_verification_requests_revoked_by
        FOREIGN KEY (revoked_by) REFERENCES users (id),
    ADD CONSTRAINT verification_requests_revocation_check CHECK (
        (status = 'REVOKED' AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL)
        OR (status <> 'REVOKED' AND revoked_by IS NULL AND revoked_at IS NULL
            AND revocation_notes IS NULL));
