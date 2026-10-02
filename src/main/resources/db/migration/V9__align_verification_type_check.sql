-- CT-010 renamed the badge vocabulary from IDENTITY / ORGANIZATION to
-- POLICE / NGO / SUBJECT_MATTER_EXPERT.
--
-- Any database whose verification_requests table was first created by Hibernate
-- auto-DDL carries a generated CHECK constraint listing the old values. Those
-- environments reject every insert with the new vocabulary, and `ddl-auto: validate`
-- does not notice: it compares tables, columns and types, never check constraints.
-- Measured on the dev database, where a valid submit returned 500 through
-- DataIntegrityViolationException.
--
-- Databases built purely from V1 have no such constraint, so the DROP is a no-op
-- there and the ADD pins the vocabulary everywhere from here on.
--
-- No data backfill: the dev and prod tables hold no rows written under the old
-- names. A deployment that does hold them must rewrite them before this runs,
-- or the ADD CONSTRAINT fails and the migration stops — which is the safe outcome.

ALTER TABLE verification_requests
    DROP CONSTRAINT IF EXISTS verification_requests_verification_type_check;

ALTER TABLE verification_requests
    ADD CONSTRAINT verification_requests_verification_type_check
        CHECK (verification_type IN ('POLICE', 'NGO', 'SUBJECT_MATTER_EXPERT'));
