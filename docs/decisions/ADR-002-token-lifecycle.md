# ADR-002: Short-Lived Access Tokens with Logout Revocation, No Refresh Token

- **Status:** Accepted
- **Date:** 2026-09-29

## Context

CT-007 asked for a credential lifecycle and said to "record the decision if the token model
changes." CT-009 then asked for "the selected refresh or logout endpoint." The selection was made
in code during CT-007 but never written down, and two artefacts ended up asserting the opposite:

- `docs/tasks/phase_1_identity.md` still claimed "There is no refresh-token or logout endpoint in
  CT-007," which stopped being true once `POST /api/v1/auth/logout` shipped.
- `OpenApiConfig` published `/api/auth/register`, `/api/auth/login`, and `/api/auth/refresh` as
  `x-crowdtrace-status: planned` placeholders, advertising a refresh endpoint that was never going
  to be built and unversioned paths that the implementation did not use.

A refresh token is the conventional alternative. It would let access tokens live for minutes rather
than 15 of them, at the cost of a second credential to store, rotate, detect reuse on, and revoke —
a stateful store CrowdTrace does not otherwise need. CrowdTrace is a web client talking to one
deployable; it has no offline or long-lived background sessions that a refresh token would serve.

## Decision

Authentication uses a single short-lived signed JWT access token. There is no refresh token and no
`/refresh` endpoint.

Three mechanisms revoke a credential before it expires:

1. **Expiry.** `jwt.access-token-expiry` bounds every token's lifetime (900s in dev and test).
2. **Logout.** `POST /api/v1/auth/logout` records the presented token in `revoked_tokens` via
   `TokenRevocationService`; `JwtFilter` rejects it for the remainder of its lifetime.
   `ExpiredCredentialCleanupJob` purges rows once they can no longer be replayed.
3. **Credentials version.** `users.credentials_version` is embedded in every token. Bumping it —
   on password reset, or on an account-status change — invalidates every token issued before the
   bump at once. Editing a profile deliberately does not bump it.

Only `ACTIVE` accounts can authenticate, so a status change also acts as revocation.

All authentication endpoints live under the versioned prefix `/api/v1/auth`. The unversioned
`/api/auth` aliases and the planned-path placeholders are removed.

## Consequences

### Positive

- One credential to reason about. No refresh-token rotation, reuse detection, or family revocation.
- Revocation is immediate and testable at two granularities: one token (logout) and every token
  for a user (credentials version).
- The published OpenAPI document now describes only endpoints that exist.

### Negative

- Users re-authenticate every 15 minutes of inactivity. Acceptable for a web client; revisit if a
  mobile or offline client is added.
- `revoked_tokens` is a write on every logout and a read on every authenticated request. Bounded by
  the token lifetime and pruned by `ExpiredCredentialCleanupJob`, but it is state on the hot path.
- Reversing this decision means introducing a refresh credential and its rotation rules, not just
  adding an endpoint.

## Verification

- `SessionInvalidationTest` — credentials-version bumps invalidate old tokens; profile edits do not.
- `CurrentUserProfileTest.logoutRevokesTheCurrentJwt` — logout invalidates the presented token.
- `TokenRevocationRaceTest` — concurrent revocation of the same token.
- `JwtExpiryTest` — expiry is enforced.
- `OpenApiContractTest` — no `/refresh` path is published under either prefix.
- `CanonicalPathTest` — every auth endpoint is mapped exactly once, under `/api/v1/auth`.
