package com.souldealers.crowdtracebackend.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class OpenApiConfig {

    private static final String PLANNED_DESCRIPTION =
            "Contract placeholder only. This operation is planned and not implemented in the current runtime. "
                    + "No controller exists yet; implementation is scheduled for a later domain phase.";

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
                        new io.swagger.v3.oas.models.tags.Tag()
                                .name("Operations")
                                .description("Application health probes"),
                        new io.swagger.v3.oas.models.tags.Tag()
                                .name("Identity")
                                .description("Current identity endpoints"),
                        new io.swagger.v3.oas.models.tags.Tag()
                                .name("Authentication")
                                .description("Planned authentication endpoints"),
                        new io.swagger.v3.oas.models.tags.Tag()
                                .name("Public Cases")
                                .description("Planned public case registry endpoints"),
                        new io.swagger.v3.oas.models.tags.Tag()
                                .name("Administration")
                                .description("Planned moderator and admin endpoints")))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"))
                .components(new Components()
                        .addSecuritySchemes("basicAuth", new SecurityScheme()
                                .name("basicAuth")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic"))
                        .schemas(reusableSchemas()))
                .paths(contractPaths());
    }

    private Map<String, Schema> reusableSchemas() {
        Map<String, Schema> schemas = new LinkedHashMap<>();
        schemas.put("ProblemDetail", problemDetailSchema());
        schemas.put("ApiResponse", apiResponseSchema());
        schemas.put("PagedResponse", pagedResponseSchema());
        schemas.put("UserResponse", userResponseSchema());
        schemas.put("UserPageResponse", userPageResponseSchema());
        schemas.put("UserListResponse", userListResponseSchema());
        schemas.put("HealthResponse", healthResponseSchema());
        return schemas;
    }

    private Schema problemDetailSchema() {
        return new ObjectSchema()
                .description("RFC 9457 problem details returned for API errors.")
                .addProperties("type", new StringSchema().format("uri"))
                .addProperties("title", new StringSchema())
                .addProperties("status", new IntegerSchema().format("int32"))
                .addProperties("detail", new StringSchema())
                .addProperties("instance", new StringSchema().format("uri"))
                .addProperties("code", new StringSchema())
                .addProperties("correlationId", new StringSchema())
                .addProperties("errors", new ObjectSchema());
    }

    private Schema apiResponseSchema() {
        return new ObjectSchema()
                .description("Standard successful CrowdTrace response envelope.")
                .addProperties("success", new BooleanSchema())
                .addProperties("message", new StringSchema())
                .addProperties("data", new Schema<>().$ref("#/components/schemas/PagedResponse"))
                .addProperties("errors", new ObjectSchema());
    }

    private Schema pagedResponseSchema() {
        return new ObjectSchema()
                .description("Page metadata and content returned by list endpoints.")
                .addProperties("content", new ArraySchema().items(new ObjectSchema()))
                .addProperties("page", new IntegerSchema().format("int32"))
                .addProperties("size", new IntegerSchema().format("int32"))
                .addProperties("totalElements", new IntegerSchema().format("int64"))
                .addProperties("totalPages", new IntegerSchema().format("int32"))
                .addProperties("last", new BooleanSchema());
    }

    private Schema userResponseSchema() {
        return new ObjectSchema()
                .description("User projection returned by the current user listing endpoint.")
                .addProperties("displayName", new StringSchema())
                .addProperties("email", new StringSchema().format("email"))
                .addProperties("role", new StringSchema())
                .addProperties("accountStatus", new StringSchema())
                .addProperties("createdAt", new StringSchema().format("date-time"));
    }

    private Schema userPageResponseSchema() {
        return new ObjectSchema()
                .description("Concrete page shape returned by the current user listing endpoint.")
                .addProperties("content", new ArraySchema().items(
                        new Schema<>().$ref("#/components/schemas/UserResponse")))
                .addProperties("page", new IntegerSchema().format("int32"))
                .addProperties("size", new IntegerSchema().format("int32"))
                .addProperties("totalElements", new IntegerSchema().format("int64"))
                .addProperties("totalPages", new IntegerSchema().format("int32"))
                .addProperties("last", new BooleanSchema());
    }

    private Schema userListResponseSchema() {
        return new ObjectSchema()
                .description("Concrete response envelope returned by the current user listing endpoint.")
                .addProperties("success", new BooleanSchema())
                .addProperties("message", new StringSchema())
                .addProperties("data", new Schema<>().$ref("#/components/schemas/UserPageResponse"))
                .addProperties("errors", new ObjectSchema());
    }

    private Schema healthResponseSchema() {
        return new ObjectSchema()
                .description("Spring Boot health probe response.")
                .addProperties("status", new StringSchema())
                .addProperties("components", new ObjectSchema());
    }

    private Paths contractPaths() {
        return new Paths()
                .addPathItem("/actuator/health/liveness", healthPath(
                        "Liveness probe", "Returns the current liveness status."))
                .addPathItem("/actuator/health/readiness", healthPath(
                        "Readiness probe", "Returns the current readiness status, including database health."))
                .addPathItem("/api/auth/register", new PathItem().post(publicPlannedOperation(
                        "Authentication", "Register", "Planned user registration operation.")))
                .addPathItem("/api/auth/login", new PathItem().post(publicPlannedOperation(
                        "Authentication", "Login", "Planned user login operation.")))
                .addPathItem("/api/auth/refresh", new PathItem().post(publicPlannedOperation(
                        "Authentication", "Refresh authentication", "Planned authentication refresh operation.")))
                .addPathItem("/api/public/cases", new PathItem().get(publicPlannedOperation(
                        "Public Cases", "List public cases", "Planned public case listing operation.")))
                .addPathItem("/api/public/cases/{caseId}", new PathItem().get(
                        publicPlannedOperation("Public Cases", "Get public case", "Planned public case detail operation.")
                                .parameters(List.of(caseIdParameter()))))
                .addPathItem("/api/admin/cases/review", new PathItem().get(plannedOperation(
                        "Administration", "Review cases", "Planned case review queue operation.")))
                .addPathItem("/api/admin/cases/{caseId}/decision", new PathItem().post(
                        plannedOperation("Administration", "Decide case", "Planned case decision operation.")
                                .parameters(List.of(caseIdParameter()))));
    }

    private PathItem healthPath(String summary, String description) {
        return new PathItem().get(new Operation()
                .tags(List.of("Operations"))
                .summary(summary)
                .description(description)
                .security(List.of())
                .responses(new ApiResponses().addApiResponse("200", jsonResponse(
                        "Health status", "#/components/schemas/HealthResponse"))
                        .addApiResponse("default", jsonResponse(
                                "Problem details", "#/components/schemas/ProblemDetail"))));
    }

    private Operation publicPlannedOperation(String tag, String summary, String description) {
        return plannedOperation(tag, summary, description).security(List.of());
    }

    private Operation plannedOperation(String tag, String summary, String description) {
        Operation operation = new Operation()
                .tags(List.of(tag))
                .summary(summary)
                .description(description + " " + PLANNED_DESCRIPTION)
                .responses(new ApiResponses().addApiResponse("200", new io.swagger.v3.oas.models.responses.ApiResponse()
                        .description("Contract placeholder only; no runtime response is available.")
                        .content(new Content().addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ApiResponse"))))));
        operation.addExtension("x-crowdtrace-status", "planned");
        return operation;
    }

    private Parameter caseIdParameter() {
        return new Parameter()
                .name("caseId")
                .in("path")
                .required(true)
                .description("Planned case identifier.")
                .schema(new StringSchema());
    }

    private io.swagger.v3.oas.models.responses.ApiResponse jsonResponse(String description, String schemaReference) {
        return new io.swagger.v3.oas.models.responses.ApiResponse()
                .description(description)
                .content(new Content().addMediaType("application/json", new MediaType()
                        .schema(new Schema<>().$ref(schemaReference))));
    }
}
