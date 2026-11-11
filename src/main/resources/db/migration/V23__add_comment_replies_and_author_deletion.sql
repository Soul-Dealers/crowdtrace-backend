-- Nested replies and author deletion. Existing comments stay roots (parent NULL).
ALTER TABLE comments
    ADD COLUMN parent_comment_id BIGINT,
    ADD COLUMN deleted_at        TIMESTAMP WITHOUT TIME ZONE,
    ADD CONSTRAINT uq_comments_id_case UNIQUE (id, case_id),
    ADD CONSTRAINT ck_comments_not_own_parent CHECK (parent_comment_id IS NULL OR parent_comment_id <> id),
    -- An author-deleted comment keeps only the sentinel, never its text.
    ADD CONSTRAINT ck_comments_deleted_body CHECK (deleted_at IS NULL OR body = '[deleted]');

-- A reply must belong to the same case as its parent.
ALTER TABLE comments
    ADD CONSTRAINT fk_comments_parent_same_case FOREIGN KEY (parent_comment_id, case_id)
        REFERENCES comments (id, case_id);

-- Roots and each parent's direct replies are read newest first.
DROP INDEX idx_comments_case_visible;
CREATE INDEX idx_comments_case_roots_visible ON comments (case_id, created_at DESC, id DESC)
    WHERE moderation_status = 'VISIBLE' AND parent_comment_id IS NULL;
CREATE INDEX idx_comments_children_visible ON comments (parent_comment_id, created_at DESC, id DESC)
    WHERE moderation_status = 'VISIBLE';
