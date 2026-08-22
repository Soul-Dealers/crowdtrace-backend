## 2026-08-21 — Add Compose-compatible application and Postgres containers
- Replaced the placeholder Dockerfile with a Java 21 multi-stage application image build.
- Added a Compose application service and Postgres service wired through datasource environment credentials.
- Added Docker build-context exclusions and a safe environment example.
