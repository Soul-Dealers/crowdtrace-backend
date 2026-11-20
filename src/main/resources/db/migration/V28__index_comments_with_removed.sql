-- Removed comments now stay in the public thread as placeholders, so the thread indexes cover every row.
DROP INDEX idx_comments_case_roots_visible;
DROP INDEX idx_comments_children_visible;
CREATE INDEX idx_comments_case_roots ON comments (case_id, created_at DESC, id DESC) WHERE parent_comment_id IS NULL;
CREATE INDEX idx_comments_children ON comments (parent_comment_id, created_at DESC, id DESC);
