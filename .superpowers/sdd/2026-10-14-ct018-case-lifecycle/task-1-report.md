# CT-018 Task 1 report: schema and policy

Implemented the V19 lifecycle constraints, `CaseRecord` takedown attribution fields, lifecycle transition policy and text normalization utilities, and the `CASE.RESTORED` audit action. Updated existing casefile and governance test fixtures so taken-down cases include attribution and resolved cases retain the timestamp shape required by V19.

## Decisions

- Used the static API selected in the Opus preflight: `CaseLifecyclePolicy.requireStatusChange(review, current, target, admin)`, `requireTakedown(review)`, and `requireRestore(review)`. Invalid transitions throw `ConflictException`.
- `LifecycleText.normalize(value, label)` uses `String.strip()`, rejects null or blank text with `ValidationException`, and caps the stripped value using Java `String.length()` at 2,000. That follows the existing DTO `@Size` convention. The boundary tests use BMP characters; supplementary code point counting is not separately tested.
- V19 uses `COALESCE` in the resolved and closed timestamp checks, so a null `case_status` cannot make the check evaluate to SQL `NULL`. A statement requires a non-null, non-`MISSING` outcome, non-whitespace content and at most 2,000 database characters. Takedown attribution must be entirely absent or entirely present; the taken-down state also requires an outcome and full attribution.

## Verification

All PostgreSQL runs used `DOCKER_HOST=unix:///Users/Edem/.orbstack/run/docker.sock TESTCONTAINERS_RYUK_DISABLED=true`.

- RED: the initial full baseline picked up the new migration tests before V19 existed. Invalid shapes were accepted and inserts referencing the new columns failed. Its captured output is in `baseline.log`.
- RED: `./mvnw -Dtest=CaseLifecyclePolicyTest,LifecycleTextTest test` failed compilation because the lifecycle APIs and `CaseRecord` fields had not yet been implemented.
- GREEN: `./mvnw -Dtest=CaseLifecycleMigrationTest test` passed: 14 tests, 0 failures/errors.
- GREEN: the combined run covering `SchemaValidationTest`, `CaseLifecyclePolicyTest`, and `LifecycleTextTest` passed those three suites: 175 tests total. The migration suite in that first combined run exposed an untyped null parameter in its SQL fixture; I added an explicit `BIGINT` cast and reran it separately with all 14 tests passing.
- Full suite: `./mvnw test` ran 1,089 tests with no assertion failures and two fixture errors. `DuplicateDetectionServiceTest.comparesEveryPublicCaseStatus` changed outcomes without updating timestamps; `CaseRecordRepositoryTest.optimisticLockRejectsAStaleUpdate` set a closing statement on a pending case. Both fixtures were repaired without changing the concurrency assertions.
- GREEN after those repairs: `./mvnw -Dtest=DuplicateDetectionServiceTest,CaseRecordRepositoryTest test` passed 46 tests, 0 failures/errors. The other suites passed during the full run; only these two changed fixtures needed the focused rerun.
- `git diff --check` passed.

The JVM/Mockito and Testcontainers logging emitted environment warnings during tests; no test or schema-validation warning indicated a Task 1 defect.
