# ADR-001: Nested case replies and author-deleted placeholders

- **Status:** Proposed for implementation; user approved the feature and requested planning only.
- **Date:** 2026-11-11
- **Feature spec:** ../superpowers/specs/2026-11-11-nested-case-replies.md

## Context

The case discussion UI only shows Reply when a handler is provided, but the live case page supplies none. Comments have no parent association. Edem wants inline replies, replies to replies, and readable descendants beneath an author-deleted placeholder.

## Decision

Use a nullable, immutable parent_comment_id on comments, with a composite foreign key that requires parent and child to belong to the same case. Existing rows remain root comments. Paginate direct children separately at every depth using the existing creation-time/ID cursor ordering.

Author deletion sets deleted_at and replaces stored body with the nonblank sentinel [deleted]. It does not delete the row, move its children or modify any audit row. Public projection sets author and body to null and marks deleted=true. The UI renders “Comment has been deleted by author” while preserving the replies control and nested descendants. Author deletion and moderator removal remain distinct states.

Allow reading children of an author-deleted parent. Reject new direct replies to that parent, while live descendants remain valid reply targets. Lock the parent during reply creation and the target during deletion so their ordering is deterministic.

## Invariants and consequences

- Same-case links are enforced by PostgreSQL and validated by the service; existing comments keep their IDs and contents.
- All levels remain bounded to 1–50 items per request, default 20. Deep threads load on expansion rather than as an unbounded recursive response.
- Deleted body and author identity never appear in public responses; existing descendants remain readable.
- Only the actual author can delete; repeated deletion is harmless and does not change the original deletion timestamp.
- Public responses stay independent of the viewer. The UI may compare the current profile’s avatar seed for action visibility; the backend always enforces ownership using the authenticated user ID.
- Audit tables and repositories remain unchanged and append-only.

## Alternatives considered

- Loading entire nested trees in one response violates the bounded-read invariant for large discussions.
- Hard deletion with cascading children violates the requirement that descendants remain readable.
- Keeping the original body and hiding it only in React violates the public-data invariant.
- A closure table adds storage and write complexity when the required operation is paginated direct-child retrieval; it is unnecessary for this scope.

## Proof

The implementation plan names endpoint, migration, projection and interaction checks for each invariant, including deletion of both root and nested parents with surviving descendants.
