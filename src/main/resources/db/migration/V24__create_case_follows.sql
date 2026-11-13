-- CT-026: a registered user follows a public case. The primary key makes duplicate follows impossible.
CREATE TABLE case_follows
(
    user_id    BIGINT                      NOT NULL,
    case_id    BIGINT                      NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
    CONSTRAINT pk_case_follows PRIMARY KEY (user_id, case_id),
    CONSTRAINT fk_case_follows_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_case_follows_case FOREIGN KEY (case_id) REFERENCES cases (id)
);

-- A user's follow list reads newest first.
CREATE INDEX idx_case_follows_user_recent ON case_follows (user_id, created_at DESC, case_id DESC);
