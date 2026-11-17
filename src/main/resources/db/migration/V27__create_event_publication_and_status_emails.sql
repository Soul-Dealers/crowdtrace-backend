-- CT-029: Spring Modulith's event publication registry (v2 schema, copied from spring-modulith-events-jdbc 2.0.7).
-- Each after-commit listener gets a row; a listener that throws leaves it FAILED for the retry job to resubmit.
-- serialized_event holds the id-only events, never comment text or account details.
CREATE TABLE IF NOT EXISTS event_publication
(
    id                     UUID                     NOT NULL,
    listener_id            TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);

-- One status email per lifecycle event. The row is claimed in the sending transaction, so a failed send rolls it
-- back and a retried event finds it and sends nothing. Holds no address or message content.
CREATE TABLE reporter_status_emails
(
    event_id VARCHAR(64)                 NOT NULL,
    sent_at  TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_reporter_status_emails PRIMARY KEY (event_id)
);
