-- CT-019: tell reporters when a comment they reported is removed. It names the case only, so comment_id stays null.
ALTER TABLE notifications
    DROP CONSTRAINT ck_notifications_type,
    ADD CONSTRAINT ck_notifications_type
        CHECK (type IN ('NEW_COMMENT', 'COMMENT_REPLY', 'CASE_STATUS_CHANGED', 'COMMENT_REMOVED'));
