# CT-005 Initial OpenAPI Contract Design

**Date:** 2026-09-13  
**Issue:** [CT-005 — Define the initial OpenAPI contract and repository workflow](https://github.com/Soul-Dealers/crowdtrace-backend/issues/5)

## Goal

Make the current Spring Boot API discoverable through a stable, reviewable OpenAPI contract while
reserving the future authentication, public case, and administration namespaces without making those
future routes callable before their domain behavior exists.

## Context

CrowdTrace already depends on Springdoc and exposes a paged `GET /users` endpoint plus liveness and
readiness actuator endpoints. The shared `ApiResponse`, `PagedResponse`, and `GlobalExceptionHandler`
types establish the response conventions that the contract should describe. Security currently uses
Spring Security's HTTP Basic placeholder configuration, so the OpenAPI document must not advertise
JWT or another authentication mechanism yet.

Kado's existing `OpenApiConfig` confirms the preferred local pattern: a small configuration class
publishes an `OpenAPI` bean with reusable security components, while controller annotations document
implemented operations.

## Design

### OpenAPI configuration

Create `shared.config.OpenApiConfig` with:

- CrowdTrace title, description, version, and local server metadata.
- Tags for Operations, Identity, Authentication, Public Cases, and Administration.
- An HTTP Basic security scheme named `basicAuth`, matching the current security filter chain.
- Reusable `ProblemDetail`, `ApiResponse`, `PagedResponse`, and health response schemas.
- Explicit actuator health operations because management endpoints are not guaranteed to be included
  by Springdoc's controller scan.
- Declarative future paths for authentication, public cases, and administration. Each operation will
  carry an `x-crowdtrace-status: planned` extension and state that it is a contract placeholder only;
  no controller or runtime behavior will be added for these paths.

### Implemented controller documentation

Annotate the current `/users` controller with an Identity tag, operation summary/description, pageable
parameter support, and the current HTTP Basic security requirement. Keep its existing route and response
behavior unchanged.

Permit `/swagger-ui.html`, `/swagger-ui/**`, and `/v3/api-docs/**` through the current security filter
chain so the local documentation actually renders without credentials. Health endpoints remain public;
`/users` remains protected.

### README and repository workflow

Add a root `README.md` covering only the current implementation:

- Java/Docker prerequisites and local environment setup.
- Docker Compose startup and the existing H2-backed test command.
- Current health, users, and documentation endpoints.
- The generated Swagger UI and raw OpenAPI URLs.
- The fact that the authentication, case, and admin paths visible in OpenAPI are planned placeholders,
  not implemented endpoints.
- Contribution guidance: endpoint changes must update OpenAPI annotations/configuration and tests;
  breaking response/path/security changes require review before merging.

The README will not describe future registration, case submission, moderation, or token behavior as
available functionality.

## Testing

Add an integration test that loads the test profile and verifies:

- `/v3/api-docs` is publicly accessible.
- API metadata and the `basicAuth` scheme are present.
- `/users` and both health paths are present.
- Planned authentication, public case, and admin paths are present and marked as planned.
- Reusable error and pagination schemas are present.

Run the existing full Maven test suite afterward to prove that the current runtime behavior remains
green.

## Alternatives rejected

- A static YAML-only contract would duplicate Springdoc's generated view and drift from controller
  behavior.
- Adding placeholder controllers would create misleading runtime endpoints and cross the current
  product-scope boundary.
- Advertising JWT because another project uses it would misrepresent CrowdTrace's current HTTP Basic
  placeholder security.
