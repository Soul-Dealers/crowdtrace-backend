-- CT-021: public case search. Indexes lead with review_status instead of being partial, because
-- Hibernate binds the enum as a parameter and a generic plan cannot match a partial predicate.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_cases_public_recent ON cases (review_status, approved_at DESC, id DESC);
CREATE INDEX idx_cases_public_region ON cases (review_status, region, approved_at DESC, id DESC);
CREATE INDEX idx_cases_public_outcome ON cases (review_status, case_status, approved_at DESC, id DESC);
CREATE INDEX idx_cases_public_age ON cases (review_status, age);
CREATE INDEX idx_cases_public_name_trgm ON cases USING gin (lower(full_name) gin_trgm_ops);

DROP INDEX idx_cases_public_status;
