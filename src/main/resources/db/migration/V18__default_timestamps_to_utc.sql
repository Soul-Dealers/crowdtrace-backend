-- The application writes UTC wall-clock times (Clock.systemUTC()). A bare CURRENT_TIMESTAMP
-- default converts to the session time zone instead, so on a non-UTC server rows that use the
-- default would sort against application-written rows by the wrong offset. Pin every default
-- to UTC so it no longer depends on session configuration.
ALTER TABLE users
    ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
    ALTER COLUMN updated_at SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');
ALTER TABLE verification_requests
    ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');
ALTER TABLE cases
    ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
    ALTER COLUMN updated_at SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');
ALTER TABLE case_sensitive_details
    ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
    ALTER COLUMN updated_at SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');
ALTER TABLE case_files
    ALTER COLUMN uploaded_at SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');
ALTER TABLE case_consents
    ALTER COLUMN accepted_at SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');
ALTER TABLE case_duplicate_matches
    ALTER COLUMN detected_at SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');
ALTER TABLE case_reviews
    ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');
ALTER TABLE audit_events
    ALTER COLUMN occurred_at SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');
ALTER TABLE content_reports
    ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');
