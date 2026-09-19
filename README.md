# CrowdTrace Backend

CrowdTrace is a Spring Boot backend for a missing-persons registry. The current runtime provides
health probes and a protected user listing; future authentication, public-case, and administration
workflows are not implemented yet.

## Prerequisites

- Java 21
- Docker and Docker Compose for the PostgreSQL-backed `dev` profile

## Local development

Copy `example.env` to `.env` and set the required `SPRING_DATASOURCE_USERNAME` and
`SPRING_DATASOURCE_PASSWORD` values. Then start the development profile:

```bash
docker compose --env-file .env up --build
```

The application is available on port 8080. The test suite uses the H2-backed `test` profile, so it
does not require PostgreSQL:

```bash
./mvnw test
```

## Current endpoints

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| GET | `/actuator/health/liveness` | Public | Liveness probe |
| GET | `/actuator/health/readiness` | Public | Readiness probe including database health |
| GET | `/users` | HTTP Basic | Paginated user projection |
| GET | `/v3/api-docs` | Public | Generated OpenAPI JSON |
| GET | `/swagger-ui.html` | Public | Swagger UI |

`/users` uses the current HTTP Basic placeholder security configuration. No stable credentials are
provided by this project.

The OpenAPI document includes `planned` contract placeholders under `/api/auth`, `/api/public`, and
`/api/admin`. These are not runtime endpoints: they have no controllers or handlers yet.

## Contributing

When adding or changing an endpoint, update its Springdoc annotations or OpenAPI configuration and
the contract tests. Breaking path, response, security, or status-code changes require review before
merge. Keep the README endpoint table limited to implemented runtime behavior and current as routes
change.
