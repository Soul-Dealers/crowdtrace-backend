# Modular Monolith Foundation Implementation Plan

> **For agentic workers:** This plan was executed inline in the current workspace.

**Goal:** Establish the initial Spring Modulith module map, architecture guardrail, documentation,
and test database support for CrowdTrace.

**Architecture:** Keep one Maven/Spring Boot deployable with direct business modules under the
application package. Each module exposes public APIs at its root and keeps implementation details
under `internal`; module-owned persistence and after-commit side effects are documented as rules.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Modulith 2.0.7, Maven, JUnit 5, H2, PostgreSQL,
and Flyway.

---

### Task 1: Build and documentation baseline

**Files:**

- Modify: `pom.xml`
- Modify: `docs/blueprint.md`
- Modify: `docs/tasks/phase_0_foundation.md`
- Modify: `docs/TASKS_INDEX.md`
- Create: `docs/decisions/ADR-001-modular-monolith.md`

- [x] Add H2 test support.
- [x] Document the eight direct Spring Modulith modules and their public/internal rules.
- [x] Update CT-001 acceptance criteria and test requirements.
- [x] Correct the phase-map links in the task index.
- [x] Record the architecture decision and consequences.

### Task 2: Architecture verification

**Files:**

- Create: `src/test/java/com/souldealers/crowdtracebackend/CrowdtraceModulesTest.java`

- [x] Verify that all planned modules are discovered.
- [x] Run `ApplicationModules.verify()` to detect invalid module dependencies.

### Task 3: Tracked module markers

**Files:**

- Create: `src/main/java/com/souldealers/crowdtracebackend/{identity,casefile,governance,discovery,community,notification,privacy,operations}/package-info.java`

- [x] Add package markers with `@ApplicationModule` display names.
- [x] Keep business behavior out of the scaffolding so the next issue can implement Identity cleanly.

### Task 4: Verification

- [x] Run `./mvnw -q -Dtest=CrowdtraceModulesTest test`.
- [x] Run `./mvnw test`.
- [x] Run `git diff --check`.
