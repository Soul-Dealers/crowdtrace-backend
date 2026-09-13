# Initial OpenAPI Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish an honest, code-first OpenAPI contract for the current CrowdTrace API and declarative placeholders for future auth, public-case, and admin namespaces.

**Architecture:** Follow Kado's established Spring configuration pattern by adding one `OpenAPI` bean under CrowdTrace's existing `shared.config` package. Use controller annotations for the implemented `/users` operation and model classes for reusable response schemas; add future operations directly to the OpenAPI model with a planned-status extension and no controllers.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Springdoc OpenAPI 3.0.2, Spring Security HTTP Basic, Spring MVC MockMvc, JUnit 5, Maven Wrapper.

---

## File map

- Create `src/main/java/com/souldealers/crowdtracebackend/shared/config/OpenApiConfig.java` — API metadata, security scheme, reusable schemas, current actuator paths, and future contract-only paths.
- Modify `src/main/java/com/souldealers/crowdtracebackend/modules/identity/AuthController.java` — add tag, operation, pageable parameter metadata, and current security requirement to `/users`.
- Modify `src/main/java/com/souldealers/crowdtracebackend/security/SecurityConfig.java` — allow the generated Swagger UI and JSON document to render locally without credentials.
- Modify `src/main/java/com/souldealers/crowdtracebackend/shared/ApiResponse.java` — add schema metadata for the shared success envelope.
- Modify `src/main/java/com/souldealers/crowdtracebackend/shared/PagedResponse.java` — add schema metadata for the shared page shape.
- Create `src/test/java/com/souldealers/crowdtracebackend/OpenApiContractTest.java` — verify the generated contract and its public access.
- Create `README.md` — document only the currently implemented setup and endpoints, plus contract contribution rules.

## Task 1: Write the failing OpenAPI contract test

**Files:**

- Create: `src/test/java/com/souldealers/crowdtracebackend/OpenApiContractTest.java`

- [ ] **Step 1: Add a Spring Boot MockMvc contract test**

Use the test profile and request the generated JSON document without authentication:

```java
package com.souldealers.crowdtracebackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesTheCurrentAndPlannedApiContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.1.0"))
                .andExpect(jsonPath("$.info.title").value("CrowdTrace API"))
                .andExpect(jsonPath("$.components.securitySchemes.basicAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.basicAuth.scheme").value("basic"))
                .andExpect(jsonPath("$.paths['/users'].get").exists())
                .andExpect(jsonPath("$.paths['/actuator/health/liveness'].get").exists())
                .andExpect(jsonPath("$.paths['/actuator/health/readiness'].get").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post['x-crowdtrace-status']")
                        .value("planned"))
                .andExpect(jsonPath("$.paths['/api/public/cases'].get['x-crowdtrace-status']")
                        .value("planned"))
                .andExpect(jsonPath("$.paths['/api/admin/cases/review'].get['x-crowdtrace-status']")
                        .value("planned"))
                .andExpect(jsonPath("$.components.schemas.ProblemDetail").exists())
                .andExpect(jsonPath("$.components.schemas.PagedResponse").exists());
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails for the missing contract**

Run:

```bash
./mvnw -Dtest=OpenApiContractTest test
```

Expected: the test fails because `/v3/api-docs` is currently protected or lacks the metadata,
schemas, and planned paths.

## Task 2: Add code-first OpenAPI configuration

**Files:**

- Create: `src/main/java/com/souldealers/crowdtracebackend/shared/config/OpenApiConfig.java`

- [ ] **Step 1: Add the Kado-style OpenAPI bean skeleton**

Create a `@Configuration` class exposing `OpenAPI openAPI()` with `Info`, local `Server`, five tags,
and an HTTP Basic scheme:

```java
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("CrowdTrace API")
                        .version("0.1.0")
                        .description("Backend API for the CrowdTrace missing-persons registry."))
                .servers(List.of(new Server()
                        .url("http://localhost:8080")
                        .description("Local development server")))
                .tags(List.of(
                        new Tag().name("Operations").description("Application health probes"),
                        new Tag().name("Identity").description("Current identity endpoints"),
                        new Tag().name("Authentication").description("Planned authentication endpoints"),
                        new Tag().name("Public Cases").description("Planned public case registry endpoints"),
                        new Tag().name("Administration").description("Planned moderator and admin endpoints")))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"))
                .components(new Components()
                        .addSecuritySchemes("basicAuth", new SecurityScheme()
                                .name("basicAuth")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")))
                .schemas(reusableSchemas())
                .paths(contractPaths());
    }
}
```

Use the OpenAPI model classes from `io.swagger.v3.oas.models` and keep this class focused on contract
composition. Do not import or advertise JWT types.

- [ ] **Step 2: Add reusable response schemas**

Define these component schemas:

- `ProblemDetail`: `type`, `title`, `status`, `detail`, `instance`, `code`, `correlationId`, and an
  object-valued `errors` property.
- `ApiResponse`: `success`, `message`, `data`, and `errors`.
- `PagedResponse`: `content`, `page`, `size`, `totalElements`, `totalPages`, and `last`.
- `HealthResponse`: `status` plus an object-valued optional `components` property.

Use `ObjectSchema`, `ArraySchema`, `StringSchema`, `IntegerSchema`, and `BooleanSchema` so the
generated JSON contains reusable schema names rather than only anonymous objects.

- [ ] **Step 3: Add current health paths and future contract-only paths**

Add explicit `PathItem` operations for:

| Path | Method | Status | Security | Runtime status |
|---|---|---|---|---|
| `/actuator/health/liveness` | GET | current | public | implemented |
| `/actuator/health/readiness` | GET | current | public | implemented |
| `/api/auth/register` | POST | planned | public | placeholder only |
| `/api/auth/login` | POST | planned | public | placeholder only |
| `/api/auth/refresh` | POST | planned | public | placeholder only |
| `/api/public/cases` | GET | planned | public | placeholder only |
| `/api/public/cases/{caseId}` | GET | planned | public | placeholder only |
| `/api/admin/cases/review` | GET | planned | `basicAuth` | placeholder only |
| `/api/admin/cases/{caseId}/decision` | POST | planned | `basicAuth` | placeholder only |

For each planned operation, call `addExtension("x-crowdtrace-status", "planned")` and include this
description:

```text
Contract placeholder only. This operation is planned and not implemented in the current runtime.
No controller exists yet; implementation is scheduled for a later domain phase.
```

Use `security(List.of())` on public current/planned operations to override the document-wide Basic
security requirement. Leave the admin placeholders protected by the global `basicAuth` requirement.

- [ ] **Step 4: Run the focused test and verify it passes after configuration is complete**

Run:

```bash
./mvnw -Dtest=OpenApiContractTest test
```

Expected: PASS, with metadata, reusable schemas, current paths, and planned extensions present.

## Task 3: Document the implemented `/users` operation

**Files:**

- Modify: `src/main/java/com/souldealers/crowdtracebackend/modules/identity/AuthController.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/ApiResponse.java`
- Modify: `src/main/java/com/souldealers/crowdtracebackend/shared/PagedResponse.java`

- [ ] **Step 1: Add controller-level identity metadata**

Add these annotations and imports without changing the route or method body:

```java
@Tag(name = "Identity", description = "Current identity endpoints")
@SecurityRequirement(name = "basicAuth")
@RestController
public class AuthController {
```

- [ ] **Step 2: Add operation and pageable metadata**

Annotate the method and pageable parameter:

```java
@Operation(
        summary = "List users",
        description = "Returns the current paginated user projection. Requires HTTP Basic authentication.")
@GetMapping("/users")
public ApiResponse<PagedResponse<UserResponse>> getUsers(
        @ParameterObject Pageable pageable) {
```

This follows Kado's use of `@Operation` and `@ParameterObject`, while preserving CrowdTrace's current
unversioned `/users` route.

- [ ] **Step 3: Add schema descriptions to shared envelopes**

Annotate the records with `@Schema` descriptions while retaining their fields and factory behavior:

```java
@Schema(name = "ApiResponse", description = "Standard successful CrowdTrace response envelope")
public record ApiResponse<T>(boolean success, String message, T data, Object errors) { }

@Schema(name = "PagedResponse", description = "Page metadata and content returned by list endpoints")
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last) { }
```

Keep the existing static factory methods unchanged.

- [ ] **Step 4: Run the current identity and contract tests**

Run:

```bash
./mvnw -Dtest=OpenApiContractTest,UserRepositoryTest test
```

Expected: PASS.

## Task 4: Make local documentation reachable

**Files:**

- Modify: `src/main/java/com/souldealers/crowdtracebackend/security/SecurityConfig.java`

- [ ] **Step 1: Permit only documentation resources**

Extend the existing authorization rules so the current health rules remain public and the generated
documentation is also public:

```java
.requestMatchers(
        "/actuator/health/liveness",
        "/actuator/health/readiness",
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/v3/api-docs/**")
.permitAll()
```

Keep `.anyRequest().authenticated()` unchanged so `/users` and future unimplemented routes do not
become accidentally public.

- [ ] **Step 2: Run the contract test and full suite**

Run:

```bash
./mvnw -Dtest=OpenApiContractTest test
./mvnw test
```

Expected: both commands pass. Existing health, exception, logging, module, application-context, and
repository tests must remain green.

## Task 5: Add current-state README and contribution guidance

**Files:**

- Create: `README.md`

- [ ] **Step 1: Document prerequisites and local startup**

Add the project name and current scope, then document:

```text
Prerequisites:
- Java 21
- Docker and Docker Compose for the PostgreSQL-backed dev profile

Tests:
./mvnw test

Local application:
1. Copy example.env to .env and set SPRING_DATASOURCE_USERNAME and SPRING_DATASOURCE_PASSWORD.
2. Run docker compose --env-file .env up --build.
3. Open http://localhost:8080/swagger-ui.html.
```

Explain that tests use the H2 `test` profile and the dev application uses the existing PostgreSQL
Compose service. Do not claim registration, JWT, case workflows, moderation, or notifications exist.

- [ ] **Step 2: Document only implemented endpoints**

Include this current endpoint table:

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/actuator/health/liveness` | Public | Liveness probe |
| GET | `/actuator/health/readiness` | Public | Readiness probe including database health |
| GET | `/users` | HTTP Basic | Paginated user projection |
| GET | `/v3/api-docs` | Public | Generated OpenAPI JSON |
| GET | `/swagger-ui.html` | Public | Swagger UI |

Add a note that the OpenAPI document contains `planned` placeholders under `/api/auth`,
`/api/public`, and `/api/admin`; these paths are not runtime endpoints yet.

- [ ] **Step 3: Add contract contribution rules**

Document these review rules:

- New or changed endpoints must update Springdoc annotations/configuration and the contract test.
- Response envelope, pagination, path, authentication, or status-code changes must be called out in
  the pull request.
- Breaking contract changes require review before merging.
- README endpoint tables must describe runtime behavior only.

- [ ] **Step 4: Verify README commands and links**

Run:

```bash
./mvnw test
```

Then, when Docker credentials are available locally, run the documented Compose command and verify:

```bash
curl --fail http://localhost:8080/actuator/health/liveness
curl --fail http://localhost:8080/v3/api-docs
```

The README must not instruct users to call any planned placeholder path.

## Task 6: Final verification and handoff

- [ ] **Step 1: Inspect the final diff**

Run:

```bash
git diff -- README.md \
  src/main/java/com/souldealers/crowdtracebackend/shared/config/OpenApiConfig.java \
  src/main/java/com/souldealers/crowdtracebackend/modules/identity/AuthController.java \
  src/main/java/com/souldealers/crowdtracebackend/security/SecurityConfig.java \
  src/main/java/com/souldealers/crowdtracebackend/shared/ApiResponse.java \
  src/main/java/com/souldealers/crowdtracebackend/shared/PagedResponse.java \
  src/test/java/com/souldealers/crowdtracebackend/OpenApiContractTest.java
```

Confirm that no existing user-modified files are included in the feature diff.

- [ ] **Step 2: Run the complete verification command**

Run:

```bash
./mvnw clean test
```

Expected: BUILD SUCCESS with all tests passing.

- [ ] **Step 3: Report the handoff**

Summarize the generated contract, the exact runtime-versus-placeholder boundary, test results, and
any Docker verification that could not be run because external database credentials were unavailable.
