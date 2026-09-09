# ADR-001: Use a Spring Modulith Modular Monolith

- **Status:** Accepted
- **Date:** 2026-08-22

## Context

CrowdTrace is a single Spring Boot application whose planned capabilities have clear business
boundaries: identity, case intake, governance, public discovery, community activity, notifications,
privacy, and operations. The product is still evolving, and important workflows such as case approval,
status changes, audit recording, and retention cleanup require strong transactional consistency.

Splitting these capabilities into independently deployed services would add operational and distributed
systems complexity before there is evidence that independent scaling or deployment is needed.

## Decision

CrowdTrace will use a modular monolith: one Maven project, one deployable Spring Boot application,
and initially one PostgreSQL database, with explicit Spring Modulith business modules inside the
application package.

The initial modules are:

- `identity`
- `casefile`
- `governance`
- `discovery`
- `community`
- `notification`
- `privacy`
- `operations`

Each module exposes a small public API at its package root. Implementation details, persistence
models, repositories, and adapters belong under `internal` and are not importable by other modules.
Each module owns its tables; cross-module JPA relationships and repository access are prohibited.

Synchronous public application services are used when a workflow requires an immediate result or
transactional consistency. After-commit application events are used for notifications, analytics,
and other side effects that must not run before the originating transaction succeeds.

## Consequences

### Positive

- Domain code stays close to the capability it serves.
- Approval, audit, lifecycle, and privacy workflows can remain transactionally consistent.
- The application keeps the deployment simplicity of the current Spring Boot design.
- A future service extraction has a clearer starting boundary if it becomes necessary.

### Negative

- The build and deployment blast radius remains centralized.
- Module boundaries can erode if the architecture test and package rules are not maintained.
- Shared support code requires discipline so it does not become a second monolith inside `shared`.

## Verification

`CrowdtraceModulesTest` verifies that the planned modules are discovered and that Spring Modulith's
dependency verification passes. New cross-module dependencies must be reflected in that test and
reviewed as an architecture change.
