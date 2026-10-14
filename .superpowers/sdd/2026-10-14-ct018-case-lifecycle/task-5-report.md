# Task 5 Report: Projections

## Changes

- Added `CasePresentation` and the public `presentation` field. The public mapper uses `DECEASED_NOTICE` for `FOUND_DECEASED` and withholds last seen location, physical description, clothing, circumstances, and public contact number. The same mapper serves public detail and list queries.
- Added reporter `version`, `resolvedAt`, and `closedAt`, plus admin `takedownReason`, `takenDownBy`, and `takenDownAt`.
- Added authenticated `GET /api/user/cases/{id}`. The controller derives the reporter id from the authenticated principal through `ActorLookup`; the existing owner-filtered query returns 404 for a foreign case.
- Added OpenAPI contracts for the reporter GET and projection schemas. Nullable fields use OpenAPI 3.1 JSON Schema type unions through `JsonSchema`.
- Added projection serialization, Postgres query, endpoint ownership, and OpenAPI contract assertions. The Postgres scenario invokes the production takedown and restore services for a deceased case with a closing statement, then checks both public detail and list projections.

## RED

Command:

```text
DOCKER_HOST=unix:///Users/Edem/.orbstack/run/docker.sock TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -Dtest=CaseProjectionContractTest,CaseQueryServiceTest,CaseLifecycleEndpointTest,OpenApiContractTest test
```

Before implementation, test compilation failed on the newly asserted `presentation`, `version`, `resolvedAt`, and `closedAt` projection accessors, confirming those contract fields were absent. After implementation, the first test run reached runtime and found two OpenAPI gaps: the public schema was not referenced and the existing nested admin projection lacked takedown fields. Both were corrected.

## GREEN

The same focused command passed after the fixes:

```text
Tests run: 53, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

This included Testcontainers PostgreSQL coverage for production takedown and restore followed by public detail and list reads, reporter own/foreign GET behavior, projection serialization, and generated OpenAPI contracts.

## Self-review

- Public JSON gains only `presentation`; standard cases retain their public details. Deceased cases retain the allowed identifying and lifecycle fields while all five D9 details serialize as null.
- Reporter/admin projections retain sensitive/admin lifecycle details; `takedownReason` is absent from public and reporter JSON.
- Takedown reasons remain admin-only in the JSON and schema contracts. The public detail OpenAPI entry remains marked as a planned operation; no public controller/read was added.
- OpenAPI 3.1 nullability assertions check type arrays and date-time formats, not the obsolete `nullable` keyword.
- No audit storage or lifecycle behavior was changed.
