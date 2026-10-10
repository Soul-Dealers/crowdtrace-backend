-- Review decisions and report resolutions are history, like audit events.
-- case_reviews is append-only: a changed decision is a new review row.
-- content_reports allows exactly one change, OPEN -> RESOLVED, and is never deleted.
-- These triggers also stop a later ON DELETE CASCADE / SET NULL on the users or cases
-- foreign keys from silently rewriting history: the cascaded change is rejected here.
CREATE FUNCTION reject_case_review_mutation()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'case_reviews is append-only; record a new review instead'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER case_reviews_reject_update_or_delete
    BEFORE UPDATE OR DELETE ON case_reviews
    FOR EACH ROW
    EXECUTE FUNCTION reject_case_review_mutation();

CREATE TRIGGER case_reviews_reject_truncate
    BEFORE TRUNCATE ON case_reviews
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_case_review_mutation();

CREATE FUNCTION guard_content_report_resolution()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
        AND OLD.status = 'OPEN' AND NEW.status = 'RESOLVED'
        AND NEW.id = OLD.id
        AND NEW.comment_id = OLD.comment_id
        AND NEW.reporter_id = OLD.reporter_id
        AND NEW.reason = OLD.reason
        AND NEW.details IS NOT DISTINCT FROM OLD.details
        AND NEW.created_at = OLD.created_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'content_reports only allows resolving an open report'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER content_reports_guard_update_or_delete
    BEFORE UPDATE OR DELETE ON content_reports
    FOR EACH ROW
    EXECUTE FUNCTION guard_content_report_resolution();

CREATE TRIGGER content_reports_reject_truncate
    BEFORE TRUNCATE ON content_reports
    FOR EACH STATEMENT
    EXECUTE FUNCTION guard_content_report_resolution();

ALTER TABLE content_reports
    ADD CONSTRAINT content_reports_resolved_after_created_check CHECK (resolved_at >= created_at);

-- The application caps compact JSON at 4096 UTF-8 bytes (AuditMetadataSerializer). Postgres
-- measures its normalized jsonb text, which adds a space after every ':' and ',', so the same
-- payload can be up to 1.5x larger here. The database keeps a 2x backstop so it never rejects
-- metadata the application accepted.
ALTER TABLE audit_events DROP CONSTRAINT audit_events_metadata_size_check;
ALTER TABLE audit_events
    ADD CONSTRAINT audit_events_metadata_size_check CHECK (octet_length(metadata::text) <= 8192);

-- Only human review exists today; writers need not repeat the constant.
ALTER TABLE case_reviews ALTER COLUMN review_source SET DEFAULT 'HUMAN';

-- uq_content_reports_open_reporter (comment_id, reporter_id) WHERE status = 'OPEN' already
-- serves open-report lookups by comment.
DROP INDEX idx_content_reports_open_comment;
-- No query filters audit history by action or scans it by time alone yet. Add these back with
-- the admin audit views (CT-035) if their plans need them.
DROP INDEX idx_audit_events_action;
DROP INDEX idx_audit_events_occurred;
