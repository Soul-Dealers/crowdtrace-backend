-- Rate limit counters. Deliberately not a JPA entity: the whole mechanism is a
-- single atomic upsert, and an entity would invite read-modify-write access that
-- races under concurrency.
--
-- subject_key is an HMAC of the client address or normalised email, never the
-- raw value, so this table cannot be mined for who uses the service.
CREATE TABLE rate_limit_bucket (
    scope             VARCHAR(16)  NOT NULL,
    action            VARCHAR(64)  NOT NULL,
    subject_key       BYTEA        NOT NULL,
    request_count     INTEGER      NOT NULL,
    window_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    window_ends_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    purge_after       TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (scope, action, subject_key)
);

CREATE INDEX idx_rate_limit_bucket_purge ON rate_limit_bucket (purge_after);
