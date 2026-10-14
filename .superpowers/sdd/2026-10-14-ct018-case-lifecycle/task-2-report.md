# Task 2 report: lifecycle notification event contracts

## Result

Added the four notification event records from D12 and the notification-owned `NotifiedCaseStatus` enum. Each factory builds its stable `eventId` from the corresponding audit id. `CasePhotosAddedEvent` copies `fileIds` with `List.copyOf`, retaining order and preventing later mutation.

Added `LifecycleEventContractTest` to enforce the exact record component names, event id prefixes, and photo-id immutability.

## Verification

RED command:

```text
DOCKER_HOST=unix:///Users/Edem/.orbstack/run/docker.sock TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -Dtest=LifecycleEventContractTest test
```

Result: expected compile failure before implementation because the four records and `NotifiedCaseStatus` did not yet exist.

GREEN command:

```text
DOCKER_HOST=unix:///Users/Edem/.orbstack/run/docker.sock TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -Dtest=LifecycleEventContractTest test
```

Result: `BUILD SUCCESS`; 3 tests run, 0 failures, 0 errors, 0 skipped.

## Self-review

- Event record component lists match D12 exactly.
- Contracts contain ids, statuses, actor kind, and timestamp only; no names, text, reasons, emails, or storage keys were introduced.
- Notification types have no dependency on `casefile`; mapping remains the publisher's responsibility.
- The status actor is represented by nested `ActorKind.REPORTER` / `ActorKind.ADMIN`.
- Photo ids are defensively copied in the record constructor, preserving their original order.
- No audit persistence or mutation behavior was changed.
