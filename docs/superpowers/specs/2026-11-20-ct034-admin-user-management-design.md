# CT-034 — Admin user management (design)

## Intent

Super Admins can't add, remove or lock out moderators without SQL today; only seeded moderators exist.
CT-034 gives the Super Admin a Users page to promote existing accounts to moderator, demote them, and
deactivate accounts, with every change audited.

## Decided (Edem)

- A moderator is made by **promoting an existing account**. The person signs up normally first. No invite flow.
- List endpoints use **keyset pagination**, not offset paging, to keep get-all requests cheap.

## Design

| Endpoint | Guard | Behavior |
|---|---|---|
| `GET /api/v1/admin/users?role&status&q&cursor&size` | `@RequiresSuperAdmin` | Newest first, keyset on `(created_at, id)` via `SeekCursor`/`CursorResponse`. `q` is a case-insensitive prefix match on email or display name. Rows: id, displayName, email, role, status, createdAt. Size default 20, max 50. |
| `POST /api/v1/admin/users/{id}/role` `{ role }` | `@RequiresSuperAdmin` | Only `REGISTERED_USER` ↔ `MODERATOR`. 403 on self or a `SUPER_ADMIN` target; 400 for `SUPER_ADMIN` as the new role; 409 if already that role or not `ACTIVE`; 404 if missing. Writes `USER.ROLE_CHANGED` (fromRole, toRole). |
| `POST /api/v1/admin/users/{id}/deactivate` | `@RequiresSuperAdmin` | Any non-Super-Admin except self. Sets `DEACTIVATED` and bumps `credentials_version`. 409 if already deactivated. Writes `USER.DEACTIVATED`. |

- Role change and deactivation lock the target row (`PESSIMISTIC_WRITE`) and check eligibility after the lock, so two
  Super Admins acting at once get one change and one 409.
- Each mutation is `@Transactional` + `@AuditedOperation`; a failed audit write rolls the change back.
- Profile updates write only profile columns (targeted update), so a concurrent profile save can't restore an old
  role, status or credentials version (Codex finding, 3×9).
- Access already re-checks role and status from the database on every request (`JwtFilter` → `SecurityUser`), so a
  demotion or deactivation applies on the next request. Login of a deactivated account fails.
- Migration: index `users (created_at DESC, id DESC)` for the seek.

### Web

- `/admin/users`, gated to Super Admin (the existing admin guard also admits moderators). Nav link for Super Admins only.
- Filters (role, status), search box, "Load more" for the cursor.
- Row actions: **Make moderator**, **Remove moderator**, **Deactivate**, each with a confirm dialog. Hidden on the
  viewer's own row and on Super Admin rows. 409 shows "Already changed by another admin" and refreshes.
- The app re-reads the signed-in user from `/me` on load, so a promoted or demoted user sees the right menu without
  logging in again (Codex finding, 8×3).

## Approaches considered

1. **Promote existing accounts** (chosen): no password handling, reuses sign-up and email verification.
2. Super Admin creates the account and emails a set-password link: needs an invite flow and template. Rejected.

## Non-goals

- Reactivation (not asked for; a mistaken deactivation needs a DB fix). Small to add later.
- Free-text reasons: the audit log is closed-vocabulary by design; it records actor, time, and from/to values.
- Site settings: none exist yet.
- Creating Super Admins through the app.

## Left to the builder

Response DTO names, test class layout, exact copy of confirm dialogs.
