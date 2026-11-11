# Nested case replies and author deletion

Approved by Edem: inline Reply controls, persisted nested replies and expandable paginated threads. Additional requirement: author-deleted comments display “Comment has been deleted by author” and retain readable descendants.

- Store immutable parent comment links. PostgreSQL enforces same-case parent relationships. Existing comments remain roots.
- GET comments without parentCommentId lists roots; with parentCommentId it lists that parent’s direct children. Every level uses existing cursor pagination (1–50, default 20).
- POST accepts optional parentCommentId. Only registered users can post; parent must belong to the available case, be visible and not author-deleted.
- DELETE by the author erases stored text and records deleted_at. Repeat deletion is harmless. Another user cannot delete it. Removed comments remain excluded under existing moderation rules.
- Public deleted-comment responses omit text and author identity, retain IDs, dates and visible direct-reply counts. Existing replies under author-deleted parents remain readable at every depth.
- Each live comment has an inline reply composer and an expandable replies control. Authors see Delete with an inline confirmation. Indentation stops growing after two levels to preserve mobile reading width.
- Keep all audit tables immutable and untouched. Maintain pseudonymous public projections and case visibility checks.
