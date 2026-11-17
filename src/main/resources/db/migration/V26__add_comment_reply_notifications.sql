-- Replies notify the authors above them in the thread (COMMENT_REPLY). NEW_COMMENT now goes only to the case's
-- reporter, for top-level comments.
ALTER TABLE notifications
    DROP CONSTRAINT ck_notifications_type,
    DROP CONSTRAINT ck_notifications_comment,
    ADD CONSTRAINT ck_notifications_type CHECK (type IN ('NEW_COMMENT', 'COMMENT_REPLY', 'CASE_STATUS_CHANGED')),
    ADD CONSTRAINT ck_notifications_comment
        CHECK ((type IN ('NEW_COMMENT', 'COMMENT_REPLY')) = (comment_id IS NOT NULL));
